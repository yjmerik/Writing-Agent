package com.writing.novel.orchestrator;

import com.writing.novel.agent.ConsistencyGuard;
import com.writing.novel.domain.ContinuityIssue;
import com.writing.novel.domain.Project;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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

    /** NCS-194: prepare 把场景规划渲染进写作任务（每场景字数下限/转场规格/标记指令） */
    @Test
    void prepare注入场景预算与分隔标记指令() {
        Project p = seedProject();
        var outline = repo.findOutlineByProject(p.getId()).orElseThrow();
        var synopsis = synopsisOf(outline, 1);
        synopsis.setScenePlan("""
                {"scenes":[
                  {"time":"子夜","location":"账房","characters":["沈砚"],"charBudget":1200,
                   "transitionType":"opening","transitionMinChars":0,"textureHint":"油灯","causalConstraint":"异动被发现"},
                  {"time":"拂晓","location":"柜坊","characters":["沈砚","掌柜"],"charBudget":1600,
                   "transitionType":"time","transitionMinChars":80,"textureHint":"开门声","causalConstraint":"掌柜否认"}
                ],"totalBudget":2800}""");
        repo.saveOutline(outline);

        var prod = production();
        prod.setScenePlanService(new com.writing.novel.service.ScenePlanService(repo, null, null) {
            @Override
            public String ensurePlanned(String projectId, int chapterIndex) {
                return synopsis.getScenePlan(); // 已有规划：幂等返回，不再调用 LLM
            }
        });
        String notes = prod.prepare(p.getId(), 1, synopsis, null);
        assertTrue(notes.contains("【场景规划"), "prepare 应注入场景规划段: " + notes);
        assertTrue(notes.contains("下限 900 字"), "每场景字数下限应渲染（1200*3/4）");
        assertTrue(notes.contains("与上场景过渡≥80 字"), "转场规格应渲染");
        assertTrue(notes.contains("<<<场景>>>"), "分隔标记指令应渲染");
        assertTrue(notes.contains("合计目标 2800 字"), "合计预算应渲染");
    }

    /** NCS-187: 写章路径发出 TEXTURE_METRICS 遥测事件（observe-only，不阻断不重写） */
    @Test
    void 写章后发出纹理指标遥测事件() {
        Project p = seedProject();
        var outline = repo.findOutlineByProject(p.getId()).orElseThrow();
        var synopsis = synopsisOf(outline, 1);

        java.util.List<Map<String, Object>> textureEvents = new java.util.ArrayList<>();
        com.writing.novel.telemetry.EventLog.Listener capture = record -> {
            if ("TEXTURE_METRICS".equals(record.get("event"))) textureEvents.add(record);
        };
        com.writing.novel.telemetry.EventLog.subscribe(capture);
        try {
            production().produce(p.getId(), 1, synopsis, null);
        } finally {
            com.writing.novel.telemetry.EventLog.unsubscribe(capture);
        }
        assertEquals(1, textureEvents.size(), "produce 应恰好发出一条 TEXTURE_METRICS 事件");
        Map<String, Object> ev = textureEvents.get(0);
        assertEquals(1, ev.get("chapterIndex"));
        assertNotNull(ev.get("wordCount"));
        for (String field : java.util.List.of("dialogueParagraphRatio", "narrationRunMaxChars",
                "sceneBreaks", "dialoguePingPong", "totalParagraphs", "avgParagraphLength")) {
            assertTrue(ev.containsKey(field), "TEXTURE_METRICS 应含字段 " + field);
        }
    }
}
