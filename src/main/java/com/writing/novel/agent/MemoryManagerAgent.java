package com.writing.novel.agent;

import com.writing.novel.domain.Summary;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmTaskType;
import com.writing.novel.llm.prompt.PromptTemplateService;
import com.writing.novel.memory.SummaryService;

import java.util.Map;

/**
 * 记忆管理 Agent。把章节正文压缩为滚动摘要，控制上下文 token 用量。
 */
public class MemoryManagerAgent {

    private final LlmGateway gateway;
    private final SummaryService summaryService;
    private final PromptTemplateService promptService;

    public MemoryManagerAgent(LlmGateway gateway, SummaryService summaryService,
                              PromptTemplateService promptService) {
        this.gateway = gateway;
        this.summaryService = summaryService;
        this.promptService = promptService;
    }

    /** 把 fromChapter 到 toChapter 的正文压缩为一条摘要 */
    public Summary summarize(String projectId, int fromChapter, int toChapter, String chaptersText) {
        Summary summary = draftSummary(projectId, fromChapter, toChapter, chaptersText);
        summaryService.save(summary);
        return summary;
    }

    /** Compute a summary without persisting it, for version-guarded derived writes. */
    public Summary draftSummary(String projectId, int fromChapter, int toChapter, String chaptersText) {
        String systemPrompt = promptService.render("memory-summary", Map.of());

        var req = LlmRequest.builder()
                .taskType(LlmTaskType.MEMORY_SUMMARY)
                .systemPrompt(systemPrompt)
                .userMessage("请压缩第" + fromChapter + "-" + toChapter + "章：\n" + chaptersText)
                .temperature(0.3)
                .maxTokens(1500) // p12-K4: 500→1500（卷摘要需要更多空间）
                .build();

        var resp = gateway.complete(req);
        return new Summary(projectId, fromChapter, toChapter, resp.getContent().trim());
    }
}
