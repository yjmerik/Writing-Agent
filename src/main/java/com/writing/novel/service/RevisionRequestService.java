package com.writing.novel.service;

import com.writing.novel.domain.RevisionRequest;
import com.writing.novel.persistence.sqlite.RevisionRequestRepository;
import com.writing.novel.telemetry.EventLog;

import java.util.List;
import java.util.Map;

/**
 * NCS-013（方案 §12/F07/F09）：统一修订请求服务。
 * 六类入口归一登记；范围批准与内容采纳是两个独立决定；
 * 同步完成的入口（作者直存/恢复/计划确认）登记即完成——作者本人即采纳决定。
 */
public class RevisionRequestService {

    private final RevisionRequestRepository repository;

    public RevisionRequestService(RevisionRequestRepository repository) {
        this.repository = repository;
    }

    public RevisionRequest record(String projectId, String origin, String intent,
                                  String targetAnchorsJson, String baseVersionsJson,
                                  String protectAnchorsJson) {
        RevisionRequest r = new RevisionRequest();
        r.setProjectId(projectId);
        r.setOrigin(RevisionRequest.Origin.valueOf(origin));
        r.setIntent(intent == null ? "" : intent);
        r.setTargetAnchorsJson(targetAnchorsJson == null ? "[]" : targetAnchorsJson);
        r.setBaseVersionsJson(baseVersionsJson == null ? "{}" : baseVersionsJson);
        r.setProtectAnchorsJson(protectAnchorsJson == null ? "[]" : protectAnchorsJson);
        repository.save(r);
        EventLog.emit("REVISION_REQUEST_RECORDED", Map.of(
                "projectId", projectId, "requestId", r.getId(),
                "origin", r.getOrigin().name()));
        return r;
    }

    /** 同步入口（作者直存等）：登记并立即完成——作者本人即采纳决定，仍留完整记录 */
    public RevisionRequest recordAndComplete(String projectId, String origin, String intent,
                                              String targetAnchorsJson, String baseVersionsJson,
                                              String protectAnchorsJson, String acceptanceNote) {
        RevisionRequest r = record(projectId, origin, intent, targetAnchorsJson,
                baseVersionsJson, protectAnchorsJson);
        r.accept(acceptanceNote == null ? "作者直接操作，同步完成" : acceptanceNote);
        repository.save(r);
        EventLog.emit("REVISION_REQUEST_COMPLETED", Map.of(
                "projectId", projectId, "requestId", r.getId(),
                "origin", r.getOrigin().name()));
        return r;
    }

    /** 范围批准（独立于内容采纳）：批准的是探索与费用范围，不是接受正文 */
    public RevisionRequest approveScope(String projectId, String requestId, String decision) {
        RevisionRequest r = get(projectId, requestId);
        r.approveScope(decision);
        repository.save(r);
        EventLog.emit("REVISION_SCOPE_APPROVED", Map.of(
                "projectId", projectId, "requestId", requestId));
        return r;
    }

    /** 内容采纳：接受实际候选版本（仅在范围批准后或同步入口） */
    public RevisionRequest accept(String projectId, String requestId, String acceptanceRecord) {
        RevisionRequest r = get(projectId, requestId);
        r.accept(acceptanceRecord);
        repository.save(r);
        return r;
    }

    public RevisionRequest cancel(String projectId, String requestId, String reason) {
        RevisionRequest r = get(projectId, requestId);
        r.cancel(reason);
        repository.save(r);
        return r;
    }

    /** 修复候选生成前的范围前提检查——OPEN 状态不得生成（批准的是范围，采纳的是内容） */
    public RevisionRequest requireScopeApproved(String projectId, String requestId) {
        RevisionRequest r = get(projectId, requestId);
        if (r.getStatus() != RevisionRequest.Status.SCOPE_APPROVED
                && r.getStatus() != RevisionRequest.Status.COMPLETED) {
            throw new IllegalStateException(
                    "修订请求尚未批准范围（当前 " + r.getStatus() + "）——先批准探索/费用范围，再生成修复");
        }
        return r;
    }

    public RevisionRequest get(String projectId, String requestId) {
        return repository.find(projectId, requestId)
                .orElseThrow(() -> new IllegalArgumentException("修订请求不存在: " + requestId));
    }

    public List<RevisionRequest> list(String projectId) {
        return repository.findByProject(projectId);
    }
}
