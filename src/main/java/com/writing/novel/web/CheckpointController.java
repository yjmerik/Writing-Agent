package com.writing.novel.web;

import com.writing.novel.domain.Checkpoint;
import com.writing.novel.orchestrator.NovelOrchestrator;
import com.writing.novel.service.CheckpointTargetService;
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

    public CheckpointController(NovelRepository repository, NovelOrchestrator orchestrator,
                                CheckpointTargetService checkpointTargetService) {
        this.repository = repository;
        this.orchestrator = orchestrator;
        this.checkpointTargetService = checkpointTargetService;
    }

    /** 列出待审核 checkpoint */
    @GetMapping
    public List<Checkpoint> list(@PathVariable String id) {
        return repository.findPendingCheckpoints(id);
    }

    /** 批准 checkpoint — 推进项目状态 */
    @PostMapping("/{checkpointId}/approve")
    public Map<String, Object> approve(@PathVariable String id, @PathVariable String checkpointId) {
        checkpointTargetService.requirePending(id, checkpointId);
        orchestrator.approveCheckpoint(id, checkpointId);
        return Map.of("status", "approved", "checkpointId", checkpointId);
    }

    /**
     * 拒绝 checkpoint — 带修改意见重写当前章节。
     * body: { "instructions": "修改意见" }
     */
    @PostMapping("/{checkpointId}/reject")
    public Map<String, Object> reject(@PathVariable String id, @PathVariable String checkpointId,
                                       @RequestBody Map<String, String> body) {
        var cp = checkpointTargetService.requirePending(id, checkpointId);
        String instructions = body.getOrDefault("instructions", "");

        // 如果是 AFTER_CHAPTER 类型，重写当前章节
        if (cp.getType() == Checkpoint.Type.AFTER_CHAPTER) {
            var project = repository.findProject(id).orElseThrow();
            int chapterIndex = project.getCurrentChapterIndex();
            orchestrator.rewriteChapter(id, chapterIndex, instructions);
        }

        cp.reject();
        repository.saveCheckpoint(cp);

        // 重新进入 PAUSED_AT_CHECKPOINT 等待再次审核
        return Map.of("status", "rejected", "checkpointId", checkpointId,
                "instructions", instructions);
    }

    /**
     * 修改 checkpoint — 带修改意见，但批准并继续。
     * body: { "instructions": "修改意见（记录但不阻塞）" }
     */
    @PostMapping("/{checkpointId}/modify")
    public Map<String, Object> modify(@PathVariable String id, @PathVariable String checkpointId,
                                       @RequestBody Map<String, String> body) {
        var cp = checkpointTargetService.requirePending(id, checkpointId);
        String instructions = body.getOrDefault("instructions", "");

        // 如果是 AFTER_CHAPTER，先重写再批准
        if (cp.getType() == Checkpoint.Type.AFTER_CHAPTER && !instructions.isBlank()) {
            var project = repository.findProject(id).orElseThrow();
            int chapterIndex = project.getCurrentChapterIndex();
            orchestrator.rewriteChapter(id, chapterIndex, instructions);
        }

        cp.modify();
        repository.saveCheckpoint(cp);
        orchestrator.step(id);

        return Map.of("status", "modified", "checkpointId", checkpointId,
                "instructions", instructions);
    }
}
