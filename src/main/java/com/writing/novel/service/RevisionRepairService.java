package com.writing.novel.service;

import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.RevisionRequest;
import com.writing.novel.telemetry.EventLog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * NCS-015（方案 §12）：影响修复候选生成。
 * 为影响分析判定的 MUST_REPAIR 章生成隔离修复候选（带影响上下文与保护项），
 * 挂接修订请求（requestKey=rr:{requestId}:{chapterIndex}）；候选全部隔离于
 * revision_proposals（READY），绝不触碰主稿——采纳是另一个决定（NCS-016）。
 */
public class RevisionRepairService {

    public record RepairGeneration(String requestId, int generated,
                                   List<String> previewIds, List<Integer> chapters) {}

    private final ImpactAnalysisService impactAnalysis;
    private final AgentRevisionService agentRevision;
    private final RevisionRequestService requests;
    private final com.writing.novel.spi.NovelRepository repository;

    public RevisionRepairService(ImpactAnalysisService impactAnalysis,
                                 AgentRevisionService agentRevision,
                                 RevisionRequestService requests,
                                 com.writing.novel.spi.NovelRepository repository) {
        this.impactAnalysis = impactAnalysis;
        this.agentRevision = agentRevision;
        this.requests = requests;
        this.repository = repository;
    }

    public RepairGeneration generateRepairs(String projectId, String requestId,
                                             int revisedChapterIndex, String intent) {
        RevisionRequest request = requests.requireScopeApproved(projectId, requestId);
        var revised = agentRevisionChapter(projectId, revisedChapterIndex);
        var plan = impactAnalysis.analyze(projectId, revisedChapterIndex, revised.getVersion());

        List<String> previewIds = new ArrayList<>();
        List<Integer> chapters = new ArrayList<>();
        for (var item : plan.items()) {
            if (item.classification() != com.writing.novel.domain.ImpactItem.Classification.MUST_REPAIR) {
                continue;
            }
            Chapter target = agentRevisionChapter(projectId, item.chapterIndex());
            String instructions = buildInstructions(intent, item, request);
            var preview = agentRevision.preview(projectId, new AgentRevisionService.RevisionRequest(
                    com.writing.novel.agent.ContentRevisionAgent.TargetType.CHAPTER,
                    target.getId(), item.chapterIndex(), null,
                    instructions,
                    "rr:" + requestId + ":" + item.chapterIndex()));
            previewIds.add(preview.previewId());
            chapters.add(item.chapterIndex());
            EventLog.emit("REVISION_REPAIR_GENERATED", Map.of(
                    "projectId", projectId,
                    "requestId", requestId,
                    "chapterIndex", item.chapterIndex(),
                    "previewId", preview.previewId()));
        }
        return new RepairGeneration(requestId, previewIds.size(), previewIds, chapters);
    }

    private Chapter agentRevisionChapter(String projectId, int chapterIndex) {
        return repository.findChapterByIndex(projectId, chapterIndex)
                .orElseThrow(() -> new IllegalArgumentException("章节不存在: " + chapterIndex));
    }

    private String buildInstructions(String intent, com.writing.novel.domain.ImpactItem item,
                                     RevisionRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("【修订意图】").append(intent == null ? "" : intent).append("\n");
        sb.append("【影响原因】本章被判定为必须修复：\n");
        for (String reason : item.reasons()) {
            sb.append("- ").append(reason).append("\n");
        }
        sb.append("【受影响实体】").append(String.join("、", item.subjects())).append("\n");
        if (request.getProtectAnchorsJson() != null && !request.getProtectAnchorsJson().isBlank()
                && !"[]".equals(request.getProtectAnchorsJson())) {
            sb.append("【保护项（不得修改）】").append(request.getProtectAnchorsJson()).append("\n");
        }
        sb.append("请生成与修订意图一致、化解上述依赖冲突的整章修复候选；保留无需改动的内容。");
        return sb.toString();
    }
}
