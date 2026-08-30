package com.writing.novel.web;

import com.writing.novel.agent.QualityEvaluationAgent;
import com.writing.novel.domain.NovelState;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.WritingTask;
import com.writing.novel.orchestrator.NovelOrchestrator;
import com.writing.novel.orchestrator.ProjectService;
import com.writing.novel.service.WritingTaskExecutor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/projects/{id}")
public class OrchestratorController {

    private final NovelOrchestrator orchestrator;
    private final ProjectService projectService;
    private final QualityEvaluationAgent qualityAgent;
    private final WritingTaskExecutor writingTaskExecutor;

    public OrchestratorController(NovelOrchestrator orchestrator,
                                   ProjectService projectService,
                                   QualityEvaluationAgent qualityAgent,
                                   WritingTaskExecutor writingTaskExecutor) {
        this.orchestrator = orchestrator;
        this.projectService = projectService;
        this.qualityAgent = qualityAgent;
        this.writingTaskExecutor = writingTaskExecutor;
    }

    @PostMapping("/run")
    public Map<String, Object> run(@PathVariable String id) {
        Project project = projectService.find(id).orElseThrow();
        if (project.getState() == NovelState.CONCEIVING) {
            WritingTask task = writingTaskExecutor.startConceiving(id);
            return Map.of(
                    "projectId", id,
                    "taskId", task.getId(),
                    "type", task.getType().name(),
                    "status", task.getStatus().name()
            );
        }
        // p20-06: 同步 step 也是写作入口——经协调器占坑，活动期冲突拒绝（409）
        String workId = "step-" + java.util.UUID.randomUUID();
        writingTaskExecutor.beginWork(id, workId);
        try {
            orchestrator.step(id);
        } finally {
            writingTaskExecutor.endWork(id, workId);
        }
        return status(id);
    }

    @PostMapping("/pause")
    public Map<String, Object> pause(@PathVariable String id) {
        orchestrator.pause(id);
        writingTaskExecutor.cancelByProject(id);
        return status(id);
    }

    @PostMapping("/resume")
    public Map<String, Object> resume(@PathVariable String id) {
        // p20-06: resume 可推进到写章——经协调器占坑，活动期冲突拒绝（409）
        String workId = "step-" + java.util.UUID.randomUUID();
        writingTaskExecutor.beginWork(id, workId);
        try {
            orchestrator.step(id);
        } finally {
            writingTaskExecutor.endWork(id, workId);
        }
        return status(id);
    }

    @GetMapping("/status")
    public Map<String, Object> status(@PathVariable String id) {
        Project p = projectService.find(id).orElseThrow();
        return Map.of(
                "projectId", id,
                "state", p.getState().name(),
                "mode", p.getMode().name(),
                "currentChapter", p.getCurrentChapterIndex(),
                "totalWords", orchestrator.getTotalWordCount(id)
        );
    }

    /**
     * p12-K2: 异步连续写作——启动后台任务，立即返回 taskId。
     * 前端轮询 GET /writing-tasks/{taskId} 查进度。
     */
    @PostMapping("/write-all")
    public Map<String, Object> writeAll(@PathVariable String id,
                                         @RequestParam(defaultValue = "50") int maxChapters,
                                         @RequestParam(required = false) String instruction) {
        WritingTask task = writingTaskExecutor.startWriting(id, maxChapters, instruction);
        return Map.of(
                "projectId", id,
                "taskId", task.getId(),
                "type", task.getType().name(),
                "status", task.getStatus().name(),
                "totalChapters", task.getTotalChapters(),
                "message", "异步写作已启动，请轮询任务状态"
        );
    }

    /** 查询写作任务进度 */
    @GetMapping("/writing-tasks/{taskId}")
    public Map<String, Object> getWritingTask(@PathVariable String id, @PathVariable String taskId) {
        WritingTask task = writingTaskExecutor.getTask(taskId);
        if (task == null) return Map.of("error", "任务不存在");
        return Map.of(
                "taskId", task.getId(),
                "projectId", task.getProjectId(),
                "type", task.getType().name(),
                "status", task.getStatus().name(),
                "currentChapter", task.getCurrentChapter(),
                "chaptersWritten", task.getChaptersWritten(),
                "totalChapters", task.getTotalChapters(),
                "totalWords", task.getTotalWords(),
                "errorMessage", task.getErrorMessage() != null ? task.getErrorMessage() : ""
        );
    }

    /** 取消写作任务 */
    @PostMapping("/writing-tasks/{taskId}/cancel")
    public Map<String, Object> cancelWritingTask(@PathVariable String id, @PathVariable String taskId) {
        writingTaskExecutor.cancel(taskId);
        return Map.of("taskId", taskId, "status", "CANCELLED");
    }

    /**
     * p21-02: 显式成书门禁重试——NEEDS_REVISION 人工修订后触发；通过 → FINAL。
     */
    @PostMapping("/final-audit")
    public Map<String, Object> finalAudit(@PathVariable String id) {
        boolean passed = orchestrator.retryFinalAudit(id);
        return Map.of(
                "projectId", id,
                "passed", passed,
                "state", passed ? NovelState.FINAL.name() : NovelState.NEEDS_REVISION.name()
        );
    }

    /** 质量评价：从一致性/风格/情节/人物/世界观 5 维度评分并发现问题 */
    @PostMapping("/evaluate")
    public Map<String, Object> evaluate(@PathVariable String id) {
        var report = qualityAgent.evaluate(id);
        return report.toMap();
    }
}
