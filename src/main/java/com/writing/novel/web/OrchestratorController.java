package com.writing.novel.web;

import com.writing.novel.agent.QualityEvaluationAgent;
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
    private final QualityEvaluationAgent qualityAgent;

    public OrchestratorController(NovelOrchestrator orchestrator,
                                   ProjectService projectService,
                                   QualityEvaluationAgent qualityAgent) {
        this.orchestrator = orchestrator;
        this.projectService = projectService;
        this.qualityAgent = qualityAgent;
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
                "currentChapter", p.getCurrentChapterIndex(),
                "totalWords", orchestrator.getTotalWordCount(id)
        );
    }

    /** 连续写作：批量写 maxChapters 章（默认 50） */
    @PostMapping("/write-all")
    public Map<String, Object> writeAll(@PathVariable String id,
                                         @RequestParam(defaultValue = "50") int maxChapters) {
        int written = orchestrator.runWritingLoop(id, maxChapters);
        Project p = projectService.find(id).orElseThrow();
        return Map.of(
                "projectId", id,
                "chaptersWritten", written,
                "state", p.getState().name(),
                "currentChapter", p.getCurrentChapterIndex(),
                "totalWords", orchestrator.getTotalWordCount(id)
        );
    }

    /** 质量评价：从一致性/风格/情节/人物/世界观 5 维度评分并发现问题 */
    @PostMapping("/evaluate")
    public Map<String, Object> evaluate(@PathVariable String id) {
        var report = qualityAgent.evaluate(id);
        return report.toMap();
    }
}
