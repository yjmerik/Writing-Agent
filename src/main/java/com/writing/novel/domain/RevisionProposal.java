package com.writing.novel.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

/**
 * p37-01（A5）：持久修订候选——候选是 DB 资源而非弹窗内存，刷新/重启后可恢复。
 * status 生命周期：READY（可应用）→ APPLIED（正文已推进，终态）/ STALE（来源已变化）
 * / CANCELLED（作者或系统取消，留记录）。APPLIED 不回到 READY；无 TTL 自动删除未处理候选。
 *
 * sourceHash 与 p36 正文身份兼容：章节对象用 ChapterApproval.contentHash（标题+正文长度前缀），
 * 非章节对象（世界观/人物/卷）用规范 JSON 全文 SHA-256（canonicalJsonSha256）。
 * beforeJson/afterJson/instructions/resultJson 只存 DB，不写应用日志。
 */
public class RevisionProposal {

    public enum TargetType { CHAPTER, PASSAGE, WORLD, CHARACTER, VOLUME }

    public enum Status { READY, APPLIED, STALE, CANCELLED }

    private final String id;
    private final String projectId;
    private final TargetType targetType;
    private final String targetId;
    private final Integer chapterIndex;
    private final Integer passageIndex;
    private final int baseVersion;
    private final String sourceHash;
    private final String instructions;
    private final String beforeJson;
    private final String afterJson;
    private String summary;
    private Status status;
    private final String requestKey;
    private String resultJson;
    private final Instant createdAt;
    private Instant updatedAt;

    public RevisionProposal(String id, String projectId, TargetType targetType, String targetId,
                            Integer chapterIndex, Integer passageIndex, int baseVersion,
                            String sourceHash, String instructions,
                            String beforeJson, String afterJson, String summary,
                            Status status, String requestKey, String resultJson,
                            Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.projectId = projectId;
        this.targetType = targetType;
        this.targetId = targetId;
        this.chapterIndex = chapterIndex;
        this.passageIndex = passageIndex;
        this.baseVersion = baseVersion;
        this.sourceHash = sourceHash;
        this.instructions = instructions;
        this.beforeJson = beforeJson;
        this.afterJson = afterJson;
        this.summary = summary;
        this.status = status;
        this.requestKey = requestKey;
        this.resultJson = resultJson;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** 生成中的候选由仓储以非 READY 初始状态落盘；可应用前显式转 READY。 */
    public void markReady() {
        requireNotApplied("已应用的候选不得回到 READY");
        this.status = Status.READY;
        touch();
    }

    public void markStale() {
        requireNotApplied("已应用的候选不得标记 STALE");
        this.status = Status.STALE;
        touch();
    }

    public void cancel() {
        requireNotApplied("已应用的候选不得取消");
        this.status = Status.CANCELLED;
        touch();
    }

    /** 应用完成：终态，附带结果 JSON（如新稿身份）。 */
    public void markApplied(String resultJson) {
        this.status = Status.APPLIED;
        this.resultJson = resultJson;
        touch();
    }

    public void setSummary(String summary) {
        this.summary = summary;
        touch();
    }

    private void requireNotApplied(String message) {
        if (this.status == Status.APPLIED) throw new IllegalStateException(message);
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    /** 章节来源指纹：与 ChapterApproval.contentHash 同构。 */
    public static String chapterSourceHash(String title, String content) {
        return ChapterApproval.contentHash(title, content);
    }

    /** 非章节对象来源指纹：规范 JSON 全文 SHA-256（64 位小写 hex）。 */
    public static String canonicalJsonSha256(String canonicalJson) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((canonicalJson == null ? "" : canonicalJson)
                            .getBytes(StandardCharsets.UTF_8));
            final String hexDigits = "0123456789abcdef";
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(hexDigits.charAt((b >> 4) & 0xF));
                hex.append(hexDigits.charAt(b & 0xF));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("canonicalJsonSha256 计算失败", e);
        }
    }

    public String getId() { return id; }
    public String getProjectId() { return projectId; }
    public TargetType getTargetType() { return targetType; }
    public String getTargetId() { return targetId; }
    public Integer getChapterIndex() { return chapterIndex; }
    public Integer getPassageIndex() { return passageIndex; }
    public int getBaseVersion() { return baseVersion; }
    public String getSourceHash() { return sourceHash; }
    public String getInstructions() { return instructions; }
    public String getBeforeJson() { return beforeJson; }
    public String getAfterJson() { return afterJson; }
    public String getSummary() { return summary; }
    public Status getStatus() { return status; }
    public String getRequestKey() { return requestKey; }
    public String getResultJson() { return resultJson; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
