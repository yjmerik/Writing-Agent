package com.writing.novel.web;

import com.writing.novel.agent.QualityEvaluationAgent;
import com.writing.novel.domain.Checkpoint;
import com.writing.novel.domain.NovelState;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.WritingPreferences;
import com.writing.novel.domain.WritingTask;
import com.writing.novel.orchestrator.NovelOrchestrator;
import com.writing.novel.orchestrator.ProjectActionPolicy;
import com.writing.novel.orchestrator.ProjectActionPolicy.ActionAvailability;
import com.writing.novel.orchestrator.ProjectActionPolicy.ProjectAction;
import com.writing.novel.orchestrator.ProjectService;
import com.writing.novel.service.WritingTaskExecutor;
import com.writing.novel.spi.NovelRepository;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/projects/{id}")
public class OrchestratorController {

    private final NovelOrchestrator orchestrator;
    private final ProjectService projectService;
    private final QualityEvaluationAgent qualityAgent;
    private final WritingTaskExecutor writingTaskExecutor;
    private final NovelRepository repository;
    private final com.writing.novel.persistence.sqlite.QualityEvaluationRepository evaluationRepository;
    private final ProjectActionPolicy actionPolicy = new ProjectActionPolicy();

    public OrchestratorController(NovelOrchestrator orchestrator,
                                   ProjectService projectService,
                                   QualityEvaluationAgent qualityAgent,
                                   WritingTaskExecutor writingTaskExecutor,
                                   NovelRepository repository,
                                   com.writing.novel.persistence.sqlite.QualityEvaluationRepository evaluationRepository) {
        this.orchestrator = orchestrator;
        this.projectService = projectService;
        this.qualityAgent = qualityAgent;
        this.writingTaskExecutor = writingTaskExecutor;
        this.repository = repository;
        this.evaluationRepository = evaluationRepository;
    }

    @PostMapping("/run")
    public Map<String, Object> run(@PathVariable String id) {
        Project project = projectService.find(id).orElseThrow();
        requireAllowed(project, ProjectAction.ADVANCE);
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
        ActionAvailability availability = availability(p);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("projectId", id);
        result.put("state", p.getState().name());
        result.put("mode", p.getMode().name());
        result.put("currentChapter", p.getCurrentChapterIndex());
        ProjectProgressSupport.Progress progress = ProjectProgressSupport.forProject(p, repository);
        result.put("targetChapters", progress.targetChapters());
        result.put("progressPercent", progress.progressPercent());
        result.put("pauseStage", progress.pauseStage());
        result.put("pauseReason", progress.pauseReason());
        result.put("totalWords", orchestrator.getTotalWordCount(id));
        result.put("allowedActions", availability.allowedActions());
        result.put("blockedReasons", availability.blockedReasons());
        return result;
    }

    /**
     * p12-K2: 异步连续写作——启动后台任务，立即返回 taskId。
     * 前端轮询 GET /writing-tasks/{taskId} 查进度。
     */
    @PostMapping("/write-all")
    public Map<String, Object> writeAll(@PathVariable String id,
                                         @RequestParam(defaultValue = "50") int maxChapters,
                                         @RequestParam(required = false) String instruction) {
        Project project = projectService.find(id).orElseThrow();
        requireAllowed(project, ProjectAction.WRITE_ALL);
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

    /** NCS-029：作品的持久任务历史，包括已结束的任务。 */
    @GetMapping("/writing-tasks")
    public java.util.List<WritingTask> listWritingTasks(@PathVariable String id) {
        projectService.find(id).orElseThrow();
        return repository.findWritingTasksByProject(id);
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
        Project project = projectService.find(id).orElseThrow();
        requireAllowed(project, ProjectAction.FINAL_AUDIT);
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
        Map<String, Object> result = report.toMap();
        // p32-11: 评价历史落库——切页/刷新后可读回
        evaluationRepository.save(id, report.overallScore, result);
        return result;
    }

    /** p32-11: 读回最近一次全书评价（无记录时 evaluation=null）。 */
    @GetMapping("/evaluate")
    public Map<String, Object> latestEvaluation(@PathVariable String id) {
        return evaluationRepository.findLatest(id)
                .orElseGet(() -> java.util.Collections.singletonMap("evaluation", null));
    }

    private ActionAvailability availability(Project project) {
        String projectId = project.getId();
        return actionPolicy.evaluate(project,
                repository.findOutlineByProject(projectId).isPresent(),
                !repository.findPendingCheckpoints(projectId).isEmpty());
    }

    private void requireAllowed(Project project, ProjectAction action) {
        String projectId = project.getId();
        actionPolicy.requireAllowed(project, action,
                repository.findOutlineByProject(projectId).isPresent(),
                !repository.findPendingCheckpoints(projectId).isEmpty());
    }
}

/** Shared server-side facts for status and project-list views. */
final class ProjectProgressSupport {

    private static final int CONCEIVING_MILESTONE_PERCENT = 10;
    private static final int PLANNING_MILESTONE_PERCENT = 25;

    private ProjectProgressSupport() {
    }

    static Progress forProject(Project project, NovelRepository repository) {
        int domainDefault = new WritingPreferences(project.getId()).getTargetChapters();
        int configuredTarget = repository.findWritingPreferences(project.getId())
                .map(WritingPreferences::getTargetChapters)
                .orElse(domainDefault);
        int targetChapters = configuredTarget > 0 ? configuredTarget : domainDefault;

        Checkpoint checkpoint = repository.findLatestPendingCheckpoint(project.getId()).orElse(null);
        String pauseStage = checkpoint == null ? null : switch (checkpoint.getType()) {
            case AFTER_INITIAL_SETUP, AFTER_VOLUME_OUTLINE -> NovelState.PLANNING.name();
            case AFTER_CHAPTER, ON_CONTINUITY_FIX -> NovelState.WRITING.name();
        };
        String pauseReason = checkpoint == null ? null : checkpoint.getDescription();

        int progressPercent = switch (project.getState()) {
            case CONCEIVING -> CONCEIVING_MILESTONE_PERCENT;
            case PLANNING -> PLANNING_MILESTONE_PERCENT;
            case PAUSED_AT_CHECKPOINT -> NovelState.PLANNING.name().equals(pauseStage)
                    ? PLANNING_MILESTONE_PERCENT
                    : writingPercent(project.getCurrentChapterIndex(), targetChapters);
            case WRITING, VOLUME_DONE, NEEDS_REVISION ->
                    writingPercent(project.getCurrentChapterIndex(), targetChapters);
            case FINAL -> 100;
        };
        return new Progress(targetChapters, progressPercent, pauseStage, pauseReason);
    }

    private static int writingPercent(int currentChapter, int targetChapters) {
        long bounded = Math.max(0L, currentChapter) * 100L / targetChapters;
        return (int) Math.min(100L, bounded);
    }

    record Progress(int targetChapters, int progressPercent, String pauseStage, String pauseReason) {
    }
}
