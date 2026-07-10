package com.writing.novel.web;

import com.writing.novel.domain.Project;
import com.writing.novel.orchestrator.NovelOrchestrator;
import com.writing.novel.orchestrator.ProjectService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/projects/{id}")
public class OrchestratorController {

    private final NovelOrchestrator orchestrator;
    private final ProjectService projectService;

    public OrchestratorController(NovelOrchestrator orchestrator, ProjectService projectService) {
        this.orchestrator = orchestrator;
        this.projectService = projectService;
    }

    @PostMapping("/run")
    public Map<String, Object> run(@PathVariable String id) {
        orchestrator.step(id);
        return status(id);
    }

    @GetMapping("/status")
    public Map<String, Object> status(@PathVariable String id) {
        Project p = projectService.find(id).orElseThrow();
        return Map.of(
                "projectId", id,
                "state", p.getState().name(),
                "mode", p.getMode().name(),
                "currentChapter", p.getCurrentChapterIndex()
        );
    }
}
