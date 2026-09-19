package com.writing.novel.web;

import com.writing.novel.service.DelegationPolicyService;
import com.writing.novel.spi.NovelRepository;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * NCS-025（方案 §05/DELEGATION）：委托策略 API——读/写按变更类别的 AUTO/CONFIRM 规则。
 */
@RestController
@RequestMapping("/api/projects/{id}/delegation-policy")
public class DelegationPolicyController {

    private final NovelRepository repository;
    private final DelegationPolicyService service;

    public DelegationPolicyController(NovelRepository repository, DelegationPolicyService service) {
        this.repository = repository;
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> get(@PathVariable String id) {
        requireProject(id);
        Map<String, Object> m = new LinkedHashMap<>();
        service.rules(id).forEach((k, v) -> m.put(k.name(), v.name()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("projectId", id);
        result.put("rules", m);
        return result;
    }

    @PutMapping
    public Map<String, Object> save(@PathVariable String id, @RequestBody Map<String, Object> body) {
        requireProject(id);
        Map<String, String> rules = new LinkedHashMap<>();
        Object raw = body.get("rules");
        if (raw instanceof Map<?, ?> map) {
            map.forEach((k, v) -> rules.put(String.valueOf(k), String.valueOf(v)));
        }
        service.saveRules(id, rules);
        return get(id);
    }

    private void requireProject(String projectId) {
        if (repository.findProject(projectId).isEmpty()) {
            throw new IllegalArgumentException("项目不存在: " + projectId);
        }
    }
}
