package com.writing.novel.domain;

import java.time.Instant;

/**
 * p38-09：版本绑定的问题裁决——误报/例外/延期，绑定章节身份。
 * action: FALSE_POSITIVE 误报 / EXCEPTION 作者接受风险 / DEFER 延期到 deferUntilChapter。
 * 原问题不删除；章内容变动（identity 变化）不继承旧裁决，须重新确认。
 */
public class ReviewDisposition {

    public enum Action { FALSE_POSITIVE, EXCEPTION, DEFER }

    private final String id;
    private final String projectId;
    private final String issueId;
    private final String issueFingerprint;
    private final ChapterApproval.Identity identity;
    private final Action action;
    private final String reason;
    private final Integer deferUntilChapter;
    private final Instant createdAt;

    public ReviewDisposition(String id, String projectId, String issueId, String issueFingerprint,
                              ChapterApproval.Identity identity, Action action, String reason,
                              Integer deferUntilChapter, Instant createdAt) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("裁决必须说明理由");
        }
        if (action == Action.DEFER && deferUntilChapter == null) {
            throw new IllegalArgumentException("DEFER 必须指定 deferUntilChapter");
        }
        this.id = id;
        this.projectId = projectId;
        this.issueId = issueId;
        this.issueFingerprint = issueFingerprint;
        this.identity = identity;
        this.action = action;
        this.reason = reason;
        this.deferUntilChapter = deferUntilChapter;
        this.createdAt = createdAt;
    }

    public boolean matchesIdentity(ChapterApproval.Identity current) {
        return this.identity.equals(current);
    }

    public String getId() { return id; }
    public String getProjectId() { return projectId; }
    public String getIssueId() { return issueId; }
    public String getIssueFingerprint() { return issueFingerprint; }
    public ChapterApproval.Identity getIdentity() { return identity; }
    public Action getAction() { return action; }
    public String getReason() { return reason; }
    public Integer getDeferUntilChapter() { return deferUntilChapter; }
    public Instant getCreatedAt() { return createdAt; }
}
