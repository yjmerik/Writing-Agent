package com.writing.novel.agent;

import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmTaskType;
import com.writing.novel.llm.prompt.PromptTemplateService;

import java.util.Map;

/**
 * p11-G1: 段落级编辑 Agent——只重写/扩充选中段落，保留前后内容不变。
 *
 * 三种模式：
 * - REWRITE: 重写选中段落（保持核心信息，遵循编辑指令改善）
 * - EXPAND: 扩充选中段落（字数翻倍，增加细节）
 * - INSERT: 在选中段落后插入新段落
 *
 * 与 rewriteChapter（整章重写）的区别：这里只改一段，不碰其他段落。
 */
public class PassageEditor {

    private final LlmGateway gateway;
    private final PromptTemplateService promptService;
    /** NCS-127: 可选仓库——携带全书风格约束（含范本），null 时风格段为空（行为不变） */
    private com.writing.novel.spi.NovelRepository repository;

    public PassageEditor(LlmGateway gateway, PromptTemplateService promptService) {
        this.gateway = gateway;
        this.promptService = promptService;
    }

    /** NCS-127: 注入仓库以读取 StyleProfile（生产装配用） */
    public void setRepository(com.writing.novel.spi.NovelRepository repository) {
        this.repository = repository;
    }

    /** NCS-127: 风格约束段（含 NCS-102 范本 few-shot；无档案时为空串） */
    private String styleGuideFor(String projectId) {
        if (repository == null || projectId == null) return "";
        var opt = repository.findStyleProfileByProject(projectId);
        if (opt.isEmpty()) return "";
        var style = opt.get();
        StringBuilder sb = new StringBuilder();
        if (style.getTone() != null) sb.append(style.getTone());
        if (style.getVoice() != null && !style.getVoice().isBlank()) {
            if (sb.length() > 0) sb.append("。");
            sb.append(style.getVoice());
        }
        if (style.getDosAndDonts() != null) {
            if (sb.length() > 0) sb.append("。");
            sb.append(style.getDosAndDonts());
        }
        var samples = style.getStyleSamples();
        if (samples != null && !samples.isEmpty()) {
            sb.append("\n【风格范本（模仿语感，不得照抄）】\n");
            for (int i = 0; i < samples.size(); i++) {
                sb.append("——范本").append(i + 1).append("——\n").append(samples.get(i)).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * 编辑单个段落。
     * @param passageText 待编辑段落原文
     * @param contextBefore 前 2 段（衔接参考）
     * @param contextAfter 后 2 段（衔接参考）
     * @param instruction 编辑指令
     * @param mode REWRITE / EXPAND / INSERT
     * @return 编辑后的段落文本（单段），失败返回 null
     */
    public String editPassage(String passageText, String contextBefore, String contextAfter,
                               String instruction, String mode) {
        return editPassage(null, passageText, contextBefore, contextAfter, instruction, mode);
    }

    /** NCS-127: 带 projectId 的编辑——prompt 携带全书风格约束与范本 */
    public String editPassage(String projectId, String passageText, String contextBefore,
                               String contextAfter, String instruction, String mode) {
        if (passageText == null || passageText.isBlank()) return null;
        if (mode == null) mode = "REWRITE";

        String systemPrompt = promptService.render("passage-edit", Map.of(
                "mode", mode,
                "contextBefore", contextBefore != null ? contextBefore : "（本章开头）",
                "passageText", passageText,
                "styleGuide", styleGuideFor(projectId),
                "contextAfter", contextAfter != null ? contextAfter : "（本章结尾）",
                "instruction", instruction != null ? instruction : "改善这段文字的质量"));

        var req = LlmRequest.builder()
                .taskType(LlmTaskType.GENERAL)
                .systemPrompt(systemPrompt)
                .userMessage("请执行段落编辑。")
                .temperature(0.7)
                .maxTokens(4096)
                .build();

        try {
            var resp = gateway.complete(req);
            String result = resp.getContent();
            if (result == null || result.isBlank()) return null;
            // NCS-106: 中心化围栏剥离（markdown 代码块包裹的正文段）
            return com.writing.novel.llm.LlmJson.stripFences(result);
        } catch (Exception e) {
            return null;
        }
    }
}
