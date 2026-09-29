package com.writing.novel.web;

import com.writing.novel.persistence.sqlite.ReaderReviewReportRepository;
import com.writing.novel.service.LongTaskService;
import com.writing.novel.spi.NovelRepository;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * NCS-138: 读者视角评审端点——异步 LongTask（评审可能耗时数分钟），结果持久化。
 *
 * <ul>
 *   <li>POST /api/projects/{id}/reader-review → 提交评审，返回 jobId（轮询 long-tasks），
 *       完成后 result 携带报告 JSON</li>
 *   <li>GET /api/projects/{id}/reader-review → 最新评审报告（含 JSON）</li>
 *   <li>GET /api/projects/{id}/reader-review/history → 评审历史列表</li>
 * </ul>
 * 只读：不产出 ContinuityIssue、不触发屏障/重写。
 */
@RestController
@RequestMapping("/api/projects/{id}/reader-review")
public class ReaderReviewController {

    private final com.writing.novel.agent.ReaderPerspectiveAgent readerAgent;
    private final ReaderReviewReportRepository reportRepository;
    private final LongTaskService longTasks;
    private final NovelRepository repository;

    public ReaderReviewController(com.writing.novel.agent.ReaderPerspectiveAgent readerAgent,
                                  ReaderReviewReportRepository reportRepository,
                                  LongTaskService longTasks,
                                  NovelRepository repository) {
        this.readerAgent = readerAgent;
        this.reportRepository = reportRepository;
        this.longTasks = longTasks;
        this.repository = repository;
    }

    /** 提交读者视角评审（异步） */
    @PostMapping
    public Map<String, Object> submit(@PathVariable String id) {
        repository.findProject(id)
                .orElseThrow(() -> new IllegalArgumentException("项目不存在: " + id));
        var chapters = repository.findChaptersByProject(id);
        if (chapters == null || chapters.isEmpty()) {
            throw new IllegalArgumentException("项目尚无章节，无法评审");
        }
        String jobId = longTasks.submit(id, "reader-review", "读者视角评审", () -> {
            var result = readerAgent.review(id);
            String reportId = UUID.randomUUID().toString();
            reportRepository.save(reportId, id, result.chaptersReviewed(),
                    result.totalChapters(), result.coveragePercent(), result.reportJson());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("reportId", reportId);
            payload.put("reportJson", result.reportJson());
            payload.put("chaptersReviewed", result.chaptersReviewed());
            payload.put("totalChapters", result.totalChapters());
            payload.put("coveragePercent", result.coveragePercent());
            return payload;
        });
        return Map.of("jobId", jobId, "kind", "reader-review");
    }

    /** 最新评审报告 */
    @GetMapping
    public Map<String, Object> latest(@PathVariable String id) {
        var report = reportRepository.latest(id);
        if (report == null) {
            throw new IllegalArgumentException("尚无评审报告，请先 POST /reader-review");
        }
        return report;
    }

    /** 评审历史 */
    @GetMapping("/history")
    public java.util.List<Map<String, Object>> history(@PathVariable String id) {
        return reportRepository.history(id, 20);
    }
}
