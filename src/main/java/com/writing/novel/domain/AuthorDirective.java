package com.writing.novel.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * p33-06（设计 5.3）：作者意见/要求——意见闭环的持久化对象。
 * kind: HARD 作者硬性要求（持续有效，自动流程不得关闭）/ ONCE 单次修改意见 /
 *       PREFERENCE 偏好（可撤销）。
 * status: OPEN 待处理 / APPLIED_SYSTEM 系统认为已落实（附证据，待作者确认） /
 *         CONFIRMED 作者确认已落实 / DISMISSED 已撤销（留痕）。
 * 「系统认为已落实」与「作者确认已落实」分开记录（设计不变量）。
 */
public class AuthorDirective {

    public enum Kind { HARD, ONCE, PREFERENCE }
    public enum Status { OPEN, APPLIED_SYSTEM, CONFIRMED, DISMISSED }

    private String id;
    private String projectId;
    private Kind kind;
    /** 作用章节；null=全书范围 */
    private Integer chapterIndex;
    private String content;
    private Status status = Status.OPEN;
    private String systemEvidence;
    private Integer systemEvidenceVersion;
    private Instant createdAt;
    private Instant confirmedAt;
    private Instant updatedAt;

    public AuthorDirective(String projectId, Kind kind, Integer chapterIndex, String content) {
        this.id = UUID.randomUUID().toString();
        this.projectId = projectId;
        this.kind = kind;
        this.chapterIndex = chapterIndex;
        this.content = content;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    /** 行映射完整构造。 */
    public AuthorDirective(String id, String projectId, Kind kind, Integer chapterIndex,
                           String content, Status status, String systemEvidence,
                           Integer systemEvidenceVersion, Instant createdAt,
                           Instant confirmedAt, Instant updatedAt) {
        this.id = id;
        this.projectId = projectId;
        this.kind = kind;
        this.chapterIndex = chapterIndex;
        this.content = content;
        this.status = status;
        this.systemEvidence = systemEvidence;
        this.systemEvidenceVersion = systemEvidenceVersion;
        this.createdAt = createdAt;
        this.confirmedAt = confirmedAt;
        this.updatedAt = updatedAt;
    }

    public void applySystemEvidence(String evidence, int chapterVersion) {
        if (status == Status.CONFIRMED || status == Status.DISMISSED) return;
        this.status = Status.APPLIED_SYSTEM;
        this.systemEvidence = evidence;
        this.systemEvidenceVersion = chapterVersion;
        this.updatedAt = Instant.now();
    }

    public void confirm() {
        this.status = Status.CONFIRMED;
        this.confirmedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    /** 仅软性意见（ONCE/PREFERENCE）可撤销；HARD 需走显式裁决通道。 */
    public boolean dismissible() {
        return kind != Kind.HARD && status != Status.CONFIRMED;
    }

    public void dismiss() {
        this.status = Status.DISMISSED;
        this.updatedAt = Instant.now();
    }

    public String getId() { return id; }
    public String getProjectId() { return projectId; }
    public Kind getKind() { return kind; }
    public Integer getChapterIndex() { return chapterIndex; }
    public String getContent() { return content; }
    public Status getStatus() { return status; }
    public String getSystemEvidence() { return systemEvidence; }
    public Integer getSystemEvidenceVersion() { return systemEvidenceVersion; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
