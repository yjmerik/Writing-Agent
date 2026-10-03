package com.writing.novel.web;

import com.writing.novel.domain.ChapterApproval;
import com.writing.novel.service.WritingTaskExecutor;
import com.writing.novel.spi.NovelRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * p36-04：携带版本的审阅上下文与批准接口。
 * 客户端必须显式携带已读稿件身份与待审集合指纹，服务端不补填；
 * 身份/待办冲突 409 并返回当前权威状态供刷新；不自动批准未读新稿。
 */
@RestController
@RequestMapping("/api/projects/{id}/chapters/{chapterIndex}")
public class ReviewDecisionController {

    private final NovelRepository repository;
    private final WritingTaskExecutor writingTaskExecutor;
    private final com.writing.novel.orchestrator.NovelOrchestrator recheckService;
    private final com.writing.novel.service.CheckWaiverService checkWaiverService;
    private final com.writing.novel.service.LongTaskService longTasks;

    @Autowired
    public ReviewDecisionController(NovelRepository repository, WritingTaskExecutor writingTaskExecutor,
                                    com.writing.novel.orchestrator.NovelOrchestrator recheckService,
                                    com.writing.novel.service.CheckWaiverService checkWaiverService,
                                    com.writing.novel.service.LongTaskService longTasks) {
        this.repository = repository;
        this.writingTaskExecutor = writingTaskExecutor;
        this.recheckService = recheckService;
        this.checkWaiverService = checkWaiverService;
        this.longTasks = longTasks;
    }

    /** 已读正文身份、reviewSetHash、报告/待办/裁决/批准的一致快照。 */
    @GetMapping("/review-context")
    public ChapterApproval.ReviewContext reviewContext(@PathVariable String id,
                                                       @PathVariable int chapterIndex) {
        return repository.readReviewContext(id, chapterIndex);
    }

    /**
     * p38-11（审计 P1-4）：按当前稿重新检查——必须携带 identity；不符 409。
     * 检查产物绑定该稿身份，不会冒充更新稿的报告。
     */
    @PostMapping("/recheck")
    public Map<String, Object> recheck(@PathVariable String id,
                                       @PathVariable int chapterIndex,
                                       @RequestBody Map<String, Object> body) {
        Object identityRaw = body == null ? null : body.get("identity");
        if (!(identityRaw instanceof Map<?, ?> identityMap)) {
            throw new IllegalArgumentException("缺少 identity（服务端不补填）");
        }
        String identityProjectId = stringField(identityMap, "projectId");
        if (!id.equals(identityProjectId)) {
            throw new IllegalArgumentException("identity.projectId 与路径项目不符");
        }
        Integer idChapter = intField(identityMap, "chapterIndex");
        if (idChapter == null || idChapter != chapterIndex) {
            throw new IllegalArgumentException("identity.chapterIndex 与路径章节不符");
        }
        Integer version = intField(identityMap, "version");
        String contentHash = stringField(identityMap, "contentHash");
        if (version == null || contentHash == null || contentHash.isBlank()) {
            throw new IllegalArgumentException("缺少 identity.version/contentHash");
        }
        var identity = new com.writing.novel.domain.ChapterApproval.Identity(
                id, chapterIndex, version, contentHash);
        // NCS-087: 可选 checkers 路由（缺省仅 continuity，向后兼容）；
        // correction-verification 不可重跑，编排器 409 明示担责豁免出路。
        List<String> checkers = null;
        Object checkersRaw = body == null ? null : body.get("checkers");
        if (checkersRaw instanceof List<?> list) {
            checkers = list.stream().filter(String.class::isInstance)
                    .map(String.class::cast).toList();
        }
        // NCS-091: recheck 的 LLM 调用携带 projectId（成本归集，照 NCS-086 模式）
        com.writing.novel.telemetry.EventContext.setProjectId(id);
        String workId = "recheck-" + UUID.randomUUID();
        if (writingTaskExecutor != null) {
            writingTaskExecutor.beginWork(id, workId);
        }
        try {
            var report = recheckService.recheckChapter(id, chapterIndex, identity, checkers);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("chapterIndex", chapterIndex);
            result.put("chapterVersion", version);
            result.put("grade", report.getGrade());
            result.put("reportCurrent", report.isReportCurrent());
            result.put("issues", report.getIssues().size());
            return result;
        } finally {
            com.writing.novel.telemetry.EventContext.clear();
            if (writingTaskExecutor != null) {
                writingTaskExecutor.endWork(id, workId);
            }
        }
    }

