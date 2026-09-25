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

    @Autowired
    public ReviewDecisionController(NovelRepository repository, WritingTaskExecutor writingTaskExecutor,
                                    com.writing.novel.orchestrator.NovelOrchestrator recheckService) {
        this.repository = repository;
        this.writingTaskExecutor = writingTaskExecutor;
        this.recheckService = recheckService;
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
        String workId = "recheck-" + UUID.randomUUID();
        if (writingTaskExecutor != null) {
            writingTaskExecutor.beginWork(id, workId);
        }
        try {
            var report = recheckService.recheckChapter(id, chapterIndex, identity);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("chapterIndex", chapterIndex);
            result.put("chapterVersion", version);
            result.put("grade", report.getGrade());
            result.put("reportCurrent", report.isReportCurrent());
            result.put("issues", report.getIssues().size());
            return result;
        } finally {
            if (writingTaskExecutor != null) {
                writingTaskExecutor.endWork(id, workId);
            }
        }
    }

    /** 版本化批准：identity + reviewSetHash + idempotencyKey + reason → ChapterApproval.Result。 */
    @PostMapping("/review-decisions")
    public ChapterApproval.Result reviewDecision(@PathVariable String id,
                                                 @PathVariable int chapterIndex,
                                                 @RequestBody Map<String, Object> body) {
        ChapterApproval.Request request = parseRequest(id, chapterIndex, body);
        String workId = "review-decision-" + UUID.randomUUID();
        if (writingTaskExecutor != null) {
            writingTaskExecutor.beginWork(id, workId);
        }
        try {
            return repository.approveChapter(request);
        } catch (IllegalStateException e) {
            // NCS-081: 满足性门禁失败（未解决 HIGH / 作者要求未落实）→ 结构化 409 + code，
            // 前端据此常驻展示阻塞原因（此前裸 {error} 只换来 3 秒 toast）
            throw new ApprovalBlockedException(e.getMessage(), e);
        } finally {
            if (writingTaskExecutor != null) {
                writingTaskExecutor.endWork(id, workId);
            }
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
