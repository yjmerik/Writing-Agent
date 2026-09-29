package com.writing.novel.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.domain.StoryFact;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.LlmTaskType;
import com.writing.novel.llm.prompt.PromptTemplateService;
import com.writing.novel.memory.RelevanceGateway;
import com.writing.novel.spi.NovelRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 质量评价 Agent（评分子系统）。
 * 从多个维度评价生成的小说质量，发现具体问题。
 *
 * 评价维度：
 * 1. 长程一致性：人物性格/能力/设定是否前后矛盾
 * 2. 风格统一：文风是否漂移、不同章节是否像不同人写的
 * 3. 情节质量：节奏、爽点、是否有流水账
 * 4. 人物塑造：角色是否立体、动机是否合理
 * 5. 世界观完整：设定是否自洽、有无漏洞
 */
public class QualityEvaluationAgent {

    private final LlmGateway gateway;
    private final NovelRepository repository;
    private final PromptTemplateService promptService;
    private final RelevanceGateway relevanceGateway; // p11-q0-02: 真典消费
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public QualityEvaluationAgent(LlmGateway gateway, NovelRepository repository,
                                  PromptTemplateService promptService) {
        this(gateway, repository, promptService, null);
    }

    /** p11-q0-02: 注入 RelevanceGateway，消费真典 ACTIVE 事实作为长程评价依据 */
    public QualityEvaluationAgent(LlmGateway gateway, NovelRepository repository,
                                  PromptTemplateService promptService, RelevanceGateway relevanceGateway) {
        this.gateway = gateway;
        this.repository = repository;
        this.promptService = promptService;
        this.relevanceGateway = relevanceGateway;
    }

    /**
     * 评价整个项目的质量。
     * 返回包含各维度评分和具体问题的评价报告。
     */
    public EvaluationReport evaluate(String projectId) {
        // 收集项目全部素材
        String worldbuilding = collectWorldbuilding(projectId);
        String characters = collectCharacters(projectId);
        String chapterSamples = collectChapterSamples(projectId);
        String outline = collectOutline(projectId);
        String summary = collectSummaries(projectId);
        String canon = collectCanon(projectId); // p11-q0-02: 真典作为长程一致性评价依据

        String systemPrompt = promptService.render("quality-evaluate", Map.of());

        String userMessage = "=== 世界观设定 ===\n" + truncate(worldbuilding, 800) +
                "\n\n=== 人物卡 ===\n" + truncate(characters, 600) +
                "\n\n=== 大纲 ===\n" + truncate(outline, 500) +
                "\n\n=== 前情摘要 ===\n" + truncate(summary, 800) +
                "\n\n=== 故事真典（已确立事实） ===\n" + truncate(canon, 800) +
                "\n\n=== 章节正文采样 ===\n" + truncate(chapterSamples, 9000) +
                "\n\n请评价这部小说的质量。";

        var req = LlmRequest.builder()
                .taskType(LlmTaskType.GENERAL)
                .systemPrompt(systemPrompt)
                .userMessage(userMessage)
                .temperature(0.3)
                .maxTokens(4000)
                .build();

        LlmResponse resp = gateway.complete(req);
        return parseReport(resp.getContent());
    }

