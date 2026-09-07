package com.writing.novel.web;

import com.writing.novel.domain.Project;
import com.writing.novel.domain.ProjectMode;
import com.writing.novel.domain.WritingPreferences;
import com.writing.novel.domain.WritingTask;
import com.writing.novel.service.WritingTaskExecutor;
import com.writing.novel.spi.NovelRepository;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** One collaboration surface; novel phase and current worker remain independent. */
@RestController
@RequestMapping("/api/projects/{id}/collaboration")
public class CollaborationController {
    private final NovelRepository repository;
    private final WritingTaskExecutor executor;

    public CollaborationController(NovelRepository repository, WritingTaskExecutor executor) {
        this.repository = repository;
        this.executor = executor;
    }

    @GetMapping
    public Map<String, Object> status(@PathVariable String id) {
        Project project = requireProject(id);
        WritingTask task = executor.latestCollaborativeTask(id);
        boolean busy = executor.hasActiveTask(id);
        if (busy) task = repository.findActiveWritingTask(id).orElse(task);
        String blocked = executor.collaborationBlocker(id);
        var prefs = preferences(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("projectId", id);
        result.put("state", project.getState());
        result.put("busy", busy);
        result.put("task", task);
        result.put("stopRequested", busy && task != null && task.isStopRequested());
        result.put("pendingReviews", repository.findPendingCheckpoints(id).size());
        result.put("canContinue", !busy && blocked == null);
        result.put("blockedReason", busy ? "正在处理内容" : blocked);
        result.put("reviewPolicy", project.getMode() == ProjectMode.SOLO ? "MANUAL"
                : prefs.getCheckpointSpan() == -1 ? "VOLUME"
                : prefs.getCheckpointSpan() == 0 ? "MANUAL" : "CHAPTER");
        result.put("currentChapter", project.getCurrentChapterIndex());
        result.put("targetChapters", prefs.getTargetChapters());
        result.put("totalWords", repository.getTotalWordCount(id));
        return result;
    }

    @PostMapping("/start")
    public WritingTask start(@PathVariable String id,
                             @RequestBody(required = false) Map<String, String> body) {
        String instruction = body == null ? null : body.get("instruction");
        if (instruction != null && instruction.length() > 2000) {
            throw new IllegalArgumentException("创作要求请控制在 2000 字以内");
        }
        return executor.startCollaborative(id, instruction);
    }

    @PostMapping("/stop")
    public Map<String, Object> stop(@PathVariable String id) {
        executor.stopCollaborative(id);
        return status(id);
    }

    @PutMapping("/review-policy")
    public Map<String, Object> reviewPolicy(@PathVariable String id,
                                            @RequestBody Map<String, String> body) {
        String policy = body.get("policy");
        if (policy == null || !Set.of("MANUAL", "CHAPTER", "VOLUME").contains(policy)) {
            throw new IllegalArgumentException("未知审核节奏");
        }
        String owner = "review-policy-" + UUID.randomUUID();
        executor.beginWork(id, owner);
        try {
            Project project = requireProject(id);
            var prefs = preferences(id);
            project.setMode(policy.equals("MANUAL") ? ProjectMode.SOLO : ProjectMode.INTERACTIVE);
            prefs.setCheckpointSpan(policy.equals("MANUAL") ? 0 : policy.equals("VOLUME") ? -1 : 1);
            repository.saveWritingPreferences(prefs);
            repository.saveProject(project);
        } finally {
            executor.endWork(id, owner);
        }
        return status(id);
    }

    private Project requireProject(String id) {
        return repository.findProject(id)
                .orElseThrow(() -> new IllegalArgumentException("项目不存在"));
    }

    private WritingPreferences preferences(String id) {
        return repository.findWritingPreferences(id).orElseGet(() -> new WritingPreferences(id));
    }
}
