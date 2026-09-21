package com.writing.novel.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.domain.Character;
import com.writing.novel.domain.ContinuityIssue;
import com.writing.novel.domain.StoryFact;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmTaskType;
import com.writing.novel.llm.prompt.PromptTemplateService;
import com.writing.novel.memory.RelevanceGateway;
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
    private final com.writing.novel.persistence.sqlite.CheckResultRepository checkResults;
    private final RelevanceGateway relevanceGateway; // p11-q0-01: 检索收窄门面
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ConsistencyGuard.class);

    public ConsistencyGuard(LlmGateway gateway, NovelRepository repository,
                            PromptTemplateService promptService) {
        this(gateway, repository, promptService, null);
    }

    /** p11-q0-01: 注入 RelevanceGateway 后按 cast 收窄角色，不再全量 findAll* */
    public ConsistencyGuard(LlmGateway gateway, NovelRepository repository,
                            PromptTemplateService promptService, RelevanceGateway relevanceGateway) {
        this(gateway, repository, promptService, relevanceGateway, null);
    }

    public ConsistencyGuard(LlmGateway gateway, NovelRepository repository,
                            PromptTemplateService promptService, RelevanceGateway relevanceGateway,
                            com.writing.novel.persistence.sqlite.CheckResultRepository checkResults) {
        this.checkResults = checkResults;
        this.gateway = gateway;
        this.repository = repository;
        this.promptService = promptService;
        this.relevanceGateway = relevanceGateway;
    }

    /**
     * 检测章节的能力越界和凭空证据问题。
     * @return 发现的问题列表（ContinuityIssue），空列表表示无问题
     */
    public List<ContinuityIssue> check(String projectId, int chapterIndex, String chapterContent) {
        return check(projectId, chapterIndex, chapterContent, null);
    }

    /**
     * p11-q0-01: 经 RelevanceGateway 按本章 cast 收窄角色，不再全量 findAll*。
     * @param cast 本章出场人物名（收窄依据；null/空时 gateway 主角保底）
     */
    public List<ContinuityIssue> check(String projectId, int chapterIndex, String chapterContent, List<String> cast) {
        List<ContinuityIssue> issues = new ArrayList<>();
        if (chapterContent == null || chapterContent.length() < 200) throw new IllegalArgumentException("正文不足，未执行一致性检查");

        // p11-q0-01: 经 RelevanceGateway 按 cast 收窄（百万字下避免全量角色进 LLM 失明）
        var characters = relevanceGateway != null
                ? relevanceGateway.relevantCharacters(projectId, cast, 12)
                : repository.findCharactersByProject(projectId);
        String abilities = characters.stream()
                .filter(c -> c.getAbility() != null && !c.getAbility().isBlank())
                .map(c -> "- " + c.getName() + "（" + c.getRole() + "）能力：" + c.getAbility())
                .collect(Collectors.joining("\n"));
        if (abilities.isEmpty()) abilities = "（无角色能力设定）";

        // 截断超长章节
        String content = chapterContent.length() > 5000
                ? chapterContent.substring(0, 5000) : chapterContent;

        // p11-q0-02: 消费真典 ACTIVE 事实作为能力越界/凭空证据的校验依据
        var facts = relevanceGateway != null
                ? relevanceGateway.relevantFacts(projectId, cast, 15)
                : java.util.Collections.<StoryFact>emptyList();
        String systemPrompt = promptService.render("consistency-guard", Map.of(
                "characterAbilities", abilities,
                "storyCanon", formatCanon(facts),
                "recentChapters", recentChapterEvidence(projectId, chapterIndex),
                "chapterContent", content));

        var req = LlmRequest.builder()
                .taskType(LlmTaskType.GENERAL)
                .systemPrompt(systemPrompt)
                .userMessage("请检查本章是否存在能力越界或凭空证据问题。")
                .temperature(0.2)
                .maxTokens(3072)
                .build();

        try {
            var resp = gateway.complete(req);
            issues.addAll(parseIssues(resp.getContent(), projectId, chapterIndex));
            recordCheck(projectId, chapterIndex, "consistency-guard", issues.isEmpty()
                    ? CheckResultEnvelope.Status.VALID_EMPTY : CheckResultEnvelope.Status.SUCCESS,
                    resp.getContent(), chapterContent);
        } catch (Exception e) {
            recordCheck(projectId, chapterIndex, "consistency-guard", CheckResultEnvelope.Status.UNKNOWN,
                    e.getMessage(), chapterContent);
            throw new IllegalStateException("一致性守卫未完成，不能视为无问题", e);
        }
        return issues;
    }

    /** 最近两章的取证场景不能因为真典切片遗漏而消失；查询范围排除当前/未来稿件。 */
    private String recentChapterEvidence(String projectId, int chapterIndex) {
        if (chapterIndex <= 1) return "（无前章）";
        return repository.findChaptersByIndexRange(projectId, Math.max(1, chapterIndex - 2), chapterIndex - 1)
                .stream().filter(c -> c.getChapterIndex() < chapterIndex && c.getContent() != null)
                .sorted(java.util.Comparator.comparingInt(com.writing.novel.domain.Chapter::getChapterIndex))
                .map(c -> {
                    String text = c.getContent();
                    if (text.length() > 5000)
                        text = text.substring(0, 2500) + "\n（中段省略）\n" + text.substring(text.length() - 2500);
                    return "第" + c.getChapterIndex() + "章：\n" + text;
                }).collect(Collectors.joining("\n\n"));
    }

    /** p11-q0-02: 格式化真典事实为校验依据文本 */
    private String formatCanon(List<StoryFact> facts) {
        if (facts == null || facts.isEmpty()) return "（无已确立事实）";
        return facts.stream()
                .map(f -> "- " + f.getSubject() + "（" + f.getFactType() + "）：" + f.getAssertion())
                .collect(Collectors.joining("\n"));
    }

    private List<ContinuityIssue> parseIssues(String json, String projectId, int chapterIndex) {
        List<ContinuityIssue> issues = new ArrayList<>();
        try {
            String clean = json.trim();
            if (clean.startsWith("```")) {
                clean = clean.replaceAll("^```(json)?\\s*", "").replaceAll("\\s*```$", "");
            }
            JsonNode root = MAPPER.readTree(clean);
            for (String field : List.of("abilityViolations", "evidenceProblems", "anachronisms")) {
                if (root == null || !root.path(field).isArray())
                    throw new IllegalArgumentException("缺少问题数组：" + field);
            }

            // 能力越界
            JsonNode abilityArr = root.path("abilityViolations");
            if (abilityArr.isArray()) {
                for (JsonNode node : abilityArr) {
                    requireText(node, "character", "violation", "setting");
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
                    requireText(node, "evidence", "reason");
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
                    requireText(node, "word", "context", "suggestion");
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
        } catch (Exception e) {
            throw new IllegalArgumentException("一致性检查输出无效", e);
        }
        return issues;
    }

    private static void requireText(JsonNode node, String... fields) {
        for (String field : fields) {
            if (!node.path(field).isTextual() || node.path(field).asText().isBlank())
                throw new IllegalArgumentException("缺少有效文本：" + field);
        }
    }

    public record CorrectionVerdict(String issueId, String status, String evidence, String reason) {
        public boolean resolved() { return "RESOLVED".equals(status); }
    }

    /** Stable ordinal IDs keep recording fingerprints independent of generated database UUIDs. */
    public List<CorrectionVerdict> verifyCorrections(String projectId, int chapterIndex, int chapterVersion,
            String chapterContent, List<ContinuityIssue> findings, String settings) {
        if (findings.isEmpty()) return List.of();
        var input = MAPPER.createObjectNode();
        input.put("chapterVersion", chapterVersion);
        input.put("settings", settings);
        input.put("chapterContent", chapterContent);
        var originals = input.putArray("findings");
        for (int i = 0; i < findings.size(); i++) {
            var finding = originals.addObject();
            finding.put("issueId", String.valueOf(i + 1));
            finding.put("description", findings.get(i).getDescription());
        }
        try {
            var request = LlmRequest.builder().taskType(LlmTaskType.GENERAL)
                    .systemPrompt(promptService.render("correction-verification", Map.of()))
                    .userMessage(input.toString()).temperature(0.0).maxTokens(3072).build();
            String raw = gateway.complete(request).getContent();
            JsonNode result = parseVerificationResponse(raw, chapterVersion);
            var verdicts = new java.util.HashMap<String, CorrectionVerdict>();
            consumeVerdictItems(result.path("results"), chapterContent, verdicts);
            JsonNode refillResults = null;

            // NCS-069: 缺项一次补问——模型偶发回答缺项（2项只回1项），严格校验保留；
            // 对缺失 issueId 单独补问一轮并合并，补问后仍缺才失败（屏障）。
            var missing = new ArrayList<Integer>();
            for (int i = 1; i <= findings.size(); i++) {
                if (!verdicts.containsKey(String.valueOf(i))) missing.add(i);
            }
            if (!missing.isEmpty()) {
                String missingIds = missing.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
                com.writing.novel.telemetry.EventLog.emit("CORRECTION_GAP_REFILL", Map.of(
                        "projectId", projectId, "chapterIndex", chapterIndex, "chapterVersion", chapterVersion,
                        "missing", missingIds, "count", missing.size()));
                var refillInput = MAPPER.createObjectNode();
                refillInput.put("chapterVersion", chapterVersion);
                refillInput.put("settings", settings);
                refillInput.put("chapterContent", chapterContent);
                var refillFindings = refillInput.putArray("findings");
                for (int id : missing) {
                    var finding = refillFindings.addObject();
                    finding.put("issueId", String.valueOf(id));
                    finding.put("description", findings.get(id - 1).getDescription());
                }
                var refillRequest = LlmRequest.builder().taskType(LlmTaskType.GENERAL)
                        .systemPrompt(promptService.render("correction-verification", Map.of()))
                        .userMessage(refillInput.toString()
                                + "\n上一次回答缺少问题 " + missingIds + " 的结论。请只输出这些问题编号的结论。")
                        .temperature(0.0).maxTokens(3072).build();
                String refillRaw = gateway.complete(refillRequest).getContent();
                JsonNode refillResult = parseVerificationResponse(refillRaw, chapterVersion);
                consumeVerdictItems(refillResult.path("results"), chapterContent, verdicts);
                refillResults = refillResult.path("results");
            }

            var ordered = new ArrayList<CorrectionVerdict>();
            for (int i = 1; i <= findings.size(); i++) {
                var verdict = verdicts.get(String.valueOf(i));
                if (verdict == null) throw new IllegalArgumentException("缺少原问题复验结论：" + i);
                ordered.add(verdict);
            }
            var receipt = MAPPER.createObjectNode();
            receipt.put("chapterVersion", chapterVersion);
            receipt.set("findings", originals);
            receipt.set("results", result.path("results"));
            if (refillResults != null) receipt.set("refillResults", refillResults);
            recordCheck(projectId, chapterIndex, "correction-verification", CheckResultEnvelope.Status.SUCCESS,
                    receipt.toString(), chapterContent);
            return List.copyOf(ordered);
        } catch (Exception e) {
            recordCheck(projectId, chapterIndex, "correction-verification", CheckResultEnvelope.Status.UNKNOWN,
                    e.getMessage(), chapterContent);
            throw new IllegalStateException("原问题逐项复验未完成", e);
        }
    }

    /** 剥离代码围栏并校验稿号；结果数组存在性在此保证 */
    private JsonNode parseVerificationResponse(String raw, int chapterVersion) throws Exception {
        String clean = raw == null ? "" : raw.trim().replaceAll("^```(json)?\\s*", "").replaceAll("\\s*```$", "");
        JsonNode result = MAPPER.readTree(clean);
        if (result == null || !result.path("chapterVersion").isIntegralNumber()
                || result.path("chapterVersion").asInt() != chapterVersion
                || !result.path("results").isArray())
            throw new IllegalArgumentException("复验稿号不匹配或结果格式无效");
        return result;
    }

    /** 逐条严格校验并入集：编号必填、结论枚举、RESOLVED 需正文原文证据、编号不得重复 */
    private void consumeVerdictItems(JsonNode results, String chapterContent,
                                     java.util.HashMap<String, CorrectionVerdict> verdicts) {
        for (JsonNode item : results) {
            requireText(item, "issueId", "status", "reason");
            String id = item.path("issueId").asText();
            String status = item.path("status").asText();
            String evidence = item.path("evidence").asText("");
            if (!List.of("RESOLVED", "UNRESOLVED", "UNKNOWN").contains(status))
                throw new IllegalArgumentException("未知复验结论");
            if ("RESOLVED".equals(status) && (evidence.strip().length() < 4 || !chapterContent.contains(evidence)))
                throw new IllegalArgumentException("已解决结论缺少当前正文原文证据");
            if (verdicts.putIfAbsent(id, new CorrectionVerdict(id, status, evidence, item.path("reason").asText())) != null)
                throw new IllegalArgumentException("复验问题编号重复");
        }
    }

    private void recordCheck(String projectId, int chapterIndex, String checker,
            CheckResultEnvelope.Status status, String reason, String content) {
        if (checkResults != null) checkResults.save(projectId, chapterIndex, checker, status, reason, content);
    }
}
