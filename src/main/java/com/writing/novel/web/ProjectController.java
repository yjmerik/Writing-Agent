package com.writing.novel.web;

import com.writing.novel.domain.Project;
import com.writing.novel.domain.ProjectMode;
import com.writing.novel.orchestrator.ProjectService;
import com.writing.novel.service.WritingTaskExecutor;
import com.writing.novel.web.dto.CreateProjectRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;
    private final WritingTaskExecutor writingTaskExecutor;

    public ProjectController(ProjectService projectService, WritingTaskExecutor writingTaskExecutor) {
        this.projectService = projectService;
        this.writingTaskExecutor = writingTaskExecutor;
    }

    @PostMapping
    public Project create(@RequestBody CreateProjectRequest req) {
        ProjectMode mode = req.mode() != null ? ProjectMode.valueOf(req.mode()) : ProjectMode.SOLO;
        String title = req.title();
        // fallback: 标题为空时用构思前10字
        if ((title == null || title.isBlank()) && req.concept() != null && !req.concept().isBlank()) {
            title = req.concept().length() > 10 ? req.concept().substring(0, 10) + "…" : req.concept();
        }
        return projectService.create(title, req.concept(), req.tags(), mode, req.preset(), req.targetChapters());
    }

    @GetMapping
    public List<Project> list() {
        return projectService.findAll();
    }

    @GetMapping("/{id}")
    public Project get(@PathVariable String id) {
        return projectService.find(id).orElseThrow(() ->
                new IllegalArgumentException("项目不存在: " + id));
    }

    @PutMapping("/{id}/mode")
    public void switchMode(@PathVariable String id, @RequestBody String mode) {
        String cleanMode = mode.replace("\"", "");
        projectService.switchMode(id, ProjectMode.valueOf(cleanMode));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id) {
        if (writingTaskExecutor != null && writingTaskExecutor.hasActiveTask(id)) {
            throw new IllegalStateException("请先暂停或取消写作");
        }
        projectService.delete(id);
    }
}
