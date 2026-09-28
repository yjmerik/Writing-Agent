package com.writing.novel.orchestrator;

import com.writing.novel.agent.ConsistencyGuard;
import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.ContinuityIssue;
import com.writing.novel.domain.StoryFact;
import com.writing.novel.memory.RelevanceGateway;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.persistence.sqlite.SqliteStoryCanon;
import com.writing.novel.persistence.sqlite.StoryFactRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-098: 矛盾仲裁闭环激活——
 * 1) 真典极性冲突对裁决前不注入（RelevanceGateway 过滤）；
 * 2) HIGH 问题三分类路由：WORLDBUILDING→真典演进（不重写不暂停，事件留痕）、
 *    TIMELINE→直通人工（不浪费重写预算）、其余→既有重写链。
 */
class RepairArbiterClosedLoopTest {

    // --- 1) 冲突对过滤（单元） ---

    @TempDir
    Path tempDir;

    @Test
    void 极性冲突对_裁决前双方都不注入() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("conflict.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        StoryFactRepository factRepo = new StoryFactRepository(cp);
        SqliteStoryCanon canon = new SqliteStoryCanon(factRepo);
        var repository = new SqliteNovelRepository(cp);
        RelevanceGateway gateway = new RelevanceGateway(repository, canon);

        StoryFact yes = new StoryFact("p1", StoryFact.FactType.CHARACTER_STATE, "林凡", "左手残废", 1);
        yes.setPredicate("左手状态");
        yes.setPolarity("肯定");
        factRepo.save(yes);
        StoryFact no = new StoryFact("p1", StoryFact.FactType.CHARACTER_STATE, "林凡", "左手痊愈", 2);
        no.setPredicate("左手状态");
        no.setPolarity("否定");
        factRepo.save(no);
        StoryFact other = new StoryFact("p1", StoryFact.FactType.WORLD_RULE, "灵气体系", "九重境界", 1);
        other.setPredicate("境界数");
        other.setPolarity("肯定");
        factRepo.save(other);

        var out = gateway.relevantFacts("p1", List.of(), 5, 40);
        assertTrue(out.stream().noneMatch(f -> f.getPredicate() != null && f.getPredicate().equals("左手状态")),
                "冲突对双方都不应注入");
        assertTrue(out.stream().anyMatch(f -> "九重境界".equals(f.getAssertion())),
                "无冲突事实正常注入");

        // 静态口径单测：同谓词相反极性 → 双双排除；无谓词旧数据不受影响
        var legacy = new StoryFact("p1", StoryFact.FactType.WORLD_RULE, "旧数据", "无谓词断言", 1);
        assertEquals(1, RelevanceGateway.withoutConflictPairs(List.of(legacy)).size());
    }

    // --- 2) 三分类路由（单元） ---

    @Test
    void 三分类路由_世界观演进_时间线人工_其余重写() {
        var world = new ContinuityIssue("p", 1, ContinuityIssue.Type.WORLDBUILDING,
                ContinuityIssue.Severity.HIGH, "境界体系矛盾");
        var timeline = new ContinuityIssue("p", 1, ContinuityIssue.Type.TIMELINE,
                ContinuityIssue.Severity.HIGH, "年龄倒退");
        var character = new ContinuityIssue("p", 1, ContinuityIssue.Type.CHARACTER,
                ContinuityIssue.Severity.HIGH, "能力越界");
        var low = new ContinuityIssue("p", 1, ContinuityIssue.Type.CHARACTER,
                ContinuityIssue.Severity.MEDIUM, "次要问题");

        var partition = com.writing.novel.orchestrator.ChapterProduction.partitionByRepairAction(
                List.of(world, timeline, character, low), false);

        assertEquals(List.of(world), partition.canonEvolution(), "WORLDBUILDING HIGH → 真典演进");
        assertEquals(List.of(timeline), partition.manualReview(), "TIMELINE HIGH → 人工");
        assertTrue(partition.rewrite().contains(character) && partition.rewrite().contains(low),
                "CHARACTER 与非 HIGH → 既有重写链");
    }

    // --- 3) 写作链路集成：三类出口（复用编排器测试 harness） ---

    static class WorldEvolutionGuard extends ConsistencyGuard {
        WorldEvolutionGuard() { super(null, null, null); }
        @Override
        public List<ContinuityIssue> check(String projectId, int chapterIndex, String chapterContent, List<String> cast) {
            return List.of(new ContinuityIssue(projectId, chapterIndex,
                    ContinuityIssue.Type.WORLDBUILDING, ContinuityIssue.Severity.HIGH,
                    "测试注入：世界观体系拓展（合法演进）"));
        }
    }

    /** 手工驱动 harness（同包注入 tempDir 后 setUp），返回已就绪的编排器环境 */
    private ManualReviewLifecycleTestHarnessSupport harness(ConsistencyGuard guard, String subdir) {
        var support = new ManualReviewLifecycleTestHarnessSupport() {
            @Override protected ConsistencyGuard guardOverride() { return guard; }
        };
        try {
            java.nio.file.Files.createDirectories(tempDir.resolve(subdir));
        } catch (java.io.IOException e) { throw new RuntimeException(e); }
        support.tempDir = tempDir.resolve(subdir);
        support.setUp();
        return support;
    }

    /** WORLDBUILDING HIGH → 真典演进出口：不暂停、不重写、issue 结清 */
    @Test
    void 世界观高危_走真典演进出口_不重写不暂停() {
        var support = harness(new WorldEvolutionGuard(), "world");
        var pid = support.seedProject().getId();
        Chapter ch = support.orchestrator().runWritingOneChapter(pid);
        assertNotNull(ch);
        assertNotEquals(Chapter.Status.NEEDS_MANUAL_REVIEW, ch.getStatus(),
                "世界观合法演进不得把章节打成待人工");
        // 基线：首写路径 setContent 一次 → v2；发生额外重写会到 v3
        assertEquals(2, ch.getVersion(), "真典演进路径不消耗重写");
        var resolved = support.repo().findUnresolvedIssues(pid).stream()
                .filter(i -> i.getDescription().contains("世界观体系拓展")).toList();
        assertTrue(resolved.isEmpty(), "演进 issue 应已结清");
    }

    static class TimelineGuard extends ConsistencyGuard {
        TimelineGuard() { super(null, null, null); }
        @Override
        public List<ContinuityIssue> check(String projectId, int chapterIndex, String chapterContent, List<String> cast) {
            return List.of(new ContinuityIssue(projectId, chapterIndex,
                    ContinuityIssue.Type.TIMELINE, ContinuityIssue.Severity.HIGH,
                    "测试注入：跨章年龄倒退"));
        }
    }

    /** TIMELINE HIGH → 人工出口：直接暂停且不先烧一次重写 */
    @Test
    void 时间线高危_直通人工_不浪费重写预算() {
        var support = harness(new TimelineGuard(), "timeline");
        var pid = support.seedProject().getId();
        Chapter ch = support.orchestrator().runWritingOneChapter(pid);
        assertEquals(Chapter.Status.NEEDS_MANUAL_REVIEW, ch.getStatus(), "跨章矛盾应直通人工");
        // 首写 setContent → v2；旧路径会先重写一版到 v3，新路径不消耗
        assertEquals(2, ch.getVersion(), "直通人工不得先消耗重写");
    }
}