    /** NCS-105: 包可见性放宽供中心化解析回归测试直接验证失败路径 */
    public EvaluationReport parseReport(String json) {
        // NCS-105: 中心化解析——失败路径发事件（此前静默降级全 0 分无任何信号）
        var parsed = com.writing.novel.llm.LlmJson.parse(json);
        if (!parsed.ok()) {
            com.writing.novel.llm.LlmJson.emitFailure("quality-evaluate",
                    com.writing.novel.llm.LlmTaskType.GENERAL.name(), parsed);
            EvaluationReport degraded = new EvaluationReport();
            degraded.summary = "评价解析失败（" + parsed.failure() + "），原始响应:\n" + json;
            return degraded;
        }
        try {
            JsonNode root = parsed.node();

            EvaluationReport report = new EvaluationReport();
            JsonNode scores = root.path("scores");
            report.consistency = scores.path("consistency").asInt();
            report.style = scores.path("style").asInt();
            report.plot = scores.path("plot").asInt();
            report.characters = scores.path("characters").asInt();
            report.worldbuilding = scores.path("worldbuilding").asInt();
            report.dialogue = scores.path("dialogue").asInt();
            report.patternDiversity = scores.path("pattern_diversity").asInt();
            report.overallScore = root.path("overallScore").asInt();
            report.summary = root.path("summary").asText("");

            JsonNode issues = root.path("issues");
            for (JsonNode issue : issues) {
                report.issues.add(new QualityIssue(
                        issue.path("dimension").asText(""),
                        issue.path("severity").asText("low"),
                        issue.path("description").asText("")));
            }
            return report;
        } catch (Exception e) {
            // 解析失败，返回原始文本
            EvaluationReport report = new EvaluationReport();
            report.summary = "评价解析失败，原始响应:\n" + json;
            return report;
        }
    }

    private String collectWorldbuilding(String projectId) {
        var entries = repository.findKnowledgeEntriesByProject(projectId);
        if (entries.isEmpty()) return "（无世界观设定）";
        StringBuilder sb = new StringBuilder();
        for (var e : entries) {
            sb.append("【").append(e.getType()).append("】").append(e.getName())
                    .append("：").append(e.getContent()).append("\n");
        }
        return sb.toString();
    }

    private String collectCharacters(String projectId) {
        var chars = repository.findCharactersByProject(projectId);
        if (chars.isEmpty()) return "（无人物卡）";
        StringBuilder sb = new StringBuilder();
        for (var c : chars) {
            sb.append(c.getName()).append("（").append(c.getRole()).append("）")
                    .append(" 性格：").append(safe(c.getPersonality()))
                    .append(" 能力：").append(safe(c.getAbility()))
                    .append("\n");
        }
        return sb.toString();
    }

    private String collectOutline(String projectId) {
        var outline = repository.findOutlineByProject(projectId);
        if (outline.isEmpty()) return "（无大纲）";
        var o = outline.get();
        StringBuilder sb = new StringBuilder();
        sb.append("总纲：").append(o.getPremise()).append("\n");
        for (var v : o.getVolumes()) {
            sb.append("卷").append(v.getVolumeIndex()).append(" ").append(v.getTitle())
                    .append("（").append(v.getSynopses().size()).append("章）\n");
        }
        return sb.toString();
    }

    private String collectSummaries(String projectId) {
        var summaries = repository.findSummariesByProject(projectId);
        if (summaries.isEmpty()) return "（无摘要）";
        StringBuilder sb = new StringBuilder();
        for (var s : summaries) {
            sb.append("第").append(s.getFromChapter()).append("-").append(s.getToChapter())
                    .append("章：").append(s.getContent()).append("\n");
        }
        return sb.toString();
    }

    /** p11-q0-02: 收集故事真典 ACTIVE 事实作为长程一致性评价依据（替代级联压缩摘要） */
    private String collectCanon(String projectId) {
        if (relevanceGateway == null) return "（无真典）";
        List<StoryFact> facts = relevanceGateway.relevantFacts(projectId, null, 30);
        if (facts.isEmpty()) return "（无已确立事实）";
        return facts.stream()
                .map(f -> "- " + f.getSubject() + "（" + f.getFactType() + "）：" + f.getAssertion())
                .collect(Collectors.joining("\n"));
    }

