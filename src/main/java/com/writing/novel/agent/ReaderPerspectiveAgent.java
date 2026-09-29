package com.writing.novel.agent;

import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.LlmTaskType;
import com.writing.novel.llm.prompt.PromptTemplateService;
import com.writing.novel.spi.NovelRepository;
import com.writing.novel.telemetry.EventLog;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 读者视角评审 Agent——像真实读者一样读完全书已有章节，从三个维度给出整体反馈：
 * 剧情（主线/节奏/悬念）、连续性（读者读感，非台账核对）、艺术性（文字/意象/对话）。
 *
 * <p><b>只读性</b>：不产出 ContinuityIssue、不落 barrier、不触发重写——
 * 产出独立报告（reader_review_report 表），供作者在继续写作前获得读者外部视角。
 * 与工程检测器（ContinuityChecker/ConsistencyGuard）互补而非替代。
 *
 * <p>采样策略：首末章完整节选（基调+完成度）、中间分层采样（≥20%），
 * 附覆盖率声明（复用 NCS-126 经验：采样不足时 style/连续性评分不可信）。
 */
@org.springframework.stereotype.Component
public class ReaderPerspectiveAgent {

    private final LlmGateway gateway;
    private final NovelRepository repository;
    private final PromptTemplateService promptService;

    public ReaderPerspectiveAgent(LlmGateway gateway, NovelRepository repository,
                                  PromptTemplateService promptService) {
        this.gateway = gateway;
        this.repository = repository;
        this.promptService = promptService;
    }

    /** 评审结果：原始 JSON 字符串（已剥围栏），由 web 层直接返回前端解析 */
    public record ReviewResult(String reportJson, int chaptersReviewed, int totalChapters,
                               double coveragePercent) {}

    /**
     * 对项目已有章节执行读者视角评审。
     * @param maxSampledChapters 最多采样的章数（防 token 爆炸；实际覆盖 ≥20% 或全量）
     */
    public ReviewResult review(String projectId) {
        var chapters = repository.findChaptersByProject(projectId);
        if (chapters.isEmpty()) {
            throw new IllegalStateException("项目尚无章节，无法评审");
        }
        chapters.sort(Comparator.comparingInt(c -> c.getChapterIndex()));

        int total = chapters.size();
        int target = Math.min(10, Math.max(3, (total + 4) / 5));
        int stride = Math.max(1, total / target);

        StringBuilder picked = new StringBuilder();
        int pickedCount = 0;
        for (int i = 0; i < total && pickedCount < target; i += stride) {
            appendChapter(picked, chapters.get(i), i == 0 || i + stride >= total);
            pickedCount++;
        }
        // 末章保底
        if (pickedCount > 0 && (total - 1) % stride != 0 && pickedCount < target + 1) {
            appendChapter(picked, chapters.get(total - 1), true);
            pickedCount++;
        }

        double coverage = Math.min(100.0, 100.0 * pickedCount / total);
        String scopeNote = "（评审证据覆盖 " + pickedCount + "/" + total + " 章，覆盖率 "
                + Math.round(coverage) + "%。未采样章节不在本次评价范围内，评语中不得对其下结论。）";

        String systemPrompt = promptService.render("reader-perspective", Map.of(
                "chaptersText", picked.toString(),
                "scopeNote", scopeNote));

        var req = LlmRequest.builder()
                .taskType(LlmTaskType.GENERAL)
                .systemPrompt(systemPrompt)
                .userMessage("请给出你的读者视角评审 JSON。")
                .temperature(0.4)
                .maxTokens(4000)
                .build();

        var resp = gateway.complete(req);
        String json = com.writing.novel.llm.LlmJson.stripFences(resp.getContent());
        // 只读性保障：解析失败抛异常（fail-loud），不产出任何 ContinuityIssue
        com.writing.novel.llm.LlmJson.Parsed parsed = com.writing.novel.llm.LlmJson.parse(json);
        if (!parsed.ok()) {
            EventLog.emit("READER_REVIEW_PARSE_FAILED", Map.of(
                    "projectId", projectId,
                    "reason", EventLog.truncate(parsed.reason(), 200)));
            throw new IllegalStateException("读者评审输出不可解析: " + parsed.reason());
        }

        EventLog.emit("READER_REVIEW_COMPLETED", Map.of(
                "projectId", projectId,
                "chaptersReviewed", pickedCount,
                "totalChapters", total,
                "truncated", resp.isTruncated()));
        return new ReviewResult(json, pickedCount, total, coverage);
    }

    /** 单章节选：首末章取头 1200+尾 500 字，中间章取头 600+尾 300 字 */
    private static void appendChapter(StringBuilder sb, com.writing.novel.domain.Chapter ch, boolean keyChapter) {
        String content = ch.getContent() == null ? "" : ch.getContent();
        int headLen = keyChapter ? 1200 : 600;
        int tailLen = keyChapter ? 500 : 300;
        String head = content.length() > headLen ? content.substring(0, headLen) : content;
        String tail = content.length() > headLen + tailLen
                ? "……" + content.substring(content.length() - tailLen) : "";
        sb.append("--- 第").append(ch.getChapterIndex()).append("章 ")
                .append(ch.getTitle()).append(" ---\n")
                .append(head).append(tail).append("\n\n");
    }
}
