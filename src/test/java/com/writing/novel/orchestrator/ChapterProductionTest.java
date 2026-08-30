package com.writing.novel.orchestrator;

import com.writing.novel.agent.ConsistencyGuard;
import com.writing.novel.domain.ContinuityIssue;
import com.writing.novel.domain.Project;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * p22-04: 章节生产模块——Context→Write→Quality→Rewrite 可经小结果接口独立调用。
 *
 * - produce() 一次驱动四阶段并返回 ProductionResult（章节 + 是否触发重写）
 * - produce() 不做任何项目状态转移（索引不推进、章节状态不改）——状态机归 Orchestrator
 * - prepare() 注入作者指令与写前约束
 */
class ChapterProductionTest extends ManualReviewLifecycleTestHarnessSupport {

    /** 恒定返回 HIGH 矛盾的守卫——驱动质量链进入 Rewrite 阶段 */
    static class AlwaysHighGuard extends ConsistencyGuard {
        AlwaysHighGuard() { super(null, null, null); }
        @Override
        public List<ContinuityIssue> check(String projectId, int chapterIndex,
                                           String chapterContent, List<String> cast) {
            return List.of(new ContinuityIssue(projectId, chapterIndex,
                    ContinuityIssue.Type.CHARACTER, ContinuityIssue.Severity.HIGH,
                    "生产模块测试：能力越界"));
        }
    }

    @Override
    protected ConsistencyGuard guardOverride() {
        return new AlwaysHighGuard();
    }

    @Test
    void produce一次驱动四阶段并返回结果() {
        Project p = seedProject();
        var outline = repo.findOutlineByProject(p.getId()).orElseThrow();
        var synopsis = synopsisOf(outline, 1);
        assertNotNull(synopsis, "第1章应有梗概");

        int indexBefore = repo.findProject(p.getId()).orElseThrow().getCurrentChapterIndex();

        ChapterProduction.ProductionResult result =
                production().produce(p.getId(), 1, synopsis, "本章聚焦诀别");
        assertNotNull(result.chapter(), "produce 应产出章节");
        assertTrue(result.chapter().getWordCount() > 0);
        assertTrue(result.rewritten(), "AlwaysHighGuard 注入 HIGH 矛盾应触发 Rewrite 阶段");

        // 小结果接口不持有状态机——produce 不推进索引、不改章节状态
        assertEquals(indexBefore, repo.findProject(p.getId()).orElseThrow().getCurrentChapterIndex(),
                "produce 不得推进项目索引（状态转移归 Orchestrator）");
    }

    @Test
    void prepare注入作者指令与写前约束() {
        // prepare 只做装配，与守卫策略无关——复用类 harness
        Project p = seedProject();
        var outline = repo.findOutlineByProject(p.getId()).orElseThrow();
        var synopsis = synopsisOf(outline, 1);

        String notes = production().prepare(p.getId(), 1, synopsis, "重点写雨夜对峙");
        assertNotNull(notes);
        assertTrue(notes.contains("【作者指令】"), "prepare 应前置作者指令段");
        assertTrue(notes.contains("雨夜对峙"));
    }
}
