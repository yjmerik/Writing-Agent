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

    /** 批准 checkpoint — 推进项目状态 */
    @PostMapping("/{checkpointId}/approve")
    public Map<String, Object> approve(@PathVariable String id, @PathVariable String checkpointId) {
        return withRevisionWork(id, () -> {
            checkpointTargetService.requirePending(id, checkpointId);
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
     */
    @PostMapping("/{checkpointId}/modify")
    public Map<String, Object> modify(@PathVariable String id, @PathVariable String checkpointId,
                                       @RequestBody Map<String, String> body) {
        return withRevisionWork(id, () -> {
            var cp = checkpointTargetService.requirePending(id, checkpointId);
            String instructions = body.getOrDefault("instructions", "");

            // 连续性修复 checkpoint 只推进状态；其余类型先完成真实内容改写。
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

    private NovelOrchestrator.CheckpointRevisionResult reviseContentIfSupported(
            String projectId, Checkpoint checkpoint, String instructions) {
        return switch (checkpoint.getType()) {
            case AFTER_INITIAL_SETUP, AFTER_VOLUME_OUTLINE, AFTER_CHAPTER ->
                    orchestrator.reviseCheckpoint(projectId, checkpoint, instructions);
            case ON_CONTINUITY_FIX -> null;
        };
    }

    private <T> T withRevisionWork(String projectId, java.util.function.Supplier<T> work) {
        String workId = "checkpoint-revision-" + java.util.UUID.randomUUID();
        writingTaskExecutor.beginWork(projectId, workId);
        try {
            return work.get();
        } finally {
            writingTaskExecutor.endWork(projectId, workId);
        }
    }
}
