package com.writing.novel.agent;

import com.writing.novel.domain.SceneType;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmTaskType;
import com.writing.novel.llm.prompt.PromptTemplateService;

import java.util.Map;

/**
 * 场景类型分类器——用 LLM 将章节正文归类为一种 SceneType。
 * p10-b2: 用于检测"连续多章同一场景类型"的结构套路化。
 * 分类任务是 7 选 1，使用低成本模型即可。
 */
public class SceneClassifier {

    private final LlmGateway gateway;
    private final PromptTemplateService promptService;

    public SceneClassifier(LlmGateway gateway, PromptTemplateService promptService) {
        this.gateway = gateway;
        this.promptService = promptService;
    }

    /**
     * 分类章节的场景类型。
     * @param chapterContent 章节正文
     * @return SceneType（失败时返回 TRANSITION 作为安全默认值）
     */
    public SceneType classify(String chapterContent) {
        if (chapterContent == null || chapterContent.isBlank()) return SceneType.TRANSITION;

        // 截断超长章节（分类只需判断主导场景，前 4000 字足够）
        String content = chapterContent.length() > 4000
                ? chapterContent.substring(0, 4000) : chapterContent;

        String systemPrompt = promptService.render("scene-classify", Map.of(
                "chapterContent", content));

        var req = LlmRequest.builder()
                .taskType(LlmTaskType.GENERAL)
                .systemPrompt(systemPrompt)
                .userMessage("请输出该章的场景类型。")
                .temperature(0.1)
                .maxTokens(50)
                .build();

        try {
            var resp = gateway.complete(req);
            return SceneType.fromString(resp.getContent().trim());
        } catch (Exception e) {
            return SceneType.TRANSITION;
        }
    }
}
