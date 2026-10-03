package com.writing.novel.web;

import com.writing.novel.domain.Checkpoint;
import com.writing.novel.orchestrator.NovelOrchestrator;
import com.writing.novel.service.CheckpointTargetService;
import com.writing.novel.service.WritingTaskExecutor;
import com.writing.novel.spi.NovelRepository;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Checkpoint 审核 REST API。
 * 支持列出/批准/拒绝/修改 checkpoint。
 */
@RestController
@RequestMapping("/api/projects/{id}/checkpoints")
public class CheckpointController {

    private final NovelRepository repository;
    private final NovelOrchestrator orchestrator;
    private final CheckpointTargetService checkpointTargetService;
    private final WritingTaskExecutor writingTaskExecutor;

    public CheckpointController(NovelRepository repository, NovelOrchestrator orchestrator,
                                CheckpointTargetService checkpointTargetService,
                                WritingTaskExecutor writingTaskExecutor) {
        this.repository = repository;
        this.orchestrator = orchestrator;
        this.checkpointTargetService = checkpointTargetService;
        this.writingTaskExecutor = writingTaskExecutor;
    }

    /** 列出待审核 checkpoint */
    @GetMapping
    public List<Checkpoint> list(@PathVariable String id) {
        return repository.findPendingCheckpoints(id);
    }

    /** 批准 checkpoint — 推进项目状态（p36-06: 章节类审核点不得无版本放行） */
    @PostMapping("/{checkpointId}/approve")
    public Map<String, Object> approve(@PathVariable String id, @PathVariable String checkpointId) {
        return withRevisionWork(id, () -> {
            var cp = checkpointTargetService.requirePending(id, checkpointId);
            rejectChapterClassApproval(cp);
            orchestrator.approveCheckpoint(id, checkpointId);
            return Map.of("status", "approved", "checkpointId", checkpointId);
        });
    }

    /**
     * 拒绝 checkpoint — 带修改意见重写当前章节。
     * body: { "instructions": "修改意见" }
     */
    @PostMapping("/{checkpointId}/reject")
    public Map<String, Object> reject(@PathVariable String id, @PathVariable String checkpointId,
                                       @RequestBody Map<String, String> body) {
        return withRevisionWork(id, () -> {
            var cp = checkpointTargetService.requirePending(id, checkpointId);
            String instructions = body.getOrDefault("instructions", "");

            // 连续性修复 checkpoint 保留原有状态型处理；其余类型先完成真实内容改写。
            NovelOrchestrator.CheckpointRevisionResult revision = reviseContentIfSupported(id, cp, instructions);

            cp.reject();
            repository.saveCheckpoint(cp);

            // NCS-168: reject 后重开一轮 PENDING 检查点——否则检查点终态、作者无法再次修订
            if (cp.getType() != Checkpoint.Type.ON_CONTINUITY_FIX) {
                orchestrator.reopenCheckpointAfterRevision(id, cp);
            }

            // 重新进入 PAUSED_AT_CHECKPOINT 等待再次审核
            if (revision == null) {
                return Map.of("status", "rejected", "checkpointId", checkpointId,
                        "instructions", instructions);
            }
            return Map.of("status", "rejected", "checkpointId", checkpointId,
                    "instructions", instructions, "revision", revision);
        });
    }

    /**
     * 修改 checkpoint — 带修改意见，但批准并继续。
     * body: { "instructions": "修改意见（记录但不阻塞）" }
     * p36-06: 章节类审核点保留重写动作但不再默认批准——checkpoint 回到待审，
     * 由作者通过版本化「批准当前稿」放行；ON_CONTINUITY_FIX 无法改写且不得放行，直接 409。
     */
    @PostMapping("/{checkpointId}/modify")
    public Map<String, Object> modify(@PathVariable String id, @PathVariable String checkpointId,
                                       @RequestBody Map<String, String> body) {
        return withRevisionWork(id, () -> {
            var cp = checkpointTargetService.requirePending(id, checkpointId);
            String instructions = body.getOrDefault("instructions", "");

            if (cp.getType() == Checkpoint.Type.AFTER_CHAPTER) {
                NovelOrchestrator.CheckpointRevisionResult revision =
                        reviseContentIfSupported(id, cp, instructions);
                if (revision == null) {
                    return Map.of("status", "modified_pending_review", "checkpointId", checkpointId,
                            "instructions", instructions);
                }
                return Map.of("status", "modified_pending_review", "checkpointId", checkpointId,
                        "instructions", instructions, "revision", revision);
            }
            if (cp.getType() == Checkpoint.Type.ON_CONTINUITY_FIX) {
                throw new IllegalStateException(
                        "连续性修复审核点已改用版本化批准：请修订正文后通过「批准当前稿」放行");
            }

            // 非章节类（初始设定/卷计划）保持原流程：重写 + 批准推进
            NovelOrchestrator.CheckpointRevisionResult revision = reviseContentIfSupported(id, cp, instructions);

            // 项目推进成功后才处理 checkpoint；推进异常仍保留 PENDING，便于用户重试。
            orchestrator.resumeAfterCheckpointRevision(id, cp);
            cp.modify();
            repository.saveCheckpoint(cp);

            if (revision == null) {
                return Map.of("status", "modified", "checkpointId", checkpointId,
                        "instructions", instructions);
            }
            return Map.of("status", "modified", "checkpointId", checkpointId,
                    "instructions", instructions, "revision", revision);
        });
    }

