package com.writing.novel.web;

import com.writing.novel.domain.RevisionRequest;
import com.writing.novel.service.RevisionRequestService;
import com.writing.novel.spi.NovelRepository;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NCS-013（方案 §12）：统一修订请求 API。
 * 六类入口归一登记；范围批准与内容采纳为两个独立端点。
 */
@RestController
@RequestMapping("/api/projects/{id}/revision-requests")
public class RevisionRequestController {

    private final NovelRepository repository;
    private final RevisionRequestService service;

    public RevisionRequestController(NovelRepository repository, RevisionRequestService service) {
        this.repository = repository;
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> list(@PathVariable String id) {
        requireProject(id);
        List<Map<String, Object>> requests = new ArrayList<>();
        for (RevisionRequest r : service.list(id)) {
            requests.add(toMap(r));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("projectId", id);
        m.put("requests", requests);
        return m;
    }

    @PostMapping
    public Map<String, Object> record(@PathVariable String id, @RequestBody Map<String, Object> body) {
        requireProject(id);
        String origin = String.valueOf(body.getOrDefault("origin", ""));
        RevisionRequest r = service.record(id, origin,
                String.valueOf(body.getOrDefault("intent", "")),
                body.get("targetAnchors") == null ? "[]" : String.valueOf(body.get("targetAnchors")),
                body.get("baseVersions") == null ? "{}" : String.valueOf(body.get("baseVersions")),
                body.get("protectAnchors") == null ? "[]" : String.valueOf(body.get("protectAnchors")));
        return toMap(r);
    }

    @PostMapping("/{requestId}/approve-scope")
    public Map<String, Object> approveScope(@PathVariable String id, @PathVariable String requestId,
                                            @RequestBody Map<String, Object> body) {
        requireProject(id);
        return toMap(service.approveScope(id, requestId,
                String.valueOf(body.getOrDefault("decision", ""))));
    }

    @PostMapping("/{requestId}/accept")
    public Map<String, Object> accept(@PathVariable String id, @PathVariable String requestId,
                                      @RequestBody Map<String, Object> body) {
        requireProject(id);
        return toMap(service.accept(id, requestId,
                String.valueOf(body.getOrDefault("acceptance", ""))));
    }

    @PostMapping("/{requestId}/cancel")
    public Map<String, Object> cancel(@PathVariable String id, @PathVariable String requestId,
                                      @RequestBody Map<String, Object> body) {
        requireProject(id);
        return toMap(service.cancel(id, requestId,
                String.valueOf(body.getOrDefault("reason", ""))));
    }

    private void requireProject(String projectId) {
        if (repository.findProject(projectId).isEmpty()) {
            throw new IllegalArgumentException("项目不存在: " + projectId);
        }
    }

    private Map<String, Object> toMap(RevisionRequest r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("origin", r.getOrigin().name());
        m.put("intent", r.getIntent());
        m.put("targetAnchors", r.getTargetAnchorsJson());
        m.put("baseVersions", r.getBaseVersionsJson());
        m.put("protectAnchors", r.getProtectAnchorsJson());
        m.put("status", r.getStatus().name());
        m.put("scopeDecision", r.getScopeDecision());
        m.put("acceptance", r.getAcceptance());
        m.put("cancelReason", r.getCancelReason());
        m.put("createdAt", r.getCreatedAt().toString());
        m.put("updatedAt", r.getUpdatedAt().toString());
        return m;
    }
}
