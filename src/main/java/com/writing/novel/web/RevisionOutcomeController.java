package com.writing.novel.web;

import com.writing.novel.service.RevisionExperienceService;
import com.writing.novel.spi.NovelRepository;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NCS-026（方案 §13）：运行经验 API——列表/删除（作者可撤回经验）。
 */
@RestController
@RequestMapping("/api/projects/{id}/revision-outcomes")
public class RevisionOutcomeController {

    private final NovelRepository repository;
    private final RevisionExperienceService service;

    public RevisionOutcomeController(NovelRepository repository, RevisionExperienceService service) {
        this.repository = repository;
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> list(@PathVariable String id) {
        requireProject(id);
        List<Map<String, Object>> items = new ArrayList<>();
        service.list(id).forEach(o -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", o.getId());
            m.put("changeClass", o.getChangeClass());
            m.put("decision", o.getDecision());
            m.put("instructions", o.getInstructions());
            m.put("summary", o.getSummary());
            m.put("reason", o.getReason());
            m.put("createdAt", o.getCreatedAt().toString());
            items.add(m);
        });
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("projectId", id);
        m.put("outcomes", items);
        return m;
    }

    @DeleteMapping("/{outcomeId}")
    public Map<String, Object> delete(@PathVariable String id, @PathVariable String outcomeId) {
        requireProject(id);
        boolean deleted = service.delete(id, outcomeId);
        if (!deleted) {
            throw new IllegalArgumentException("经验记录不存在: " + outcomeId);
        }
        return Map.of("deleted", true);
    }

    private void requireProject(String projectId) {
        if (repository.findProject(projectId).isEmpty()) {
            throw new IllegalArgumentException("项目不存在: " + projectId);
        }
    }
}
