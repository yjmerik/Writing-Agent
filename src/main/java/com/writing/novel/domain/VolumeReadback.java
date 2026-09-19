package com.writing.novel.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * NCS-020（方案 §06/J08）：卷末回读报告——人物变化/节奏/承诺状态/偏离汇总/建议。
 * PENDING_REVIEW 阻断下一卷自动续写；作者确认（ACKNOWLEDGED）后放行。
 */
public class VolumeReadback {

    public static final String PENDING_REVIEW = "PENDING_REVIEW";
    public static final String ACKNOWLEDGED = "ACKNOWLEDGED";

    private String id;
    private String projectId;
    private int volumeIndex;
    private String reportJson;
    private String status;
    private Instant acknowledgedAt;
    private Instant createdAt;

    public VolumeReadback() {
        this.id = UUID.randomUUID().toString();
        this.status = PENDING_REVIEW;
        this.createdAt = Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public int getVolumeIndex() { return volumeIndex; }
    public void setVolumeIndex(int volumeIndex) { this.volumeIndex = volumeIndex; }
    public String getReportJson() { return reportJson; }
    public void setReportJson(String reportJson) { this.reportJson = reportJson; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getAcknowledgedAt() { return acknowledgedAt; }
    public void setAcknowledgedAt(Instant acknowledgedAt) { this.acknowledgedAt = acknowledgedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
