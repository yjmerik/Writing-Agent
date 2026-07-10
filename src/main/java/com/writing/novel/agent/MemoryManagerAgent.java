package com.writing.novel.agent;

import com.writing.novel.domain.Summary;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmTaskType;
import com.writing.novel.memory.SummaryService;

/**
 * 记忆管理 Agent。把章节正文压缩为滚动摘要，控制上下文 token 用量。
 */
public class MemoryManagerAgent {

    private final LlmGateway gateway;
    private final SummaryService summaryService;

    public MemoryManagerAgent(LlmGateway gateway, SummaryService summaryService) {
        this.gateway = gateway;
        this.summaryService = summaryService;
    }

    /** 把 fromChapter 到 toChapter 的正文压缩为一条摘要 */
    public Summary summarize(String projectId, int fromChapter, int toChapter, String chaptersText) {
        String systemPrompt = """
                你是小说摘要专家。把给定的章节正文压缩为简洁的情节摘要。
                要求：保留关键事件、人物状态变化、伏笔进展；省略细节描写。
                输出 2-4 句话的摘要，不要加标题或格式标记。
                """;

        var req = LlmRequest.builder()
                .taskType(LlmTaskType.MEMORY_SUMMARY)
                .systemPrompt(systemPrompt)
                .userMessage("请压缩第" + fromChapter + "-" + toChapter + "章：\n" + chaptersText)
                .temperature(0.3)
                .maxTokens(500)
                .build();

        var resp = gateway.complete(req);
        Summary summary = new Summary(projectId, fromChapter, toChapter, resp.getContent().trim());
        summaryService.save(summary);
        return summary;
    }
}
