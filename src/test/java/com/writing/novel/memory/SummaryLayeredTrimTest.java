package com.writing.novel.memory;

import com.writing.novel.domain.Project;
import com.writing.novel.domain.Summary;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * p16-06: 前情摘要分层裁剪 + premise 参与预算裁剪。
 *
 * 用例A（远期挤死近期）：50 卷卷级摘要 + 最近 3 章细粒度摘要、预算受限时，
 *   裁剪必须分层——先丢最老卷 stub、再丢次老、最后才动细粒度摘要；
 *   修复前 buildRecentSummary 把细粒度摘要放在字符串最尾，applyTokenBudget
 *   的整体尾部截断最先牺牲衔接最关键的近期摘要。
 *
 * 用例B（premise 不参与预算）：超长 premise（>6000 token）修复前从不被裁剪，
 *   50 卷总纲每章所有 LLM 调用固定背这笔 token；修复后保头部截断并加显式标记。
 */
class SummaryLayeredTrimTest {

    @TempDir
    Path tempDir;
    private ContextAssembler assembler;
    private SqliteNovelRepository repo;
    private SummaryService summaryService;
    private String pid;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("layered-trim.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        VectorRetrievalService vr = mock(VectorRetrievalService.class);
        KnowledgeBaseService kb = new KnowledgeBaseService(repo, vr);
        summaryService = new SummaryService(repo);
        // entityGraph/tracker 依赖可空或空实现——本测试只关心摘要组装与预算裁剪
        assembler = new ContextAssembler(repo, kb, summaryService, vr,
                null, new com.writing.novel.service.ClimaxDiversityTracker(), null, null, null);

        Project p = new Project("分层裁剪书", "少年剑修逆天改命", "玄幻");
        p.setBookSummary("全书主线：林逸自微末崛起，终成剑道至尊。");
        repo.saveProject(p);
        pid = p.getId();
    }

    /** 造数：50 卷卷级摘要（每卷 4 章，span>3 判为卷级）+ 最近 3 章细粒度摘要 */
    private void seedFiftyVolumesPlusFineGrained() {
        for (int i = 1; i <= 50; i++) {
            String content = "[V" + i + "]" + "卷级摘要正文".repeat(100) + "|END" + i;
            summaryService.save(new Summary(pid, (i - 1) * 4 + 1, i * 4, content));
        }
        summaryService.save(new Summary(pid, 197, 197, "[F197]近期细粒度摘要内容七"));
        summaryService.save(new Summary(pid, 198, 198, "[F198]近期细粒度摘要内容八"));
        summaryService.save(new Summary(pid, 199, 199, "[F199]近期细粒度摘要内容九"));
    }

    @Test
    void 预算受限时细粒度摘要保留而最老卷stub先被丢弃() {
        seedFiftyVolumesPlusFineGrained();

        // 小预算强制 applyTokenBudget 触发 recentSummary 整体截断（4000 token 上限档）
        WritingContext ctx = assembler.assemble(pid, 200, "第200章", "本章梗概", null, 4500);
        String rs = ctx.getRecentSummarySection();

        // 衔接最关键的近期细粒度摘要必须全部存活
        assertTrue(rs.contains("[F197]"), "最近第3章细粒度摘要被裁掉，违反分层裁剪（应最后才动细粒度）");
        assertTrue(rs.contains("[F198]"), "最近第2章细粒度摘要被裁掉");
        assertTrue(rs.contains("[F199]"), "最近1章细粒度摘要被裁掉——衔接信息丢失");

        // 最近 3 卷仍为全文（|END 标记在 300 字截断点之后，只有全文才含）
        assertTrue(rs.contains("|END50"), "最近卷应保留全文而非 stub");
        assertTrue(rs.contains("[V50]"));

        // 分层丢弃：最老卷 stub 先被丢
        assertFalse(rs.contains("[V1]"), "最老卷 stub 应最先被丢弃");
        assertFalse(rs.contains("[V5]"), "次老区域卷 stub 应先于细粒度被丢弃");
        // 次新的旧卷 stub（紧邻最近 3 卷）仍保留——证明是按从老到新分层丢，不是一刀切
        assertTrue(rs.contains("[V47]"), "紧邻详细区的旧卷 stub 不应在细粒度之前被丢");
    }

    @Test
    void 无预算压力时细粒度摘要位于更早卷stub之前() {
        seedFiftyVolumesPlusFineGrained();

        // 大预算不触发裁剪——直接断言组装顺序：细粒度在更早卷 stub 之前（受保护位）
        WritingContext ctx = assembler.assemble(pid, 200, "第200章", "本章梗概", null, 1_000_000);
        String rs = ctx.getRecentSummarySection();

        int fine = rs.indexOf("[F199]");
        int oldestStub = rs.indexOf("[V47]"); // 第一个旧卷 stub（最近 3 卷之后）
        assertTrue(fine >= 0, "细粒度摘要应存在");
        assertTrue(oldestStub >= 0, "旧卷 stub 应存在");
        assertTrue(fine < oldestStub,
                "细粒度摘要必须排在更早卷 stub 之前，否则整体尾部截断会先牺牲衔接信息");
        // 信息语义不变：最近 3 卷全文、更早卷截断为 stub
        assertTrue(rs.contains("|END50"), "最近卷应全文");
        assertFalse(rs.contains("|END47"), "更早卷应截断为 300 字 stub");
        assertTrue(rs.contains("（早期卷，已精简）"));
    }

    @Test
    void 超长premise预算受限时保头部截断并加标记() {
        ContextAssembler bare = new ContextAssembler(null, null, null, null, null, null, null, null, null);
        WritingContext ctx = new WritingContext();
        String head = "【核心原旨】主角林逸的复仇与救赎之旅，立意：凡人之躯可比肩神明。";
        // >6000 token（>9000 字）的超长总纲，模拟 50 卷逐卷累积
        String longPremise = head + "总纲冗余内容累积".repeat(1500);
        assertTrue(longPremise.length() > 9000, "测试前提：premise 超过 6000 token");
        ctx.setPremiseSection(longPremise);
        ctx.setRecentSummarySection("摘要".repeat(50));

        bare.applyTokenBudget(ctx, 4500);

        String trimmed = ctx.getPremiseSection();
        assertTrue(trimmed.length() < longPremise.length(), "超长 premise 应被截断");
        assertTrue(trimmed.startsWith(head), "截断必须保头部（原旨核心段在头部）");
        assertTrue(trimmed.contains("[总纲已截断]"), "截断后应带显式标记");
        assertTrue(trimmed.endsWith("……[总纲已截断]"), "截断标记应在尾部");
        // 上限：6000 token ≈ 9000 字 + 标记
        assertTrue(trimmed.length() <= 9000 + "……[总纲已截断]".length() + 1, "截断上限应为 6000 token");
    }

    @Test
    void 常规长度premise在紧预算下不被裁剪() {
        ContextAssembler bare = new ContextAssembler(null, null, null, null, null, null, null, null, null);
        WritingContext ctx = new WritingContext();
        String premise = "构思：少年剑修逆天改命\n总纲：三卷成尊，剑指九天。";
        ctx.setPremiseSection(premise);
        ctx.setRecentSummarySection("摘要".repeat(500)); // 超预算由 recentSummary 承担

        bare.applyTokenBudget(ctx, 200);

        assertEquals(premise, ctx.getPremiseSection(),
                "6000 token 以内的 premise 即使预算紧张也必须完整保留（【故事原旨】核心语义）");
    }
}
