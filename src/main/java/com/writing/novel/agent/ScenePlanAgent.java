package com.writing.novel.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.LlmTaskType;
import com.writing.novel.llm.prompt.PromptTemplateService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * NCS-193: 场景规划 Agent——写前把章梗概拆为 2-4 个带字数预算/转场规格/
 * 质感提示/因果约束（吸收 scene-contract）的场景。GENERAL 路由小输出；
 * 坏输出返回 null 安全降级（写作管线不依赖规划存在，NCS-194 起消费）。
 *
 * <p>预算算术是确定性的：LLM 给出的各场景字数预算合计超出本章字数区间时，
 * 按比例缩放到最近的区间端点（四舍五入余数记到最大场景）——保证
 * 「预算合计落在本章字数区间」不依赖模型算术。
 */
public class ScenePlanAgent {

    private final LlmGateway gateway;
    private final PromptTemplateService promptService;

    public ScenePlanAgent(LlmGateway gateway, PromptTemplateService promptService) {
        this.gateway = gateway;
        this.promptService = promptService;
    }

    /**
     * 生成场景规划 JSON；解析失败/场景数越界返回 null。
     *
     * @param previousTail 上一章结尾文本（衔接参考，可空）
     * @param policyNotes  NarrativePolicy 质感要求文本（可空=宽松题材）
     * @param targetMinChars/maxChars 本章字数目标区间（预算归一基准）
     */
    public JsonNode generateScenePlan(String chapterTitle, String synopsis, String beats,
                                      String previousTail, String policyNotes,
                                      int targetMinChars, int targetMaxChars) {
        String systemPrompt = promptService.render("scene-plan", Map.of(
                "chapterTitle", chapterTitle == null ? "" : chapterTitle,
                "synopsis", synopsis == null ? "" : synopsis,
                "beats", beats == null ? "" : beats,
                "previousTail", previousTail == null ? "" : previousTail,
                "policyNotes", policyNotes == null ? "" : policyNotes,
                "targetMinChars", String.valueOf(targetMinChars),
                "targetMaxChars", String.valueOf(targetMaxChars)));
        var req = LlmRequest.builder()
                .taskType(LlmTaskType.GENERAL)
                .systemPrompt(systemPrompt)
                .userMessage("请为该章生成场景规划 JSON。")
                .temperature(0.3)
                .maxTokens(2000)
                .build();
        LlmResponse resp;
        try {
            resp = gateway.complete(req);
        } catch (Exception e) {
            return null; // 规划是增强能力，调用失败不阻断写作
        }
        String raw = resp.getContent();
        if (raw == null || raw.isBlank()) return null;
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        var parsed = com.writing.novel.llm.LlmJson.parse(raw.substring(start, end + 1));
        if (!parsed.ok() || !parsed.node().isObject()) return null;
        return normalizeScenes(parsed.node(), targetMinChars, targetMaxChars);
    }

    /** 规范化场景数组：2-4 个、字段齐全、index 重排、预算合计归一到目标区间；不合格返回 null */
    JsonNode normalizeScenes(JsonNode root, int targetMinChars, int targetMaxChars) {
        JsonNode rawScenes = root.path("scenes");
        if (!rawScenes.isArray() || rawScenes.size() < 2 || rawScenes.size() > 4) return null;

        List<ObjectNode> scenes = new ArrayList<>();
        List<Integer> budgets = new ArrayList<>();
        for (JsonNode s : rawScenes) {
            if (!s.isObject()) return null;
            int budget = s.path("charBudget").asInt(0);
            if (budget <= 0) return null;
            ObjectNode out = com.writing.novel.llm.LlmJson.mapper().createObjectNode();
            out.put("time", s.path("time").asText(""));
            out.put("location", s.path("location").asText(""));
            ArrayNode chars = out.putArray("characters");
            for (JsonNode c : s.path("characters")) {
                if (c.asText("").isBlank()) return null;
                chars.add(c.asText());
            }
            out.put("charBudget", budget);
            String transition = s.path("transitionType").asText(
                    scenes.isEmpty() ? "opening" : "space");
            out.put("transitionType", transition);
            out.put("transitionMinChars", Math.max(0, s.path("transitionMinChars").asInt(0)));
            out.put("textureHint", s.path("textureHint").asText(""));
            out.put("causalConstraint", s.path("causalConstraint").asText(""));
            scenes.add(out);
            budgets.add(budget);
        }

        // 预算归一：合计超出 [min,max] 时按比例缩放到最近端点；余数记给最大场景
        int sum = budgets.stream().mapToInt(Integer::intValue).sum();
        int target = sum;
        if (targetMinChars > 0 && sum < targetMinChars) target = targetMinChars;
        if (targetMaxChars > 0 && sum > targetMaxChars) target = targetMaxChars;
        if (target != sum && sum > 0) {
            int largest = 0;
            for (int i = 1; i < budgets.size(); i++) if (budgets.get(i) > budgets.get(largest)) largest = i;
            int allocated = 0;
            for (int i = 0; i < scenes.size(); i++) {
                int scaled = (int) Math.round((double) budgets.get(i) * target / sum);
                if (i == largest) scaled += target - (allocated + scaled); // 吸收舍入余数
                allocated += scaled;
                scenes.get(i).put("charBudget", Math.max(1, scaled));
            }
        }

        ObjectNode plan = com.writing.novel.llm.LlmJson.mapper().createObjectNode();
        ArrayNode outScenes = plan.putArray("scenes");
        int index = 1;
        for (ObjectNode s : scenes) {
            s.put("index", index++);
            outScenes.add(s);
        }
        int total = 0;
        for (JsonNode s : plan.path("scenes")) total += s.path("charBudget").asInt();
        plan.put("totalBudget", total);
        return plan;
    }
}
