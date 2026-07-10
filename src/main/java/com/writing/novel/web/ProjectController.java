package com.writing.novel.web;

import com.writing.novel.domain.Project;
import com.writing.novel.domain.ProjectMode;
import com.writing.novel.orchestrator.ProjectService;
import com.writing.novel.web.dto.CreateProjectRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @PostMapping
    public Project create(@RequestBody CreateProjectRequest req) {
        ProjectMode mode = req.mode() != null ? ProjectMode.valueOf(req.mode()) : ProjectMode.SOLO;
        return projectService.create(req.title(), req.concept(), req.tags(), mode);
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
        projectService.delete(id);
    }
}
