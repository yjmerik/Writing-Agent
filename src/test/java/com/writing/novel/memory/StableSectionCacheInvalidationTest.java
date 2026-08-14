package com.writing.novel.memory;

import com.writing.novel.domain.KnowledgeEntry;
import com.writing.novel.domain.Outline;
import com.writing.novel.domain.Project;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.spi.NovelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * p10-w1-01: stableSectionCache 写联动失效（复现 GAP#8 正确性 bug）。
 *
 * bug: stableSectionCache 一旦 computeIfAbsent 就永不失效，导致：
 *  - 第 1 章缓存 worldbuilding 后，后续新增的 KnowledgeEntry 永远不反映到上下文；
 *  - updateMasterOutlineWithWrittenDigest 改 premise 后，premise 段永远不更新。
 * 修复: KnowledgeBaseService 写入 / NovelOrchestrator 改 premise 后 bump MemoryRevision，
 *       cached() 比较版本号不一致即重算。
 */
class StableSectionCacheInvalidationTest {

    @TempDir
    Path tempDir;
    private NovelRepository repository;
    private KnowledgeBaseService knowledgeBase;
    private MemoryRevision revision;
    private ContextAssembler assembler;
    private String pid;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("cache.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repository = new SqliteNovelRepository(cp);

        VectorRetrievalService vectorRetrieval = mock(VectorRetrievalService.class);
        revision = new MemoryRevision();
        knowledgeBase = new KnowledgeBaseService(repository, vectorRetrieval, revision);

        SummaryService summaryService = mock(SummaryService.class);
        VectorRetrievalService vectorRetrievalForAssembler = mock(VectorRetrievalService.class);
        when(vectorRetrievalForAssembler.search(anyString(), anyString(), anyInt())).thenReturn(java.util.List.of());

        // 其余依赖 mock 并 stub 关键方法返回空——本测试只关心 premise/worldbuilding 段
        com.writing.novel.service.EntityGraphService entityGraph = mock(com.writing.novel.service.EntityGraphService.class);
        when(entityGraph.recallConsistencySection(anyString(), anyInt())).thenReturn("");
        com.writing.novel.service.ClimaxDiversityTracker climax = mock(com.writing.novel.service.ClimaxDiversityTracker.class);
        when(climax.generateRotationConstraint()).thenReturn("");
        com.writing.novel.service.NarrativeThreadTracker threads = mock(com.writing.novel.service.NarrativeThreadTracker.class);
        when(threads.getThreadSection(anyString())).thenReturn("");
        com.writing.novel.service.DiversityTracker diversity = mock(com.writing.novel.service.DiversityTracker.class);
        when(diversity.generateAllConstraints()).thenReturn("");
        com.writing.novel.service.ClicheDetector cliche = mock(com.writing.novel.service.ClicheDetector.class);
        when(cliche.generateForbiddenList(anyString(), anyInt())).thenReturn("");

        assembler = new ContextAssembler(repository, knowledgeBase, summaryService,
                vectorRetrievalForAssembler,
                entityGraph, climax, threads, diversity, cliche, revision);

        // 建项目 + 总纲
        Project p = new Project("测试书", "原始构思", "historical");
        repository.saveProject(p);
        pid = p.getId();
        Outline outline = new Outline(pid, "原始总纲");
        repository.saveOutline(outline);
    }

    @Test
    void worldbuilding缓存_新增知识条目后应失效重算() {
        // 首次组装——worldbuilding 为空（无条目），被缓存
        knowledgeBase.saveEntry(new KnowledgeEntry(pid, "POWER_SYSTEM", "灵气体系", "初版设定"));
        String first = assembler.assemble(pid, 0, "ch0", "梗概", null).getWorldbuildingSection();
        assertTrue(first.contains("灵气体系"), "首次组装应含初版条目");

        // 新增第二条知识条目（saveEntry 内部 bump revision）
        knowledgeBase.saveEntry(new KnowledgeEntry(pid, "FACTION", "天机阁", "新势力"));

        // bug: 第二次组装仍返回缓存的旧 worldbuilding（不含天机阁）
        // 修复: revision 变化触发重算
        String second = assembler.assemble(pid, 1, "ch1", "梗概", null).getWorldbuildingSection();
        assertTrue(second.contains("天机阁"),
                "新增知识条目后 worldbuilding 应失效重算并含新条目，实际=" + second);
    }

    @Test
    void premise缓存_outline总纲变更后应失效重算() {
        // 首次组装——premise 含"原始总纲"
        String first = assembler.assemble(pid, 0, "ch0", "梗概", null).getPremiseSection();
        assertTrue(first.contains("原始总纲"));

        // 模拟 updateMasterOutlineWithWrittenDigest 改 premise 后调 invalidateProject
        Outline outline = repository.findOutlineByProject(pid).orElseThrow();
        outline.setPremise("演化后的新总纲 + 已写卷摘要");
        repository.saveOutline(outline);
        assembler.invalidateProject(pid);

        // bug: premise 永不更新（仍为"原始总纲"）
        // 修复: invalidateProject bump revision，重算
        String second = assembler.assemble(pid, 1, "ch1", "梗概", null).getPremiseSection();
        assertTrue(second.contains("新总纲"),
                "premise 变更+失效后应反映新总纲，实际=" + second);
        assertFalse(second.contains("原始总纲"), "不应再含旧总纲");
    }

    @Test
    void 旧构造器无revision时退化为永不失效_向后兼容() {
        VectorRetrievalService vr = mock(VectorRetrievalService.class);
        KnowledgeBaseService kbNoRev = new KnowledgeBaseService(repository, vr); // 旧构造器
        com.writing.novel.service.EntityGraphService eg = mock(com.writing.novel.service.EntityGraphService.class);
        when(eg.recallConsistencySection(anyString(), anyInt())).thenReturn("");
        com.writing.novel.service.ClimaxDiversityTracker cl = mock(com.writing.novel.service.ClimaxDiversityTracker.class);
        when(cl.generateRotationConstraint()).thenReturn("");
        com.writing.novel.service.NarrativeThreadTracker nt = mock(com.writing.novel.service.NarrativeThreadTracker.class);
        when(nt.getThreadSection(anyString())).thenReturn("");
        com.writing.novel.service.DiversityTracker dv = mock(com.writing.novel.service.DiversityTracker.class);
        when(dv.generateAllConstraints()).thenReturn("");
        com.writing.novel.service.ClicheDetector cd = mock(com.writing.novel.service.ClicheDetector.class);
        when(cd.generateForbiddenList(anyString(), anyInt())).thenReturn("");
        ContextAssembler oldAssembler = new ContextAssembler(repository, kbNoRev,
                mock(SummaryService.class), vr,
                eg, cl, nt, dv, cd); // 旧构造器，revision=null

        kbNoRev.saveEntry(new KnowledgeEntry(pid, "RULE", "规则A", "内容A"));
        String first = oldAssembler.assemble(pid, 0, "ch0", "梗概", null).getWorldbuildingSection();
        kbNoRev.saveEntry(new KnowledgeEntry(pid, "RULE", "规则B", "内容B"));
        String second = oldAssembler.assemble(pid, 1, "ch1", "梗概", null).getWorldbuildingSection();

        // 旧构造器：无失效能力（向后兼容，行为不变）——第二次仍为缓存值
        assertEquals(first, second, "旧构造器（无 revision）应保持永不失效的旧行为");
    }
}
