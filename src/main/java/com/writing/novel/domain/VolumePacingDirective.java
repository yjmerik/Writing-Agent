package com.writing.novel.domain;

import java.time.Instant;

/**
 * NCS-200: 卷级节奏指令——卷末聚合纹理指标/检测趋势/读者子分/回读节奏后持久化的
 * 下一卷设定点。迟滞原则：卷内设定点不变，只在卷边界调整。
 */
public class VolumePacingDirective {

    private Long id;
    private String projectId;
    private int volumeIndex;
    private int fromChapter;
    private int toChapter;
    private String metricsJson;   // 本卷 TEXTURE_METRICS 均值（JSON）
    private double readerAvg;     // 读者分均值（无评审= -1）
    private int reviewCount;
    private String trendWarning;  // 检测趋势告警（可空）
    private String pacingText;    // 回读 pacing 观察文本（可空）
    private String directiveText; // 下一卷设定点全文（注入 planVolume 梗概与写作约束）
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public int getVolumeIndex() { return volumeIndex; }
    public void setVolumeIndex(int volumeIndex) { this.volumeIndex = volumeIndex; }
    public int getFromChapter() { return fromChapter; }
    public void setFromChapter(int fromChapter) { this.fromChapter = fromChapter; }
    public int getToChapter() { return toChapter; }
    public void setToChapter(int toChapter) { this.toChapter = toChapter; }
    public String getMetricsJson() { return metricsJson; }
    public void setMetricsJson(String metricsJson) { this.metricsJson = metricsJson; }
    public double getReaderAvg() { return readerAvg; }
    public void setReaderAvg(double readerAvg) { this.readerAvg = readerAvg; }
    public int getReviewCount() { return reviewCount; }
    public void setReviewCount(int reviewCount) { this.reviewCount = reviewCount; }
    public String getTrendWarning() { return trendWarning; }
    public void setTrendWarning(String trendWarning) { this.trendWarning = trendWarning; }
    public String getPacingText() { return pacingText; }
    public void setPacingText(String pacingText) { this.pacingText = pacingText; }
    public String getDirectiveText() { return directiveText; }
    public void setDirectiveText(String directiveText) { this.directiveText = directiveText; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
