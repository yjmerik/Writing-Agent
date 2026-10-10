package com.writing.novel.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.writing.novel.domain.ChapterSynopsis;
import com.writing.novel.domain.Outline;
import com.writing.novel.domain.Volume;
import com.writing.novel.domain.WritingPreferences;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.LlmTaskType;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.NarrativePolicyRepository;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.service.NarrativePolicyService;
import com.writing.novel.service.ScenePlanService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-193: ScenePlanAgent 场景规划层——写前把章梗概拆成 2-4 个带字数预算/
 * 转场规格/质感提示/因果约束的场景；预算合计确定性归一到本章字数区间。
 * 仅产出并落梗概（scenePlan 字段），不改写作消费（归 NCS-194）。
 */
class ScenePlanAgentTest {

    private static final String THREE_SCENES = """
            {"scenes":[
              {"index":1,"time":"子夜","location":"账房","characters":["沈砚","郑三斤"],
               "charBudget":900,"transitionType":"opening","transitionMinChars":0,
               "textureHint":"油灯、算珠声","causalConstraint":"账册第三页异动被发现"},
              {"index":2,"time":"子夜过后","location":"后巷","characters":["沈砚"],
               "charBudget":700,"transitionType":"space","transitionMinChars":80,
               "textureHint":"雨、青石反光","causalConstraint":"追不上送信人"},
              {"index":3,"time":"拂晓","location":"柜坊","characters":["沈砚","掌柜"],
               "charBudget":800,"transitionType":"time","transitionMinChars":80,
               "textureHint":"开门伙计的哈欠","causalConstraint":"掌柜否认第三页"}
            ]}""";

