package com.writing.novel.agent;

import com.writing.novel.domain.ContinuityIssue;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmTaskType;
import com.writing.novel.spi.NovelRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 连续性校验 Agent。检查新章节是否与已有设定矛盾。
 */
public class ContinuityCheckerAgent {

    private final LlmGateway gateway;
    private final NovelRepository repository;

    public ContinuityCheckerAgent(LlmGateway gateway, NovelRepository repository) {
        this.gateway = gateway;
        this.repository = repository;
    }

    public List<ContinuityIssue> check(String projectId, int chapterIndex,
                                        String chapterContent, String relevantSettings) {
        String systemPrompt = """
                你是小说连续性审查员。检查新章节是否与已有设定矛盾。
                如果发现问题，严格按以下格式每行一条输出：
                【类型|严重度】问题描述
                类型：人物/世界观/时间线/伏笔
                严重度：高/中/低
                如果没有问题，回复"未发现连续性问题。"
                """;

        String userMessage = "新章节内容：\n" + chapterContent
                + "\n\n相关设定：\n" + relevantSettings;

        var req = LlmRequest.builder()
                .taskType(LlmTaskType.CONTINUITY_CHECK)
                .systemPrompt(systemPrompt)
                .userMessage(userMessage)
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
