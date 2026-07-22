package com.writing.novel.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.LlmTaskType;
import com.writing.novel.llm.prompt.PromptTemplateService;
import com.writing.novel.spi.NovelRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public QualityEvaluationAgent(LlmGateway gateway, NovelRepository repository,
                                  PromptTemplateService promptService) {
        this.gateway = gateway;
        this.repository = repository;
        this.promptService = promptService;
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

        String systemPrompt = promptService.render("quality-evaluate", Map.of());

        String userMessage = "=== 世界观设定 ===\n" + truncate(worldbuilding, 800) +
                "\n\n=== 人物卡 ===\n" + truncate(characters, 600) +
                "\n\n=== 大纲 ===\n" + truncate(outline, 500) +
                "\n\n=== 前情摘要 ===\n" + truncate(summary, 800) +
                "\n\n=== 章节正文采样 ===\n" + truncate(chapterSamples, 3000) +
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

    private EvaluationReport parseReport(String json) {
        try {
            // 尝试提取 JSON（LLM 可能包了 ```json 标记）
            String clean = json.trim();
            if (clean.startsWith("```")) {
                clean = clean.replaceAll("^```(json)?\\s*", "").replaceAll("\\s*```$", "");
            }
            JsonNode root = MAPPER.readTree(clean);

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

    private String collectChapterSamples(String projectId) {
        var chapters = repository.findChaptersByProject(projectId);
        if (chapters.isEmpty()) return "（无章节）";
        StringBuilder sb = new StringBuilder();
        // 采样：第1章、中间章、最后一章（各取前500字）
        int total = chapters.size();
        sb.append("共 ").append(total).append(" 章\n\n");
        // 第一章
        sb.append("--- 第1章 ").append(chapters.get(0).getTitle()).append("（前500字）---\n");
        sb.append(truncate(chapters.get(0).getContent(), 500)).append("\n\n");
        // 中间章
        if (total > 2) {
            int mid = total / 2;
            sb.append("--- 第").append(mid + 1).append("章 ").append(chapters.get(mid).getTitle()).append("（前500字）---\n");
            sb.append(truncate(chapters.get(mid).getContent(), 500)).append("\n\n");
        }
        // 最后一章
        if (total > 1) {
            sb.append("--- 第").append(total).append("章 ").append(chapters.get(total - 1).getTitle()).append("（前500字）---\n");
            sb.append(truncate(chapters.get(total - 1).getContent(), 500)).append("\n\n");
        }
        return sb.toString();
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
