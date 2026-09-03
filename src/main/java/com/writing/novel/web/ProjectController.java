package com.writing.novel.web;

import com.writing.novel.domain.Project;
import com.writing.novel.domain.ProjectMode;
import com.writing.novel.orchestrator.ProjectService;
import com.writing.novel.service.WritingTaskExecutor;
import com.writing.novel.spi.NovelRepository;
import com.writing.novel.web.dto.CreateProjectRequest;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;
    private final WritingTaskExecutor writingTaskExecutor;
    private final NovelRepository repository;

    public ProjectController(ProjectService projectService,
                             WritingTaskExecutor writingTaskExecutor,
                             NovelRepository repository) {
        this.projectService = projectService;
        this.writingTaskExecutor = writingTaskExecutor;
        this.repository = repository;
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
    public List<Map<String, Object>> list() {
        return projectService.findAll().stream().map(this::toListItem).toList();
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

    private Map<String, Object> toListItem(Project project) {
        ProjectProgressSupport.Progress progress = ProjectProgressSupport.forProject(project, repository);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", project.getId());
        item.put("title", project.getTitle());
        item.put("concept", project.getConcept());
        item.put("tags", project.getTags());
        item.put("mode", project.getMode());
        item.put("state", project.getState());
        item.put("genre", project.getGenre());
        item.put("createdAt", project.getCreatedAt());
        item.put("updatedAt", project.getUpdatedAt());
        item.put("currentChapterIndex", project.getCurrentChapterIndex());
        item.put("bookSummary", project.getBookSummary());
        item.put("bookSnapshot", project.getBookSnapshot());
        item.put("targetChapters", progress.targetChapters());
        item.put("progressPercent", progress.progressPercent());
        item.put("pauseStage", progress.pauseStage());
        item.put("pauseReason", progress.pauseReason());
        return item;
    }
}