    private ScenePlanAgent agent(String reply) {
        return new ScenePlanAgent(new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest req) {
                assertEquals(LlmTaskType.GENERAL, req.getTaskType(), "场景规划走 GENERAL 小输出路由");
                return new LlmResponse(reply, 100, 50);
            }
            @Override public void streamComplete(LlmRequest req, StreamHandler handler) {}
        }, TestPromptHelper.create());
    }

    @Test
    void 给定梗概解析出2到4个场景且字段齐全() {
        JsonNode plan = agent(THREE_SCENES).generateScenePlan(
                "第一章 残账", "学徒发现账册异动", "发现异动→追出后巷→柜坊对质", "", "", 2000, 4500);
        assertNotNull(plan, "合法输出应解析为场景规划");
        var scenes = plan.path("scenes");
        assertTrue(scenes.isArray() && scenes.size() >= 2 && scenes.size() <= 4, "场景数应为 2-4");
        assertEquals("opening", scenes.get(0).path("transitionType").asText(), "首场景转场类型为 opening");
        for (JsonNode s : scenes) {
            assertFalse(s.path("time").asText().isBlank(), "时间字段齐全");
            assertFalse(s.path("location").asText().isBlank(), "地点字段齐全");
            assertTrue(s.path("characters").isArray(), "出场人物字段齐全");
            assertTrue(s.path("charBudget").asInt() > 0, "字数预算为正");
            assertFalse(s.path("transitionType").asText().isBlank(), "转场方式齐全");
            assertTrue(s.path("transitionMinChars").asInt() >= 0, "转场最低字数齐全");
            assertFalse(s.path("causalConstraint").asText().isBlank(), "因果约束齐全（吸收 scene-contract）");
        }
    }

    @Test
    void 预算合计低于字数区间下限时按比例放大到下限() {
        String raw = """
            {"scenes":[
              {"time":"夜","location":"账房","characters":["沈砚"],"charBudget":500,
               "transitionType":"opening","transitionMinChars":0,"textureHint":"t","causalConstraint":"c"},
              {"time":"深夜","location":"后巷","characters":["沈砚"],"charBudget":500,
               "transitionType":"space","transitionMinChars":80,"textureHint":"t","causalConstraint":"c"}
            ]}""";
        JsonNode plan = agent(raw).generateScenePlan("t", "s", "b", "", "", 2500, 4500);
        assertNotNull(plan);
        int sum = 0;
        for (JsonNode s : plan.path("scenes")) sum += s.path("charBudget").asInt();
        assertTrue(sum >= 2500 && sum <= 4500, "预算合计应落在本章字数区间，实际 " + sum);
    }

    @Test
    void 预算合计超上限时按比例收缩到上限() {
        String raw = """
            {"scenes":[
              {"time":"夜","location":"账房","characters":["沈砚"],"charBudget":4000,
               "transitionType":"opening","transitionMinChars":0,"textureHint":"t","causalConstraint":"c"},
              {"time":"深夜","location":"后巷","characters":["沈砚"],"charBudget":5000,
               "transitionType":"space","transitionMinChars":80,"textureHint":"t","causalConstraint":"c"}
            ]}""";
        JsonNode plan = agent(raw).generateScenePlan("t", "s", "b", "", "", 2500, 4500);
        assertNotNull(plan);
        int sum = 0;
        for (JsonNode s : plan.path("scenes")) sum += s.path("charBudget").asInt();
        assertTrue(sum >= 2500 && sum <= 4500, "预算合计应收缩进区间，实际 " + sum);
    }

    @Test
    void 场景数越界或坏输出返回null安全降级() {
        String oneScene = """
            {"scenes":[{"time":"夜","location":"账房","characters":["沈砚"],"charBudget":3000,
             "transitionType":"opening","transitionMinChars":0,"textureHint":"t","causalConstraint":"c"}]}""";
        assertNull(agent(oneScene).generateScenePlan("t", "s", "b", "", "", 2000, 4500), "1 个场景不合格");
        assertNull(agent(" completely unrelated prose ").generateScenePlan("t", "s", "b", "", "", 2000, 4500), "无 JSON 坏输出");
        assertNull(agent("").generateScenePlan("t", "s", "b", "", "", 2000, 4500), "空输出");
    }

    @Test
    void 写前规划落梗概且幂等(@TempDir Path tempDir) {
        var cp = new ConnectionProvider(tempDir.resolve("sp.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        var repo = new SqliteNovelRepository(cp);
        var p = new com.writing.novel.domain.Project("悬疑", "旧书店", "悬疑,古代");
        p.setGenre(com.writing.novel.domain.GenreProfile.MYSTERY);
        repo.saveProject(p);
        repo.saveWritingPreferences(new WritingPreferences(p.getId()));
        Outline outline = new Outline(p.getId(), "总纲");
        Volume v = new Volume(p.getId(), 1, "卷一", "");
        ChapterSynopsis s = new ChapterSynopsis(p.getId(), 1, "第一章 残账", "学徒发现账册异动");
        s.setBeats("发现异动→追出后巷→柜坊对质");
        v.addSynopsis(s);
        outline.addVolume(v);
        repo.saveOutline(outline);

        ScenePlanService service = new ScenePlanService(repo, agent(THREE_SCENES),
                new NarrativePolicyService(repo, new NarrativePolicyRepository(cp)));
        String plan = service.ensurePlanned(p.getId(), 1);
        assertNotNull(plan, "规划成功返回 JSON");

        // 落库验证
        Outline reloaded = repo.findOutlineByProject(p.getId()).orElseThrow();
        var stored = reloaded.getVolumes().stream().flatMap(vv -> vv.getSynopses().stream())
                .filter(x -> x.getChapterIndex() == 1).findFirst().orElseThrow();
        assertEquals(plan, stored.getScenePlan(), "scenePlan 持久化到梗概");

        // 幂等：已有 scenePlan 不再调用（gateway 换成计数爆表即失败）
        ScenePlanService again = new ScenePlanService(repo, agent("should not be called"),
                new NarrativePolicyService(repo, new NarrativePolicyRepository(cp)));
        assertEquals(plan, again.ensurePlanned(p.getId(), 1), "幂等返回既有规划");
    }
}
