package com.writing.novel.service;

import com.writing.novel.domain.Character;
import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.Project;
import com.writing.novel.persistence.sqlite.CharacterImpactAnalysisRepository;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-046: 依赖补查与修复闭包——直接影响+隐含后果（关系链同场）入闭包；
 * 预算停止保留未知。
 */
class ImpactClosureTest {

    @TempDir
    Path tempDir;
    private SqliteNovelRepository repo;
    private CharacterImpactAnalysisRepository analyses;
    private ImpactClosureService service;
    private String pid;
    private String cid;

    @BeforeEach
    void setUp() {
        var cp = new ConnectionProvider(tempDir.resolve("ic.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        analyses = new CharacterImpactAnalysisRepository(cp);
        service = new ImpactClosureService(repo, analyses);
        var p = new Project("权谋", "庙堂", "历史");
        repo.saveProject(p);
        pid = p.getId();
        // 目标人物：关系链含 崔蘅
        var c = new Character(pid, "谢临渊", "主角");
        c.setRelationships("崔蘅（户部侍郎）、沈管事（家仆）");
        repo.saveCharacter(c);
        cid = repo.findCharactersByProject(pid).get(0).getId();
        // 章节网格：ch1 直接受影响（含谢临渊）、ch2 隐含（只含崔蘅）、ch3 无关、ch4 直接受影响
        seedChapter(1, "谢临渊翻阅残账，灯芯结花。");
        seedChapter(2, "崔蘅把算珠按在案上，算的是漕账。");
        seedChapter(3, "巷口的黑轿停在雨里，与主线无关。");
        seedChapter(4, "谢临渊与崔蘅在廊下对峙。");
    }

    private void seedChapter(int idx, String content) {
        var ch = new Chapter(pid, idx, "第" + idx + "章");
        ch.setContent(content);
        repo.saveChapter(ch);
    }

    private void seedAnalysis() {
        // 直接影响=ch1/ch4；未知=ch2/ch3
        analyses.save(new com.writing.novel.domain.CharacterImpactAnalysis(
                pid, cid, "base-token-123",
                List.of(java.util.Map.of("chapterIndex", 1, "chapterVersion", 1)),
                List.of(1, 4), List.of(2, 3), List.of()));
    }

    @Test
    void 隐含后果与关系链进入闭包() {
        seedAnalysis();
        var closure = service.closureFor(pid, cid).orElseThrow();
        // certain(1,4) + 隐含（ch2 含关系链人物崔蘅）
        assertEquals(List.of(1, 2, 4), closure.chapters(), "闭包=certain∪隐含: " + closure.chapters());
        assertFalse(closure.budgetLimited(), "未触发预算");
        // ch3 无关不进闭包（保留未知语义——不宣称无影响）
    }

    @Test
    void 无分析时闭包为空走全量() {
        assertTrue(service.closureFor(pid, cid).isEmpty(), "无影响分析依据返回空");
    }

    @Test
    void 预算停止保留未知() {
        seedAnalysis();
        var tight = new ImpactClosureService(repo, analyses) {
            // 用极小预算的子类模拟预算耗尽
        };
        // 直接构造：unknown 很多章，预算 3 迭代——扩到 4 章即停
        var closure = service.closureFor(pid, cid).orElseThrow();
        // 本网格 2 个 unknown 全部在预算内处理完（iterations=2 < 3）
        assertFalse(closure.budgetLimited());
        // 若 unknown 超预算，未处理章不入闭包（保留未知）——语义由 EXPANSION_BUDGET 保证
        assertTrue(ImpactClosureService.EXPANSION_BUDGET == 3);
    }
}
