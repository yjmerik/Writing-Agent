package com.writing.novel.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.domain.ContinuityIssue;
import com.writing.novel.domain.GenerationLog;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmTaskType;
import com.writing.novel.llm.prompt.PromptTemplateService;
import com.writing.novel.spi.NovelRepository;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * p11.1-J2: 桥段追踪器——每章提取叙事桥段标签，检测与近期章节的重复。
 *
 * 解决"告别戏重复""叙事模式雷同"等语义级桥段重复问题。
 * 现有检测器（DiversityTracker/ClimaxDiversityTracker）只在类型层面追踪，
 * 无法检测"又一次写了离别送行戏"这种语义级重复。
 */
public class PlotMotifTracker {

    private final LlmGateway gateway;
    private final NovelRepository repository;
    private final PromptTemplateService promptService;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 检查最近多少章的桥段（防重复窗口） */
    private static final int RECENT_WINDOW = 8;

    public PlotMotifTracker(LlmGateway gateway, NovelRepository repository,
                            PromptTemplateService promptService) {
        this.gateway = gateway;
        this.repository = repository;
        this.promptService = promptService;
    }

    /**
     * 提取本章桥段标签并检测重复。
     * @return 检测到的重复问题列表（可能为空）
     */
    public List<ContinuityIssue> trackAndDetect(String projectId, int chapterIndex, String chapterContent) {
        List<ContinuityIssue> issues = new ArrayList<>();
        if (chapterContent == null || chapterContent.length() < 200) return issues;

        // 1. LLM 提取桥段标签
        List<String> motifs = extractMotifs(chapterContent);
        if (motifs.isEmpty()) return issues;

        // 2. 记录到 GenerationLog（使用 CHAPTER_MOTIF 类型）
        String motifJson = motifs.stream().reduce((a, b) -> a + ";" + b).orElse("");
        try {
            repository.saveGenerationLog(new GenerationLog(
                    projectId, "CHAPTER_MOTIF",
                    "第" + chapterIndex + "章桥段：" + motifJson,
                    "[" + motifs.stream().map(m -> "\"" + m + "\"")
                            .reduce((a, b) -> a + "," + b).orElse("") + "]",
                    0));
        } catch (Exception ignored) {}

        // 3. 查询最近 N 章的桥段标签
        Set<String> recentMotifs = collectRecentMotifs(projectId, chapterIndex);

        // 4. 检测重复
        for (String motif : motifs) {
            if (recentMotifs.contains(motif)) {
                issues.add(new ContinuityIssue(projectId, chapterIndex,
                        ContinuityIssue.Type.STRUCTURE,
                        ContinuityIssue.Severity.MEDIUM,
                        "桥段重复：本章使用了「" + motif + "」桥段，最近" + RECENT_WINDOW +
                        "章内已使用过。建议改用不同的叙事手法或场景类型。"));
            }
        }

        return issues;
    }

    /**
     * 获取最近章节已使用的桥段标签（供 ContextAssembler 注入约束）。
     */
    public String getRecentMotifConstraint(String projectId, int currentChapter) {
        Set<String> recent = collectRecentMotifs(projectId, currentChapter);
        if (recent.isEmpty()) return "";
        return "□ 桥段去重：最近" + RECENT_WINDOW + "章已使用以下桥段，本章应避免重复——" +
                String.join("、", recent) + "\n";
    }

    private List<String> extractMotifs(String chapterContent) {
        String content = chapterContent.length() > 4000
                ? chapterContent.substring(0, 4000) : chapterContent;
        String systemPrompt = promptService.render("plot-motif-extract", Map.of(
                "chapterContent", content));
        var req = LlmRequest.builder()
                .taskType(LlmTaskType.GENERAL)
                .systemPrompt(systemPrompt)
                .userMessage("请提取桥段标签。")
                .temperature(0.2)
                .maxTokens(3072)
                .build();
        try {
            var resp = gateway.complete(req);
            var parsed = com.writing.novel.llm.LlmJson.parse(resp.getContent());
            // NCS-119: 桥段提取失败可见（旧静默空——本章桥段记录永久缺失）
            if (!parsed.ok()) {
                throw new com.writing.novel.agent.util.PostProcessStageFailure("plot-motif",
                        "桥段提取解析失败: " + parsed.failure() + ": " + parsed.reason());
            }
            JsonNode root = parsed.node();
            JsonNode arr = root.path("motifs");
            List<String> motifs = new ArrayList<>();
            if (arr.isArray()) {
                for (JsonNode n : arr) {
                    String m = n.asText("").trim();
                    if (!m.isBlank()) motifs.add(m);
                }
            }
            return motifs;
        } catch (com.writing.novel.agent.util.PostProcessStageFailure e) {
            throw e;
        } catch (Exception e) {
            throw new com.writing.novel.agent.util.PostProcessStageFailure("plot-motif", "桥段提取失败: " + e.getMessage(), e);
        }
    }

    private Set<String> collectRecentMotifs(String projectId, int currentChapter) {
        Set<String> motifs = new HashSet<>();
        try {
            var logs = repository.findRecentGenerationLogs(projectId, "CHAPTER_MOTIF", RECENT_WINDOW);
            for (var log : logs) {
                String keys = log.getKeyElements();
                if (keys != null && !keys.equals("[]")) {
                    // 解析 JSON 数组
                    try {
                        JsonNode arr = com.writing.novel.llm.LlmJson.parseOrThrow(keys, "历史桥段记录");
                        if (arr.isArray()) {
                            for (JsonNode n : arr) {
                                String m = n.asText("").trim();
                                if (!m.isBlank()) motifs.add(m);
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
        return motifs;
    }
}
