package com.writing.novel.domain;

import java.time.Instant;

/**
 * p38-05：不可变成书版本——批准后导出固定字节，后续修改不影响历史下载。
 * manifestHash = 按章节号排序的规范 JSON 全文 SHA-256。
 * state: DRAFT → APPROVED（批准绑定 manifestHash，重读时验证未变）。
 */
public class BookEdition {

    public enum State { DRAFT, APPROVED }

    /** 逐章 manifest 行——正文物化副本，不依赖会被后来覆盖的 chapters 表。 */
    public record EditionChapter(int chapterIndex, int version, String title,
                                 String content, String contentHash,
                                 String approvalId, String planRef) {
    }

    private final String editionId;
    private final String projectId;
    private final String manifestHash;
    private State state;
    private final Instant createdAt;
    private Instant approvedAt;

    public BookEdition(String editionId, String projectId, String manifestHash,
                       State state, Instant createdAt, Instant approvedAt) {
        this.editionId = editionId;
        this.projectId = projectId;
        this.manifestHash = manifestHash;
        this.state = state;
        this.createdAt = createdAt;
        this.approvedAt = approvedAt;
    }

    public void approve(String manifestHash) {
        if (this.state == State.APPROVED) throw new IllegalStateException("已批准");
        if (!this.manifestHash.equals(manifestHash)) {
            throw new IllegalStateException("manifestHash 不匹配，拒绝批准");
        }
        this.state = State.APPROVED;
        this.approvedAt = Instant.now();
    }

    public String getEditionId() { return editionId; }
    public String getProjectId() { return projectId; }
    public String getManifestHash() { return manifestHash; }
    public State getState() { return state; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getApprovedAt() { return approvedAt; }
}
