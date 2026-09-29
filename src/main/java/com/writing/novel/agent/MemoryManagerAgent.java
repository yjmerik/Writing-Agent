package com.writing.novel.agent;

import com.writing.novel.domain.Summary;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmTaskType;
import com.writing.novel.llm.prompt.PromptTemplateService;
import com.writing.novel.memory.SummaryService;
import com.writing.novel.memory.TokenBudget;

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

        // NCS-116: 卷摘要输入有界——长卷全文拼接不再直塞（此前输入不截断，超窗静默失败）
        final int INPUT_TOKEN_BUDGET = 24000;
        String bounded = chaptersText;
        if (TokenBudget.estimateTokens(chaptersText) > INPUT_TOKEN_BUDGET) {
            bounded = TokenBudget.truncateToTokens(chaptersText, INPUT_TOKEN_BUDGET)
                    + "\n…（输入已达摘要预算上限，早期内容已截断）";
        }

        var req = LlmRequest.builder()
                .taskType(LlmTaskType.MEMORY_SUMMARY)
                .systemPrompt(systemPrompt)
                .userMessage("请压缩第" + fromChapter + "-" + toChapter + "章：\n" + bounded)
                .temperature(0.3)
                .maxTokens(1500) // p12-K4: 500→1500（卷摘要需要更多空间）
                .build();

        var resp = gateway.complete(req);
        return new Summary(projectId, fromChapter, toChapter, resp.getContent().trim());
    }

    /**
     * NCS-116: 全书快照专用生成——此前复用 memory-summary 模板（纯文本摘要指令）却期望
     * JSON{mainlineProgress}，快照大概率从不更新且失败无事件。专用 book-snapshot 模板。
     */
    public String draftSnapshot(String previousSnapshot, String volumeSummary, String keyFacts) {
        String systemPrompt = promptService.render("book-snapshot", Map.of(
                "previousSnapshot", previousSnapshot == null ? "（暂无，首版）" : previousSnapshot,
                "volumeSummary", volumeSummary == null || volumeSummary.isBlank() ? "（本卷无摘要）" : volumeSummary,
                "keyFacts", keyFacts == null || keyFacts.isBlank() ? "（无）" : keyFacts));
        var req = LlmRequest.builder()
                .taskType(LlmTaskType.MEMORY_SUMMARY)
                .systemPrompt(systemPrompt)
                .userMessage("请输出全书最新快照 JSON。")
                .temperature(0.3)
                .maxTokens(1500)
                .build();
        return gateway.complete(req).getContent();
    }
}
