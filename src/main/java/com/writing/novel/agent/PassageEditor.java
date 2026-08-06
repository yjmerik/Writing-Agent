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

    public PassageEditor(LlmGateway gateway, PromptTemplateService promptService) {
        this.gateway = gateway;
        this.promptService = promptService;
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
        if (passageText == null || passageText.isBlank()) return null;
        if (mode == null) mode = "REWRITE";

        String systemPrompt = promptService.render("passage-edit", Map.of(
                "mode", mode,
                "contextBefore", contextBefore != null ? contextBefore : "（本章开头）",
                "passageText", passageText,
                "contextAfter", contextAfter != null ? contextAfter : "（本章结尾）",
                "instruction", instruction != null ? instruction : "改善这段文字的质量"));

        var req = LlmRequest.builder()
                .taskType(LlmTaskType.GENERAL)
                .systemPrompt(systemPrompt)
                .userMessage("请执行段落编辑。")
                .temperature(0.7)
                .maxTokens(1500)
                .build();

        try {
            var resp = gateway.complete(req);
            String result = resp.getContent();
            if (result == null || result.isBlank()) return null;
            // 清理可能的 markdown 包裹
            result = result.trim();
            if (result.startsWith("```")) {
                result = result.replaceAll("^```[a-z]*\\s*", "").replaceAll("\\s*```$", "").trim();
            }
            return result;
        } catch (Exception e) {
            return null;
        }
    }
}
