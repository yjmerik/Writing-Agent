package com.writing.novel.service;

import com.writing.novel.agent.ScenePlanAgent;
import com.writing.novel.domain.ChapterSynopsis;
import com.writing.novel.domain.NarrativePolicy;
import com.writing.novel.spi.NovelRepository;
import com.writing.novel.telemetry.EventLog;

import java.util.Map;

/**
 * NCS-193: 写前场景规划服务——把 ScenePlanAgent 的产出落到章梗概（scenePlan 字段）。
 *
 * <p>本 feature 只产出与持久化；写作侧消费（场景预算注入 writingContext、分隔标记）
 * 归 NCS-194。幂等：梗概已带 scenePlan 时跳过（不重复调用 LLM）。
 * 任何失败静默降级（返回 null，不阻断写作）——规划是增强能力。
 */
public class ScenePlanService {

    private final NovelRepository repository;
    private final ScenePlanAgent agent;
    private final NarrativePolicyService policyService;

    private static final int PREVIOUS_TAIL_CHARS = 500;

    public ScenePlanService(NovelRepository repository, ScenePlanAgent agent,
                            NarrativePolicyService policyService) {
        this.repository = repository;
        this.agent = agent;
        this.policyService = policyService;
    }

    /**
     * 确保该章已有场景规划；无则生成并落梗概。
     *
     * @return 场景规划 JSON；已有/失败/坏输出返回既有值或 null
     */
    public String ensurePlanned(String projectId, int chapterIndex) {
        var outlineOpt = repository.findOutlineByProject(projectId);
        if (outlineOpt.isEmpty()) return null;
        var outline = outlineOpt.get();
        ChapterSynopsis synopsis = outline.getVolumes().stream()
                .flatMap(v -> v.getSynopses().stream())
                .filter(s -> s.getChapterIndex() == chapterIndex)
                .findFirst().orElse(null);
        if (synopsis == null) return null;
        if (synopsis.getScenePlan() != null && !synopsis.getScenePlan().isBlank()) {
            return synopsis.getScenePlan(); // 幂等：已有规划不重算
        }

        String previousTail = repository.findChapterByIndex(projectId, chapterIndex - 1)
                .map(c -> tail(c.getContent()))
                .orElse("");
        String policyNotes = policyNotesOf(projectId);
        int targetMin = repository.findWritingPreferences(projectId)
                .map(p -> Math.max(1000, p.getMinChapterLength())).orElse(2000);
        int targetMax = repository.findWritingPreferences(projectId)
                .map(p -> Math.max(targetMin, p.getMaxChapterLength())).orElse(4500);

        var plan = agent.generateScenePlan(synopsis.getTitle(), synopsis.getSynopsis(),
                synopsis.getBeats(), previousTail, policyNotes, targetMin, targetMax);
        if (plan == null) {
            EventLog.emit("SCENE_PLAN_UNAVAILABLE", Map.of("chapterIndex", chapterIndex));
            return null;
        }
        synopsis.setScenePlan(plan.toString());
        repository.saveOutline(outline);
        EventLog.emit("SCENE_PLAN_READY", Map.of(
                "chapterIndex", chapterIndex,
                "scenes", plan.path("scenes").size(),
                "totalBudget", plan.path("totalBudget").asInt()));
        return plan.toString();
    }

    /** 质感题材才注入策略提示；宽松题材空串（现状等效，不约束） */
    private String policyNotesOf(String projectId) {
        try {
            NarrativePolicy policy = policyService.resolve(projectId);
            if (policy == null || policy.getDialogueParagraphRatioMax() >= 1.0) return "";
            return "每场景感官锚点不少于 " + policy.getSensoryAnchorPerScene() + " 处；"
                    + "场景间过渡不少于 " + policy.getTransitionMinChars() + " 字；"
                    + "开篇定场不超过 " + policy.getOpeningSettingAllowance() + " 字。";
        } catch (Exception e) {
            return "";
        }
    }

    private String tail(String content) {
        if (content == null || content.length() <= PREVIOUS_TAIL_CHARS) return content == null ? "" : content;
        return content.substring(content.length() - PREVIOUS_TAIL_CHARS);
    }

    /**
     * NCS-194: 把场景规划 JSON 渲染为写作任务注入段——每场景字数下限/转场规格/
     * 质感提示 + 场景分隔标记指令。坏输入返回空串（调用方零注入）。
     */
    public String renderWritingGuide(String scenePlanJson) {
        if (scenePlanJson == null || scenePlanJson.isBlank()) return "";
        var parsed = com.writing.novel.llm.LlmJson.parse(scenePlanJson);
        if (!parsed.ok() || !parsed.node().isObject()) return "";
        var scenes = parsed.node().path("scenes");
        if (!scenes.isArray() || scenes.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("【场景规划（按场景顺序写作；每个场景写完后另起一行插入分隔标记 <<<场景>>>，最后一个场景后不加）】");
        int index = 0;
        for (var s : scenes) {
            index++;
            int budget = s.path("charBudget").asInt(0);
            if (budget <= 0) return "";
            int floor = Math.max(200, budget * 3 / 4);
            sb.append("\n场景").append(index).append("（目标约 ").append(budget)
                    .append(" 字，下限 ").append(floor).append(" 字）");
            String transition = s.path("transitionType").asText("");
            int transitionMin = s.path("transitionMinChars").asInt(0);
            if (!"opening".equals(transition) && transitionMin > 0) {
                sb.append("｜与上场景过渡≥").append(transitionMin).append(" 字");
            }
            String time = s.path("time").asText("");
            String location = s.path("location").asText("");
            if (!time.isBlank() || !location.isBlank()) {
                sb.append("｜").append(time).append(time.isBlank() || location.isBlank() ? "" : "·").append(location);
            }
            var characters = s.path("characters");
            if (characters.isArray() && !characters.isEmpty()) {
                StringBuilder names = new StringBuilder();
                for (var c : characters) {
                    if (names.length() > 0) names.append("、");
                    names.append(c.asText());
                }
                sb.append("｜人物：").append(names);
            }
            String hint = s.path("textureHint").asText("");
            if (!hint.isBlank()) sb.append("｜质感：").append(hint);
            String causal = s.path("causalConstraint").asText("");
            if (!causal.isBlank()) sb.append("｜必须成立：").append(causal);
        }
        int total = parsed.node().path("totalBudget").asInt(0);
        if (total > 0) sb.append("\n全部场景合计目标 ").append(total).append(" 字。");
        return sb.toString();
    }
}
