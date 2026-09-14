package com.writing.novel.domain;

import java.time.Instant;
import java.util.List;

/**
 * p38-01：计划变更凭证——篇幅变更预览→确认的持久凭证。
 * sourcePlanHash 覆盖目标/大纲/已写边界/承诺版本，输入变化即旧凭证失效。
 * status: PENDING（预览）| CONFIRMED（已生效）。预览内容不可被后续目标输入改写。
 * PromiseDecision 逐项审计：MIGRATE 迁移到 targetChapter / OPEN_END 接受开放结局（须显式豁免理由）。
 */
public class PlanChange {

    public enum Status { PENDING, CONFIRMED }

    public record PromiseDecision(String id, String planChangeId, String promiseId,
                                  String action, Integer targetChapter, String reason) {
        public static final String ACTION_MIGRATE = "MIGRATE";
        public static final String ACTION_OPEN_END = "OPEN_END";
    }

    public record AffectedPromise(String promiseId, String description,
                                  int plantedAtChapter, Integer expectedResolveChapter,
                                  boolean mustResolve) {
    }

    private final String previewId;
    private final String projectId;
    private final String sourcePlanHash;
    private final int targetChapters;
    private final int writtenBoundary;
    private final List<AffectedPromise> affectedPromises;
    private final List<Integer> prunedChapters;
    private final Status status;
    private final Instant createdAt;

    public PlanChange(String previewId, String projectId, String sourcePlanHash,
                      int targetChapters, int writtenBoundary,
                      List<AffectedPromise> affectedPromises, List<Integer> prunedChapters,
                      Status status, Instant createdAt) {
        this.previewId = previewId;
        this.projectId = projectId;
        this.sourcePlanHash = sourcePlanHash;
        this.targetChapters = targetChapters;
        this.writtenBoundary = writtenBoundary;
        this.affectedPromises = affectedPromises != null ? List.copyOf(affectedPromises) : List.of();
        this.prunedChapters = prunedChapters != null ? List.copyOf(prunedChapters) : List.of();
        this.status = status;
        this.createdAt = createdAt;
    }

    public String getPreviewId() { return previewId; }
    public String getProjectId() { return projectId; }
    public String getSourcePlanHash() { return sourcePlanHash; }
    public int getTargetChapters() { return targetChapters; }
    public int getWrittenBoundary() { return writtenBoundary; }
    public List<AffectedPromise> getAffectedPromises() { return affectedPromises; }
    public List<Integer> getPrunedChapters() { return prunedChapters; }
    public Status getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
