package com.writing.novel.domain;

import java.time.Instant;

/**
 * NCS-052: 断言支持组——一条事实断言的支持关系。
 *
 * <p>语义：断言成立需要
 * <ul>
 *   <li>AND 前提：全部前提支持必须有效（缺一即断言失效）</li>
 *   <li>OR 来源：任一来源支持有效即可（余一保留）</li>
 * </ul>
 * 支持历史不可变（每行一条支持记录，失效经 status 标注，不删行）。
 * 快照归属：supportSnapshot 记录支持成立时的证据指纹（contentHash）。
 */
public class AssertionSupport {

    public enum Link { AND_PREMISE, OR_SOURCE }
    public enum Status { ACTIVE, WITHDRAWN }

    private Long id;
    private final String projectId;
    private final String factId;          // 被支持的断言（事实 id）
    private final Link link;
    private final String supportFactId;   // 支持项（另一条事实 id 或证据来源标识）
    private final String supportSnapshot; // 支持成立时的证据指纹（不可变快照归属）
    private Status status;
    private final Instant createdAt;

    public AssertionSupport(String projectId, String factId, Link link,
                            String supportFactId, String supportSnapshot) {
        this.projectId = projectId;
        this.factId = factId;
        this.link = link;
        this.supportFactId = supportFactId;
        this.supportSnapshot = supportSnapshot;
        this.status = Status.ACTIVE;
        this.createdAt = Instant.now();
    }

    /** 持久层恢复 */
    public AssertionSupport(Long id, String projectId, String factId, Link link,
                            String supportFactId, String supportSnapshot,
                            Status status, Instant createdAt) {
        this.id = id;
        this.projectId = projectId;
        this.factId = factId;
        this.link = link;
        this.supportFactId = supportFactId;
        this.supportSnapshot = supportSnapshot;
        this.status = status;
        this.createdAt = createdAt;
    }

    public void withdraw() {
        this.status = Status.WITHDRAWN;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getProjectId() { return projectId; }
    public String getFactId() { return factId; }
    public Link getLink() { return link; }
    public String getSupportFactId() { return supportFactId; }
    public String getSupportSnapshot() { return supportSnapshot; }
    public Status getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