    /** p36-06: 章节类审核点的批准必须走版本化 review-decisions（绑定已读稿件身份）。 */
    private void rejectChapterClassApproval(Checkpoint checkpoint) {
        if (checkpoint.getType() == Checkpoint.Type.AFTER_CHAPTER
                || checkpoint.getType() == Checkpoint.Type.ON_CONTINUITY_FIX) {
            throw new IllegalStateException(
                    "章节类审核点已改用版本化批准：请刷新审阅，并通过「批准当前稿」（review-decisions）放行");
        }
    }

    private NovelOrchestrator.CheckpointRevisionResult reviseContentIfSupported(
            String projectId, Checkpoint checkpoint, String instructions) {
        return switch (checkpoint.getType()) {
            case AFTER_INITIAL_SETUP, AFTER_VOLUME_OUTLINE, AFTER_CHAPTER ->
                    orchestrator.reviseCheckpoint(projectId, checkpoint, instructions);
            case ON_CONTINUITY_FIX -> null;
        };
    }

    // ===== NCS-154: 人物卡修订预览-确认链（确认前不落库） =====

    /** 当前待确认的人物卡修订预览（无则空 cards） */
    @GetMapping("/character-revisions")
    public Map<String, Object> pendingCharacterRevisions(@PathVariable String id) {
        requireProject(id);
        return orchestrator.characterRevisionPreviews().find(id)
                .<Map<String, Object>>map(p -> Map.of(
                        "previewId", p.previewId(), "cards", p.cards()))
                .orElseGet(() -> Map.of("cards", List.of()));
    }

    /** 确认：合并卡按原卡 id 落库，清空预览 */
    @PostMapping("/character-revisions/{previewId}/confirm")
    public Map<String, Object> confirmCharacterRevisions(@PathVariable String id,
                                                         @PathVariable String previewId) {
        requireProject(id);
        String summary = orchestrator.characterRevisionPreviews().confirm(id, previewId);
        return Map.of("applied", true, "summary", summary);
    }

    /** 放弃：清空预览，库中零变动 */
    @PostMapping("/character-revisions/{previewId}/discard")
    public Map<String, Object> discardCharacterRevisions(@PathVariable String id,
                                                         @PathVariable String previewId) {
        requireProject(id);
        orchestrator.characterRevisionPreviews().discard(id, previewId);
        return Map.of("applied", false);
    }

    private void requireProject(String projectId) {
        if (repository.findProject(projectId).isEmpty()) {
            throw new IllegalArgumentException("项目不存在: " + projectId);
        }
    }

    private <T> T withRevisionWork(String projectId, java.util.function.Supplier<T> work) {
        // NCS-091: checkpoint 修订（重写初始设定/卷计划/章节）的 LLM 调用携带 projectId
        com.writing.novel.telemetry.EventContext.setProjectId(projectId);
        String workId = "checkpoint-revision-" + java.util.UUID.randomUUID();
        writingTaskExecutor.beginWork(projectId, workId);
        // NCS-172: 修订操作入 session 日志
        com.writing.novel.telemetry.EventContext.setSession(workId, "checkpoint-revision");
        com.writing.novel.telemetry.SessionLog.op("checkpoint-revision-started", java.util.Map.of("workId", workId));
        try {
            return work.get();
        } finally {
            com.writing.novel.telemetry.SessionLog.op("checkpoint-revision-finished", java.util.Map.of("workId", workId));
            com.writing.novel.telemetry.EventContext.clear();
            writingTaskExecutor.endWork(projectId, workId);
        }
    }
}