    /**
     * NCS-088：未查清检查信封的作者担责豁免——针对当前稿，必须携带 identity 与不可空 reason；
     * 已有有效结论（SUCCESS/VALID_EMPTY）的检查拒绝豁免。豁免行不可变追加，reason 记入台账可审计。
     */
    @PostMapping("/check-waiver")
    public Map<String, Object> checkWaiver(@PathVariable String id,
                                           @PathVariable int chapterIndex,
                                           @RequestBody Map<String, Object> body) {
        Object identityRaw = body == null ? null : body.get("identity");
        if (!(identityRaw instanceof Map<?, ?> identityMap)) {
            throw new IllegalArgumentException("缺少 identity（服务端不补填）");
        }
        String identityProjectId = stringField(identityMap, "projectId");
        if (!id.equals(identityProjectId)) {
            throw new IllegalArgumentException("identity.projectId 与路径项目不符");
        }
        Integer idChapter = intField(identityMap, "chapterIndex");
        if (idChapter == null || idChapter != chapterIndex) {
            throw new IllegalArgumentException("identity.chapterIndex 与路径章节不符");
        }
        Integer version = intField(identityMap, "version");
        String contentHash = stringField(identityMap, "contentHash");
        if (version == null || contentHash == null || contentHash.isBlank()) {
            throw new IllegalArgumentException("缺少 identity.version/contentHash");
        }
        String checker = stringField(body, "checker");
        if (checker == null || checker.isBlank()) {
            throw new IllegalArgumentException("缺少 checker");
        }
        String reason = stringField(body, "reason");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("豁免必须说明理由（记入处置台账，作者可查）");
        }
        var identity = new com.writing.novel.domain.ChapterApproval.Identity(
                id, chapterIndex, version, contentHash);
        String workId = "check-waiver-" + UUID.randomUUID();
        if (writingTaskExecutor != null) {
            writingTaskExecutor.beginWork(id, workId);
        }
        try {
            var chapter = repository.findChapterByIndex(id, chapterIndex)
                    .orElseThrow(() -> new IllegalArgumentException("章节不存在: " + chapterIndex));
            var currentIdentity = com.writing.novel.domain.ChapterApproval.Identity.of(chapter);
            if (!currentIdentity.equals(identity)) {
                throw new NovelRepository.ReviewConflictException(
                        NovelRepository.ReviewConflictException.Reason.IDENTITY_STALE,
                        "担责豁免必须针对当前稿", currentIdentity, null);
            }
            checkWaiverService.waive(id, chapterIndex, checker, reason);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("chapterIndex", chapterIndex);
            result.put("chapterVersion", version);
            result.put("checker", checker);
            result.put("status", "AUTHOR_WAIVED");
            return result;
        } finally {
            if (writingTaskExecutor != null) {
                writingTaskExecutor.endWork(id, workId);
            }
        }
    }

    /** NCS-094: 重检异步化——立即返回 jobId，前端轮询 long-tasks/{jobId}。 */
    @PostMapping("/recheck-async")
    public Map<String, Object> recheckAsync(@PathVariable String id,
                                            @PathVariable int chapterIndex,
                                            @RequestBody Map<String, Object> body) {
        Object identityRaw = body == null ? null : body.get("identity");
        if (!(identityRaw instanceof Map<?, ?> identityMap)) {
            throw new IllegalArgumentException("缺少 identity（服务端不补填）");
        }
        String identityProjectId = stringField(identityMap, "projectId");
        if (!id.equals(identityProjectId)) {
            throw new IllegalArgumentException("identity.projectId 与路径项目不符");
        }
        Integer idChapter = intField(identityMap, "chapterIndex");
        if (idChapter == null || idChapter != chapterIndex) {
            throw new IllegalArgumentException("identity.chapterIndex 与路径章节不符");
        }
        Integer version = intField(identityMap, "version");
        String contentHash = stringField(identityMap, "contentHash");
        if (version == null || contentHash == null || contentHash.isBlank()) {
            throw new IllegalArgumentException("缺少 identity.version/contentHash");
        }
        var identity = new com.writing.novel.domain.ChapterApproval.Identity(
                id, chapterIndex, version, contentHash);
        List<String> checkers = null;
        Object checkersRaw = body.get("checkers");
        if (checkersRaw instanceof List<?> list) {
            checkers = list.stream().filter(String.class::isInstance)
                    .map(String.class::cast).toList();
        }
        final List<String> routedCheckers = checkers;
        String jobId = longTasks.submit(id, "recheck", "连续性补查",
                () -> recheckService.recheckChapter(id, chapterIndex, identity, routedCheckers));
        return Map.of("jobId", jobId, "kind", "recheck");
    }

    /** NCS-094: 长任务状态查询（项目隔离；不存在/不匹配返回 404）。 */
    @GetMapping("/long-tasks/{jobId}")
    public com.writing.novel.service.LongTaskService.JobState longTask(@PathVariable String id,
                                                                       @PathVariable String jobId) {
        return longTasks.get(id, jobId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在或已过期: " + jobId));
    }

    /** 版本化批准：identity + reviewSetHash + idempotencyKey + reason → ChapterApproval.Result。 */
    @PostMapping("/review-decisions")
    public ChapterApproval.Result reviewDecision(@PathVariable String id,
                                                 @PathVariable int chapterIndex,
                                                 @RequestBody Map<String, Object> body) {
        ChapterApproval.Request request = parseRequest(id, chapterIndex, body);
        String workId = "review-decision-" + UUID.randomUUID();
        ChapterApproval.Result result;
        if (writingTaskExecutor != null) {
            writingTaskExecutor.beginWork(id, workId);
        }
        // NCS-172: 批准操作入 session 日志（workId 作 sessionId）
        com.writing.novel.telemetry.EventContext.setSession(workId, "review-decision");
        com.writing.novel.telemetry.SessionLog.op("approval-requested", java.util.Map.of(
                "chapterIndex", chapterIndex, "workId", workId));
        try {
            result = repository.approveChapter(request);
            // NCS-088：批准 = 作者已读当前稿并担责放行——新批准（非幂等重放）自动把该章
            // 未查清信封追加 AUTHOR_WAIVED 行，门禁不再被无出口的 unknown 卡死。
            if (!result.replayed() && checkWaiverService != null) {
                checkWaiverService.waiveForApproval(id, chapterIndex, result.approvalId());
            }
        } catch (IllegalStateException e) {
            // NCS-081: 满足性门禁失败（未解决 HIGH / 作者要求未落实）→ 结构化 409 + code，
            // 前端据此常驻展示阻塞原因（此前裸 {error} 只换来 3 秒 toast）
            throw new ApprovalBlockedException(e.getMessage(), e);
        } finally {
            if (writingTaskExecutor != null) {
                writingTaskExecutor.endWork(id, workId);
            }
            com.writing.novel.telemetry.EventContext.clear();
        }
        com.writing.novel.telemetry.SessionLog.op("approval-settled", java.util.Map.of(
                "chapterIndex", chapterIndex, "replayed", result.replayed(),
                "approvalId", result.approvalId()));
        // NCS-146（旅程三 F-4）：批准 = 复核完成——无剩余待审时自动继续协作，
        // 不再要求作者二次点击「继续协作」；须在工作权释放后执行。
        if (!result.replayed()) {
            autoResumeIfUnblocked(id);
        }
        return result;
    }

    /** NCS-146：批准后无剩余阻塞且有暂停中的协作任务时自动恢复；失败不回滚批准。 */
    private void autoResumeIfUnblocked(String projectId) {
        if (writingTaskExecutor == null) return;
        try {
            if (!repository.findPendingCheckpoints(projectId).isEmpty()) return;
            if (!repository.findActiveReviewBarriers(projectId).isEmpty()) return;
            var previous = writingTaskExecutor.latestCollaborativeTask(projectId);
            if (previous == null || previous.getStatus() != com.writing.novel.domain.WritingTask.Status.PAUSED) {
                return;
            }
            // 阻塞已解除，过期错误信息不再展示
            previous.setErrorMessage(null);
            repository.saveWritingTask(previous);
            writingTaskExecutor.startCollaborative(projectId, null);
            com.writing.novel.telemetry.EventLog.emit("APPROVE_AUTO_RESUMED",
                    Map.of("projectId", projectId));
        } catch (Exception e) {
            com.writing.novel.telemetry.EventLog.emit("APPROVE_AUTO_RESUME_FAILED",
                    Map.of("projectId", projectId,
                            "message", com.writing.novel.telemetry.EventLog.truncate(
                                    String.valueOf(e.getMessage()), 160)));
        }
    }

    /** 缺字段即 400：服务端不会补填客户端缺失的版本或指纹。 */
    private ChapterApproval.Request parseRequest(String projectId, int chapterIndex,
                                                 Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            throw new IllegalArgumentException("批准请求体为空");
        }
        Object identityRaw = body.get("identity");
        if (!(identityRaw instanceof Map<?, ?> identityMap)) {
            throw new IllegalArgumentException("缺少 identity（projectId/chapterIndex/version/contentHash）");
        }
        Map<?, ?> identity = identityMap;

        String identityProjectId = stringField(identity, "projectId");
        if (identityProjectId == null || identityProjectId.isBlank()) {
            throw new IllegalArgumentException("identity.projectId 不能为空");
        }
        if (!projectId.equals(identityProjectId)) {
            throw new IllegalArgumentException("identity.projectId 与路径项目不符，不能跨项目批准");
        }
        Integer identityChapterIndex = intField(identity, "chapterIndex");
        if (identityChapterIndex == null || identityChapterIndex != chapterIndex) {
            throw new IllegalArgumentException("identity.chapterIndex 与路径章节不符");
        }
        Integer version = intField(identity, "version");
        if (version == null) {
            throw new IllegalArgumentException("缺少 identity.version——服务端不补填版本");
        }
        String contentHash = stringField(identity, "contentHash");
        if (contentHash == null || contentHash.isBlank()) {
            throw new IllegalArgumentException("缺少 identity.contentHash——服务端不补填正文指纹");
        }
        String reviewSetHash = stringField(body, "reviewSetHash");
        if (reviewSetHash == null || reviewSetHash.isBlank()) {
            throw new IllegalArgumentException("缺少 reviewSetHash——服务端不补填待审集合指纹");
        }
        String idempotencyKey = stringField(body, "idempotencyKey");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("缺少 idempotencyKey——同动作重试必须复用同一键");
        }
        String reason = stringField(body, "reason");
        return new ChapterApproval.Request(
                new ChapterApproval.Identity(identityProjectId, identityChapterIndex, version, contentHash),
                reviewSetHash, idempotencyKey, reason);
    }

    private String stringField(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value instanceof String s ? s : null;
    }

    private Integer intField(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /** 版本/待办冲突 → 409 + 当前权威身份与待审集合指纹，前端据此刷新而非自动重试。 */
    @ExceptionHandler(NovelRepository.ReviewConflictException.class)
    public ResponseEntity<Map<String, Object>> handleReviewConflict(
            NovelRepository.ReviewConflictException e) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("error", e.getMessage());
        payload.put("code", "REVIEW_CONFLICT");
        payload.put("reason", e.getReason() == null ? null : e.getReason().name());
        if (e.getCurrentIdentity() != null) {
            payload.put("currentIdentity", Map.of(
                    "projectId", e.getCurrentIdentity().projectId(),
                    "chapterIndex", e.getCurrentIdentity().chapterIndex(),
                    "version", e.getCurrentIdentity().version(),
                    "contentHash", e.getCurrentIdentity().contentHash()));
        }
        payload.put("currentReviewSetHash", e.getCurrentReviewSetHash());
        return ResponseEntity.status(409).body(payload);
    }

    /** NCS-081: 批准满足性门禁失败 → 409 + code=APPROVAL_BLOCKED，前端常驻展示原因。 */
    @ExceptionHandler(ApprovalBlockedException.class)
    public ResponseEntity<Map<String, Object>> handleApprovalBlocked(ApprovalBlockedException e) {
        return ResponseEntity.status(409).body(Map.of(
                "error", e.getMessage() == null ? "批准被门禁阻塞" : e.getMessage(),
                "code", "APPROVAL_BLOCKED"));
    }
}
