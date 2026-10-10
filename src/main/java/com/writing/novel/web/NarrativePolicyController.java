package com.writing.novel.web;

import com.writing.novel.service.NarrativePolicyService;
import com.writing.novel.spi.NovelRepository;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * NCS-190: 叙事策略 API——GET（题材默认+覆盖+生效值）/ PUT（部分覆盖，校验非法值 400）
 * / DELETE（清除覆盖回到题材默认）。
 */
@RestController
@RequestMapping("/api/projects/{id}/narrative-policy")
public class NarrativePolicyController {

    private final NovelRepository repository;
    private final NarrativePolicyService service;

    public NarrativePolicyController(NovelRepository repository, NarrativePolicyService service) {
        this.repository = repository;
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> get(@PathVariable String id) {
        requireProject(id);
        return buildView(id, new LinkedHashMap<>());
    }

    @PutMapping
    public Map<String, Object> put(@PathVariable String id, @RequestBody Map<String, Object> body) {
        requireProject(id);
        service.saveOverrides(id, body);
        return buildView(id, new LinkedHashMap<>());
    }

    @DeleteMapping
    public Map<String, Object> delete(@PathVariable String id) {
        requireProject(id);
        service.clearOverrides(id);
        return buildView(id, new LinkedHashMap<>());
    }

    private Map<String, Object> buildView(String projectId, Map<String, Object> base) {
        var project = repository.findProject(projectId).orElseThrow();
        String genre = project.getGenre() == null ? "GENERAL" : project.getGenre().name();
        String genreName = project.getGenre() == null ? "通用"
                : project.getGenre().getChineseName();
        base.put("genre", genre);
        base.put("genreName", genreName);
        base.put("defaults", service.defaultsFor(projectId).toMap());
        base.put("overrides", service.overridesOf(projectId));
        base.put("effective", service.resolve(projectId).toMap());
        return base;
    }

    private void requireProject(String projectId) {
        if (repository.findProject(projectId).isEmpty()) {
            throw new IllegalArgumentException("项目不存在: " + projectId);
        }
    }
}
