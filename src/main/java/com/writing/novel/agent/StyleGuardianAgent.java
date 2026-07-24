package com.writing.novel.agent;

import com.writing.novel.domain.StyleProfile;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmTaskType;
import com.writing.novel.llm.prompt.PromptTemplateService;
import com.writing.novel.spi.NovelRepository;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 风格守护 Agent。建立风格基线 + 检测风格漂移。
 */
public class StyleGuardianAgent {

    private final LlmGateway gateway;
    private final NovelRepository repository;
    private final PromptTemplateService promptService;

    public StyleGuardianAgent(LlmGateway gateway, NovelRepository repository,
                              PromptTemplateService promptService) {
        this.gateway = gateway;
        this.repository = repository;
        this.promptService = promptService;
    }

    /** 基于世界观构思建立全书风格基线（在构思阶段调用，先于人物/大纲/写作） */
    public StyleProfile establishProfile(String projectId, String conceptAndTags) {
        String systemPrompt = promptService.render("style-establish", Map.of());

        var req = LlmRequest.builder()
                .taskType(LlmTaskType.STYLE_GUARDIAN)
                .systemPrompt(systemPrompt)
                .userMessage("请为以下小说构思建立风格基线：\n" + conceptAndTags)
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
        String systemPrompt = promptService.render("style-check", Map.of());

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

    /**
     * p6-4: 从最近章节重新评估并合并更新 StyleProfile。
     * 在卷末由 Orchestrator 调用，确保风格基线反映最新趋势。
     */
    public StyleProfile updateProfile(String projectId, String recentChaptersText) {
        var existing = repository.findStyleProfileByProject(projectId);
        if (existing.isEmpty()) return null;

        StyleProfile profile = existing.get();

        String systemPrompt = promptService.render("style-establish", Map.of());
        String userMessage = "请根据以下近期章节正文，更新风格基线（保留原有核心特征，融入最新趋势）：\n"
                + "当前基线——基调：" + profile.getTone()
                + "，叙事：" + profile.getVoice()
                + "，注意事项：" + profile.getDosAndDonts()
                + "\n\n近期章节采样（前1500字）：\n"
                + (recentChaptersText.length() > 1500 ? recentChaptersText.substring(0, 1500) : recentChaptersText);

        var req = LlmRequest.builder()
                .taskType(LlmTaskType.STYLE_GUARDIAN)
                .systemPrompt(systemPrompt)
                .userMessage(userMessage)
                .temperature(0.4)
                .maxTokens(800)
                .build();

        var resp = gateway.complete(req);
        String newTone = extractField(resp.getContent(), "基调");
        String newVoice = extractField(resp.getContent(), "叙事");
        String newDosAndDonts = extractField(resp.getContent(), "注意事项");

        // 合并：只更新非空的字段（不覆写为空值）
        if (!newTone.isBlank()) profile.setTone(newTone);
        if (!newVoice.isBlank()) profile.setVoice(newVoice);
        if (!newDosAndDonts.isBlank()) profile.setDosAndDonts(newDosAndDonts);

        repository.saveStyleProfile(profile);
        return profile;
    }

    private String extractField(String text, String field) {
        Pattern p = Pattern.compile("【" + Pattern.quote(field) + "】(.+?)(?=【|$)", Pattern.DOTALL);
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1).trim() : "";
    }
}
