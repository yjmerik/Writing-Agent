package com.writing.novel.agent;

import com.writing.novel.domain.ContinuityIssue;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmTaskType;
import com.writing.novel.llm.prompt.PromptTemplateService;
import com.writing.novel.spi.NovelRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 连续性校验 Agent。检查新章节是否与已有设定矛盾。
 */
public class ContinuityCheckerAgent {

    private final LlmGateway gateway;
    private final NovelRepository repository;
    private final PromptTemplateService promptService;

    public ContinuityCheckerAgent(LlmGateway gateway, NovelRepository repository,
                                  PromptTemplateService promptService) {
        this.gateway = gateway;
        this.repository = repository;
        this.promptService = promptService;
    }

    public List<ContinuityIssue> check(String projectId, int chapterIndex,
                                        String chapterContent, String relevantSettings) {
        String systemPrompt = promptService.render("continuity-check", Map.of());

        StringBuilder msg = new StringBuilder("新章节内容：\n").append(chapterContent)
                .append("\n\n相关设定：\n").append(relevantSettings);

        // mc-p2-02: 历史卷摘要 + 叙事线索台账——长程连续性检查的依据（复现 M2）
        String history = buildHistorySection(projectId);
        if (history != null && !history.isBlank()) {
            msg.append("\n\n").append(history);
        }

        var req = LlmRequest.builder()
                .taskType(LlmTaskType.CONTINUITY_CHECK)
                .systemPrompt(systemPrompt)
                .userMessage(msg.toString())
                .temperature(0.2)
                .maxTokens(1000)
                .build();

        var resp = gateway.complete(req);
        List<ContinuityIssue> issues = parseIssues(resp.getContent(), projectId, chapterIndex);

        if (repository != null) {
            for (ContinuityIssue issue : issues) {
                repository.saveContinuityIssue(issue);
            }
        }
        return issues;
    }

    /**
     * mc-p2-02: 构建长程连续性检查依据——历史卷摘要（span>3 的卷级摘要，过滤 dirty）
     * + 叙事线索台账（未回收的线索）。检查新章节时与这些历史依据核对。
     */
    private String buildHistorySection(String projectId) {
        if (repository == null) return "";
        StringBuilder sb = new StringBuilder();

        // 历史卷摘要：卷级（span>3）且非 dirty；最多 5 卷，按章节倒序（最近卷优先）
        var volumeLevel = repository.findSummariesByProject(projectId).stream()
                .filter(s -> s.getToChapter() - s.getFromChapter() + 1 > 3 && !s.isDirty())
                .sorted((a, b) -> Integer.compare(b.getFromChapter(), a.getFromChapter()))
                .limit(5)
                .toList();
        if (!volumeLevel.isEmpty()) {
            sb.append("【历史卷摘要（前情回顾——新章节需与以下已确立情节保持一致）】\n");
            for (var s : volumeLevel) {
                String content = s.getContent();
                if (content == null) continue;
                if (content.length() > 200) content = content.substring(0, 200) + "...";
                sb.append("第").append(s.getFromChapter()).append("-").append(s.getToChapter())
                        .append("章：").append(content).append("\n");
            }
            sb.append("\n");
        }

        // 叙事线索台账：未回收线索
        var threads = repository.findAllNarrativeThreads(projectId).stream()
                .filter(t -> t.getStatus() != com.writing.novel.domain.ThreadStatus.RESOLVED)
                .toList();
        if (!threads.isEmpty()) {
            sb.append("【叙事线索台账（本章不得与以下未回收线索冲突，必要时推进或回收）】\n");
            for (var t : threads) {
                sb.append("■ ").append(t.getName())
                        .append("（第").append(t.getFirstSeenChapter()).append("章起，状态：")
                        .append(t.getStatus()).append("）\n");
            }
        }
        return sb.toString().trim();
    }

    private List<ContinuityIssue> parseIssues(String text, String projectId, int chapterIndex) {
        List<ContinuityIssue> issues = new ArrayList<>();
        if (text == null || text.contains("未发现")) return issues;

        Pattern p = Pattern.compile("【([^|]+)\\|([^】]+)】(.+)");
        Matcher m = p.matcher(text);
        while (m.find()) {
            ContinuityIssue.Type type = parseType(m.group(1).trim());
            ContinuityIssue.Severity severity = parseSeverity(m.group(2).trim());
            String desc = m.group(3).trim();
            issues.add(new ContinuityIssue(projectId, chapterIndex, type, severity, desc));
        }
        return issues;
    }

    private ContinuityIssue.Type parseType(String s) {
        return switch (s) {
            case "人物" -> ContinuityIssue.Type.CHARACTER;
            case "世界观" -> ContinuityIssue.Type.WORLDBUILDING;
            case "时间线" -> ContinuityIssue.Type.TIMELINE;
            case "伏笔" -> ContinuityIssue.Type.FORESHADOWING;
            default -> ContinuityIssue.Type.WORLDBUILDING;
        };
    }

    private ContinuityIssue.Severity parseSeverity(String s) {
        return switch (s) {
            case "高" -> ContinuityIssue.Severity.HIGH;
            case "中" -> ContinuityIssue.Severity.MEDIUM;
            default -> ContinuityIssue.Severity.LOW;
        };
    }
}
