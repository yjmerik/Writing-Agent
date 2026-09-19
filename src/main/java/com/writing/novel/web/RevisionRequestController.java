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
    private final com.writing.novel.service.ImpactAnalysisService impactAnalysis;
    private final com.writing.novel.service.RevisionRepairService repairService;
    private final com.writing.novel.service.GroupAcceptanceService acceptanceService;

    public RevisionRequestController(NovelRepository repository, RevisionRequestService service,
                                     com.writing.novel.service.ImpactAnalysisService impactAnalysis,
                                     com.writing.novel.service.RevisionRepairService repairService,
                                     com.writing.novel.service.GroupAcceptanceService acceptanceService) {
        this.repository = repository;
        this.service = service;
        this.impactAnalysis = impactAnalysis;
        this.repairService = repairService;
        this.acceptanceService = acceptanceService;
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

    /** NCS-014: 对指定章的文本变更运行影响分析（确定性；范围批准后使用） */
    @PostMapping("/{requestId}/analyze-impact")
    public Map<String, Object> analyzeImpact(@PathVariable String id, @PathVariable String requestId,
                                             @RequestParam int chapter) {
        requireProject(id);
        var request = service.get(id, requestId);
        var chapterEntity = repository.findChapterByIndex(id, chapter)
                .orElseThrow(() -> new IllegalArgumentException("章节不存在: " + chapter));
        var plan = impactAnalysis.analyze(id, chapter, chapterEntity.getVersion());
        service.requireScopeApproved(id, requestId); // 影响分析消耗探索范围——需先批准
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("requestId", request.getId());
        m.put("revisedChapterIndex", plan.revisedChapterIndex());
        m.put("revisedToVersion", plan.revisedToVersion());
        m.put("coverageNote", plan.coverageNote());
        List<Map<String, Object>> items = new ArrayList<>();
        for (var item : plan.items()) {
            Map<String, Object> im = new LinkedHashMap<>();
            im.put("chapterIndex", item.chapterIndex());
            im.put("classification", item.classification().name());
            im.put("reasons", item.reasons());
            im.put("factIds", item.factIds());
            im.put("subjects", item.subjects());
            items.add(im);
        }
        m.put("items", items);
        return m;
    }

    /** NCS-015: 为 MUST_REPAIR 章生成修复候选（需范围批准） */
    @PostMapping("/{requestId}/generate-repairs")
    public Map<String, Object> generateRepairs(@PathVariable String id, @PathVariable String requestId,
                                               @RequestParam int chapter,
                                               @RequestBody Map<String, Object> body) {
        requireProject(id);
        var result = repairService.generateRepairs(id, requestId, chapter,
                String.valueOf(body.getOrDefault("intent", "")));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("requestId", requestId);
        m.put("generated", result.generated());
        m.put("previewIds", result.previewIds());
        m.put("chapters", result.chapters());
        return m;
    }

    /** NCS-016: 依赖组整组接受（commandId 幂等） */
    @PostMapping("/{requestId}/accept-group")
    public Map<String, Object> acceptGroup(@PathVariable String id, @PathVariable String requestId,
                                           @RequestBody Map<String, Object> body) {
        requireProject(id);
        String commandId = String.valueOf(body.get("commandId"));
        var receipt = acceptanceService.acceptGroup(id, requestId, commandId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("commandId", receipt.commandId());
        m.put("applied", receipt.applied());
        m.put("replayed", receipt.replayed());
        m.put("chapters", receipt.chapters());
        m.put("message", receipt.message());
        return m;
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
