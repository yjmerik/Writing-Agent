package com.writing.novel.agent;

import com.writing.novel.domain.StyleProfile;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmTaskType;
import com.writing.novel.spi.NovelRepository;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 风格守护 Agent。建立风格基线 + 检测风格漂移。
 */
public class StyleGuardianAgent {

    private final LlmGateway gateway;
    private final NovelRepository repository;

    public StyleGuardianAgent(LlmGateway gateway, NovelRepository repository) {
        this.gateway = gateway;
        this.repository = repository;
    }

    /** 从样章分析并建立风格基线 */
    public StyleProfile establishProfile(String projectId, String sampleText) {
        String systemPrompt = """
                你是文学风格分析专家。分析给定的文本样章，总结其写作风格。
                严格按以下格式输出：
                【基调】...
                【叙事】...
                【注意事项】...
                """;

        var req = LlmRequest.builder()
                .taskType(LlmTaskType.STYLE_GUARDIAN)
                .systemPrompt(systemPrompt)
                .userMessage("请分析以下样章的风格：\n" + sampleText)
                .temperature(0.5)
                .maxTokens(800)
                .build();

        var resp = gateway.complete(req);
        StyleProfile profile = new StyleProfile(projectId);
        profile.setTone(extractField(resp.getContent(), "基调"));
        profile.setVoice(extractField(resp.getContent(), "叙事"));
        profile.setDosAndDonts(extractField(resp.getContent(), "注意事项"));

        if (repository != null) repository.saveStyleProfile(profile);
        return profile;
    }

    /** 检查新章节的风格一致性，返回评估报告 */
    public String checkConsistency(String projectId, String chapterText, String styleBaseline) {
        String systemPrompt = """
                你是风格一致性审查员。评估新章节是否与全书风格基线一致。
                输出格式：风格一致性评分：XX/100。简短说明。
                """;

        var req = LlmRequest.builder()
                .taskType(LlmTaskType.STYLE_GUARDIAN)
                .systemPrompt(systemPrompt)
                .userMessage("风格基线：" + styleBaseline
                        + "\n\n新章节：\n" + chapterText
                        + "\n\n请评估风格一致性。")
                .temperature(0.3)
                .maxTokens(500)
                .build();

        return gateway.complete(req).getContent();
    }

    private String extractField(String text, String field) {
        Pattern p = Pattern.compile("【" + Pattern.quote(field) + "】(.+?)(?=【|$)", Pattern.DOTALL);
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1).trim() : "";
    }
}
