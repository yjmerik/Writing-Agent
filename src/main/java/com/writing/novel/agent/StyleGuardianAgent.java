package com.writing.novel.agent;

import com.writing.novel.domain.StyleProfile;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmTaskType;
import com.writing.novel.llm.prompt.PromptTemplateService;
import com.writing.novel.spi.NovelRepository;

import java.util.List;
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

    /**
     * NCS-198: 卷末范本收割——读者分 ≥minScore 的章节抽最佳段落入 styleSamples
     * （cap 3 滚动刷新，单次至多 2 段；分数高的章优先）。激活 ChapterWriterAgent
     * 既有 few-shot 通道（NCS-102）。返回实际入库段数。
     */
    public int harvestHighScoreSamples(String projectId, List<Integer> chapterIndexes, int minScore) {
        if (repository == null || chapterIndexes == null || chapterIndexes.isEmpty()) return 0;
        var profileOpt = repository.findStyleProfileByProject(projectId);
        StyleProfile profile = profileOpt.orElseGet(() -> new StyleProfile(projectId));
        int added = 0;
        // 分数高的章优先出样
        List<Integer> ordered = new java.util.ArrayList<>(chapterIndexes);
        ordered.sort(java.util.Comparator.comparingInt((Integer idx) ->
                repository.findChapterReview(projectId, idx)
                        .map(com.writing.novel.domain.ChapterReview::getOverallScore).orElse(0)).reversed());
        for (int idx : ordered) {
            if (added >= 2) break;
            var review = repository.findChapterReview(projectId, idx);
            if (review.isEmpty() || review.get().getOverallScore() < minScore) continue;
            String best = bestParagraphOf(projectId, idx);
            if (best == null) continue;
            var before = List.copyOf(profile.getStyleSamples());
            profile.addStyleSample(best);
            profile.addSampleChapter(idx);
            if (!before.equals(profile.getStyleSamples())) added++;
        }
        if (added > 0) repository.saveStyleProfile(profile);
        if (added > 0) {
            com.writing.novel.telemetry.EventLog.emit("STYLE_SAMPLE_HARVESTED", java.util.Map.of(
                    "projectId", projectId, "samples", added));
        }
        return added;
    }

    /** 章内最佳范本段：最长的成段叙述（>80 字），clip 至 220 字 */
    private String bestParagraphOf(String projectId, int chapterIndex) {
        return repository.findChapterByIndex(projectId, chapterIndex)
                .map(com.writing.novel.domain.Chapter::getContent)
                .map(content -> java.util.Arrays.stream(content.split("\n"))
                        .map(String::trim)
                        .filter(l -> l.length() > 80)
                        .max(java.util.Comparator.comparingInt(String::length))
                        .map(l -> l.length() > 220 ? l.substring(0, 220) : l)
                        .orElse(null))
                .orElse(null);
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
        // NCS-198: load-or-create——preset 播种的 styleSamples（few-shot 范本）不被
        // 基线重建抹掉；tone/voice/dosAndDonts 仍按本次评估覆盖
        StyleProfile profile = repository != null
                ? repository.findStyleProfileByProject(projectId).orElseGet(() -> new StyleProfile(projectId))
                : new StyleProfile(projectId);
        profile.setTone(extractField(resp.getContent(), "基调"));
        profile.setVoice(extractField(resp.getContent(), "叙事"));
        profile.setDosAndDonts(extractField(resp.getContent(), "注意事项"));
        // NCS-199: 质感维度建档（解析失败保持 null——旧输出格式兼容）
        String density = extractField(resp.getContent(), "描写密度");
        if (!density.isBlank()) profile.setDescriptionDensity(density);
        String transition = extractField(resp.getContent(), "转场手法");
        if (!transition.isBlank()) profile.setTransitionTechnique(transition);
        String mix = extractField(resp.getContent(), "对话配比");
        if (!mix.isBlank()) profile.setDialogueNarrationRatio(mix);

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
        // NCS-128: 首次卷末更新前锁定意图快照（preset/作者定调的固定基准）
        profile.lockIntent();

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

        // NCS-128: 只更新可演化状态字段（tone/voice/dosAndDonts 作为「state」随卷演化），
        // 意图快照不触碰——检测器追随正文的"漂移合法化"回路自此斩断
        if (!newTone.isBlank()) profile.setTone(newTone);
        if (!newVoice.isBlank()) profile.setVoice(newVoice);
        if (!newDosAndDonts.isBlank()) profile.setDosAndDonts(newDosAndDonts);
        // NCS-199: 质感维度随卷演化（输出缺失保持原值）
        String newDensity = extractField(resp.getContent(), "描写密度");
        if (!newDensity.isBlank()) profile.setDescriptionDensity(newDensity);
        String newTransition = extractField(resp.getContent(), "转场手法");
        if (!newTransition.isBlank()) profile.setTransitionTechnique(newTransition);
        String newMix = extractField(resp.getContent(), "对话配比");
        if (!newMix.isBlank()) profile.setDialogueNarrationRatio(newMix);

        repository.saveStyleProfile(profile);
        return profile;
    }

    private String extractField(String text, String field) {
        Pattern p = Pattern.compile("【" + Pattern.quote(field) + "】(.+?)(?=【|$)", Pattern.DOTALL);
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1).trim() : "";
    }
}
