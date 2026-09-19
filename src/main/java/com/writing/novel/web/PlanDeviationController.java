package com.writing.novel.web;

import com.writing.novel.service.PlanDeviationService;
import com.writing.novel.spi.NovelRepository;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NCS-019（方案 §06）：计划偏离 API——按需分析/列表/三选一决定。
 */
@RestController
@RequestMapping("/api/projects/{id}/plan-deviations")
public class PlanDeviationController {

    private final NovelRepository repository;
    private final PlanDeviationService service;

    public PlanDeviationController(NovelRepository repository, PlanDeviationService service) {
        this.repository = repository;
        this.service = service;
    }

    @PostMapping("/analyze")
    public Map<String, Object> analyze(@PathVariable String id, @RequestParam int chapter) {
        requireProject(id);
        var result = service.analyze(id, chapter);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("chapterIndex", result.chapterIndex());
        m.put("analyzed", result.analyzed());
        return m;
    }

    @GetMapping
    public Map<String, Object> list(@PathVariable String id) {
        requireProject(id);
        List<Map<String, Object>> items = new ArrayList<>();
        for (var d : service.list(id)) {
            items.add(toMap(d));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("projectId", id);
        m.put("deviations", items);
        return m;
    }

    @PostMapping("/{chapterIndex}/decide")
    public Map<String, Object> decide(@PathVariable String id, @PathVariable int chapterIndex,
                                      @RequestBody Map<String, Object> body) {
        requireProject(id);
        String action = String.valueOf(body.getOrDefault("action", ""));
        return toMap(service.decide(id, chapterIndex, action));
    }

    private void requireProject(String projectId) {
        if (repository.findProject(projectId).isEmpty()) {
            throw new IllegalArgumentException("项目不存在: " + projectId);
        }
    }

    private Map<String, Object> toMap(com.writing.novel.domain.PlanDeviation d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("chapterIndex", d.getChapterIndex());
        m.put("chapterVersion", d.getChapterVersion());
        m.put("deviation", d.getDeviationJson());
        m.put("decision", d.getDecision());
        m.put("decidedAt", d.getDecidedAt() == null ? null : d.getDecidedAt().toString());
        return m;
    }
}
