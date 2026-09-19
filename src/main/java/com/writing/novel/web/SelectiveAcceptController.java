package com.writing.novel.web;

import com.writing.novel.service.SelectiveAcceptService;
import com.writing.novel.spi.NovelRepository;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NCS-024（方案 §13/R18/UX10）：选择性组合采纳 API。
 * combine → 新候选（需重检）；recheck → 强制重检；accept → 重检通过后采纳。
 */
@RestController
@RequestMapping("/api/projects/{id}/selective-accept")
public class SelectiveAcceptController {

    private final NovelRepository repository;
    private final SelectiveAcceptService service;

    public SelectiveAcceptController(NovelRepository repository, SelectiveAcceptService service) {
        this.repository = repository;
        this.service = service;
    }

    @PostMapping("/combine")
    public Map<String, Object> combine(@PathVariable String id, @RequestBody Map<String, Object> body) {
        requireProject(id);
        String requestId = String.valueOf(body.get("requestId"));
        int chapter = Integer.parseInt(String.valueOf(body.get("chapterIndex")));
        List<SelectiveAcceptService.Fragment> fragments = new ArrayList<>();
        Object raw = body.get("fragments");
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    List<Integer> paragraphs = new ArrayList<>();
                    Object p = m.get("paragraphs");
                    if (p instanceof List<?> pl) {
                        for (Object pi : pl) paragraphs.add(Integer.parseInt(String.valueOf(pi)));
                    }
                    fragments.add(new SelectiveAcceptService.Fragment(
                            String.valueOf(m.get("previewId")), paragraphs));
                }
            }
        }
        var combined = service.combine(id, requestId, chapter, fragments);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("previewId", combined.previewId());
        m.put("content", combined.content());
        m.put("sources", combined.sources());
        m.put("needsRecheck", true);
        return m;
    }

    @PostMapping("/{previewId}/recheck")
    public Map<String, Object> recheck(@PathVariable String id, @PathVariable String previewId) {
        requireProject(id);
        service.recheck(id, previewId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("previewId", previewId);
        m.put("needsRecheck", service.needsRecheck(id, previewId));
        return m;
    }

    @PostMapping("/{previewId}/accept")
    public Map<String, Object> accept(@PathVariable String id, @PathVariable String previewId) {
        requireProject(id);
        service.accept(id, previewId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("previewId", previewId);
        m.put("accepted", true);
        return m;
    }

    private void requireProject(String projectId) {
        if (repository.findProject(projectId).isEmpty()) {
            throw new IllegalArgumentException("项目不存在: " + projectId);
        }
    }
}
