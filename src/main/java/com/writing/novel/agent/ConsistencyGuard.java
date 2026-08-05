package com.writing.novel.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.domain.Character;
import com.writing.novel.domain.ContinuityIssue;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmTaskType;
import com.writing.novel.llm.prompt.PromptTemplateService;
import com.writing.novel.spi.NovelRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * p10-a3+c2: 一致性守卫——合并检测能力越界(A3)和凭空证据(C2)。
 *
 * 每章写完后运行一次 LLM 调用，同时检测：
 * - 角色行为是否超出 ability 设定（如"不习武"却翻屋顶）
 * - 关键证据/道具是否有前文铺垫（如突然掏出一份文件）
 *
 * 发现问题 → ContinuityIssue(CHARACTER/PLOT, HIGH) → 触发重写
 */
public class ConsistencyGuard {

    private final LlmGateway gateway;
    private final NovelRepository repository;
    private final PromptTemplateService promptService;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public ConsistencyGuard(LlmGateway gateway, NovelRepository repository,
                            PromptTemplateService promptService) {
        this.gateway = gateway;
        this.repository = repository;
        this.promptService = promptService;
    }

    /**
     * 检测章节的能力越界和凭空证据问题。
     * @return 发现的问题列表（ContinuityIssue），空列表表示无问题
     */
    public List<ContinuityIssue> check(String projectId, int chapterIndex, String chapterContent) {
        List<ContinuityIssue> issues = new ArrayList<>();
        if (chapterContent == null || chapterContent.length() < 200) return issues;

        // 构建角色能力设定文本
        var characters = repository.findCharactersByProject(projectId);
        String abilities = characters.stream()
                .filter(c -> c.getAbility() != null && !c.getAbility().isBlank())
                .map(c -> "- " + c.getName() + "（" + c.getRole() + "）能力：" + c.getAbility())
                .collect(Collectors.joining("\n"));
        if (abilities.isEmpty()) abilities = "（无角色能力设定）";

        // 截断超长章节
        String content = chapterContent.length() > 5000
                ? chapterContent.substring(0, 5000) : chapterContent;

        String systemPrompt = promptService.render("consistency-guard", Map.of(
                "characterAbilities", abilities,
                "chapterContent", content));

        var req = LlmRequest.builder()
                .taskType(LlmTaskType.GENERAL)
                .systemPrompt(systemPrompt)
                .userMessage("请检查本章是否存在能力越界或凭空证据问题。")
                .temperature(0.2)
                .maxTokens(1000)
                .build();

        try {
            var resp = gateway.complete(req);
            issues.addAll(parseIssues(resp.getContent(), projectId, chapterIndex));
        } catch (Exception ignored) {
        }
        return issues;
    }

    private List<ContinuityIssue> parseIssues(String json, String projectId, int chapterIndex) {
        List<ContinuityIssue> issues = new ArrayList<>();
        try {
            String clean = json.trim();
            if (clean.startsWith("```")) {
                clean = clean.replaceAll("^```(json)?\\s*", "").replaceAll("\\s*```$", "");
            }
            JsonNode root = MAPPER.readTree(clean);

            // 能力越界
            JsonNode abilityArr = root.path("abilityViolations");
            if (abilityArr.isArray()) {
                for (JsonNode node : abilityArr) {
                    String character = node.path("character").asText("");
                    String violation = node.path("violation").asText("");
                    String setting = node.path("setting").asText("");
                    if (!violation.isBlank()) {
                        issues.add(new ContinuityIssue(projectId, chapterIndex,
                                ContinuityIssue.Type.CHARACTER,
                                ContinuityIssue.Severity.HIGH,
                                "能力越界：" + character + "的「" + violation + "」超出其设定（"
                                        + setting + "）。需要跨章节铺垫或修改行为。"));
                    }
                }
            }

            // 凭空证据
            JsonNode evidenceArr = root.path("evidenceProblems");
            if (evidenceArr.isArray()) {
                for (JsonNode node : evidenceArr) {
                    String evidence = node.path("evidence").asText("");
                    String reason = node.path("reason").asText("");
                    if (!evidence.isBlank()) {
                        issues.add(new ContinuityIssue(projectId, chapterIndex,
                                ContinuityIssue.Type.PLOT,
                                ContinuityIssue.Severity.HIGH,
                                "凭空证据：「" + evidence + "」无前文铺垫。" + reason
                                        + "。需要补充获取场景。"));
                    }
                }
            }

            // p10.1-e4: 时代用语穿帮
            JsonNode anachArr = root.path("anachronisms");
            if (anachArr.isArray()) {
                for (JsonNode node : anachArr) {
                    String word = node.path("word").asText("");
                    String context = node.path("context").asText("");
                    String suggestion = node.path("suggestion").asText("");
                    if (!word.isBlank()) {
                        StringBuilder desc = new StringBuilder("时代用语穿帮：「").append(word).append("」");
                        if (!context.isBlank()) desc.append("，上下文：").append(context);
                        if (!suggestion.isBlank()) desc.append("，建议替换为：").append(suggestion);
                        issues.add(new ContinuityIssue(projectId, chapterIndex,
                                ContinuityIssue.Type.WORLDBUILDING,
                                ContinuityIssue.Severity.MEDIUM,
                                desc.toString()));
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return issues;
    }
}