    /**
     * NCS-126: 分层采样——首/中/末三章全章摘要（头+尾节选）+ 其余章头尾节选，
     * 覆盖率 ≥20%（旧实现只取 3 章各前 500 字，30 章卷 <2% 采样，style 分基本是噪声）。
     * 附证据覆盖声明（评语可信度依据）。
     */
    private String collectChapterSamples(String projectId) {
        var chapters = repository.findChaptersByProject(projectId);
        if (chapters.isEmpty()) return "（无章节）";
        // 按章号排序（findChaptersByProject 顺序不保证）
        chapters.sort(java.util.Comparator.comparingInt(com.writing.novel.domain.Chapter::getChapterIndex));
        int total = chapters.size();
        // 采样数：max(3, ceil(20%))，cap 10（token 可控）
        int sampleCount = Math.min(10, Math.max(3, (total + 4) / 5));
        int stride = Math.max(1, total / sampleCount);
        StringBuilder picked = new StringBuilder();
        int pickedCount = 0;
        int lastPickedIndex = -1;
        for (int i = 0; i < total && pickedCount < sampleCount; i += stride) {
            var ch = chapters.get(i);
            boolean keyChapter = i == 0 || i == total - 1;   // 首章或末章
            lastPickedIndex = i;
            if (keyChapter) {
                // 关键章：头 700 字 + 尾 300 字（开头定基调、结尾见完成度）
                String c = ch.getContent() == null ? "" : ch.getContent();
                String head = truncate(c, 700);
                String tail = c.length() > 1000
                        ? "……" + c.substring(c.length() - 300) : "";
                picked.append("--- 第").append(ch.getChapterIndex()).append("章 ")
                        .append(ch.getTitle()).append("（开头+结尾节选）---\n")
                        .append(head).append(tail).append("\n\n");
            } else {
                picked.append("--- 第").append(ch.getChapterIndex()).append("章 ")
                        .append(ch.getTitle()).append("（前400字）---\n")
                        .append(truncate(ch.getContent(), 400)).append("\n\n");
            }
            pickedCount++;
        }
        // NCS-126: 末章保底——stride 未覆盖到最后一章时追加（卷尾完成度是评价关键证据）
        if (lastPickedIndex < total - 1 && pickedCount < sampleCount + 1) {
            var last = chapters.get(total - 1);
            String c = last.getContent() == null ? "" : last.getContent();
            picked.append("--- 第").append(last.getChapterIndex()).append("章 ")
                    .append(last.getTitle()).append("（开头+结尾节选）---\n")
                    .append(truncate(c, 700)).append(c.length() > 1000
                            ? "……" + c.substring(c.length() - 300) : "").append("\n\n");
            pickedCount++;
        }
        // 覆盖率声明
        double coverage = Math.min(100.0, 100.0 * pickedCount / total);
        return "共 " + total + " 章；本次评价证据覆盖 " + pickedCount + " 章（分层采样，覆盖率 "
                + Math.round(coverage) + "%≥20%）\n\n" + picked;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    private static String safe(String s) {
        return s == null ? "无" : s;
    }

    // --- 报告数据结构 ---

    public static class EvaluationReport {
        public int consistency;
        public int style;
        public int plot;
        public int characters;
        public int worldbuilding;
        public int dialogue;
        public int patternDiversity;
        public int overallScore;
        public String summary;
        public List<QualityIssue> issues = new ArrayList<>();

        public Map<String, Object> toMap() {
            Map<String, Object> m = new HashMap<>();
            Map<String, Integer> scores = new HashMap<>();
            scores.put("consistency", consistency);
            scores.put("style", style);
            scores.put("plot", plot);
            scores.put("characters", characters);
            scores.put("worldbuilding", worldbuilding);
            scores.put("dialogue", dialogue);
            scores.put("pattern_diversity", patternDiversity);
            m.put("scores", scores);
            m.put("overallScore", overallScore);
            m.put("summary", summary);
            m.put("issues", issues.stream().map(i -> Map.of(
                    "dimension", i.dimension, "severity", i.severity, "description", i.description)).toList());
            return m;
        }
    }

    public record QualityIssue(String dimension, String severity, String description) {}
}
