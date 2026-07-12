package com.writing.novel.agent;

import com.writing.novel.llm.prompt.PromptTemplateService;

/**
 * 测试辅助：创建一个真实的 PromptTemplateService（已加载 prompts/*.st 模板），
 * 供测试中 new Agent(...) 传入，避免每个 Agent 内部做 null 检查。
 */
public final class TestPromptHelper {

    private TestPromptHelper() {}

    public static PromptTemplateService create() {
        PromptTemplateService svc = new PromptTemplateService();
        try {
            svc.loadTemplates();
        } catch (Exception e) {
            throw new RuntimeException("加载 prompt 模板失败", e);
        }
        return svc;
    }
}
