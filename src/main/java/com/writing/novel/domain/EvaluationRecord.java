package com.writing.novel.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 卷末质量评价记录。
 * 由 QualityEvaluationAgent 在每卷结束时生成，持久化以便下一卷引用。
 */
public class EvaluationRecord {
    private final String id;
    private final String projectId;
    private int volumeIndex;
    private Map<String, Integer> scores;     // {"consistency":6, "style":9, ...}
    private int overallScore;
    private String summary;                  // DeepSeek 评价原文
    private String improvementNotes;         // 针对低分维度的改进建议
    private Instant createdAt;

    public EvaluationRecord(String projectId, int volumeIndex,
                            Map<String, Integer> scores, int overallScore,
                            String summary, String improvementNotes) {
        this.id = UUID.randomUUID().toString();
        this.projectId = projectId;
        this.volumeIndex = volumeIndex;
        this.scores = scores;
        this.overallScore = overallScore;
        this.summary = summary;
        this.improvementNotes = improvementNotes;
        this.createdAt = Instant.now();
    }

    public String getId() { return id; }
    public String getProjectId() { return projectId; }
    public int getVolumeIndex() { return volumeIndex; }
    public Map<String, Integer> getScores() { return scores; }
    public int getOverallScore() { return overallScore; }
    public String getSummary() { return summary; }
    public String getImprovementNotes() { return improvementNotes; }
    public Instant getCreatedAt() { return createdAt; }

    /** 获取评分低于阈值的维度列表 */
    public List<String> getLowScoreDimensions(int threshold) {
        return scores.entrySet().stream()
                .filter(e -> e.getValue() < threshold)
                .map(Map.Entry::getKey)
                .toList();
    }

    /** 格式化改进建议（用于注入 WritingContext） */
    public String formatFeedback() {
        if (scores == null || scores.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("总评：").append(overallScore).append("/10\n");
        for (var entry : scores.entrySet()) {
            int score = entry.getValue();
            String mark = score >= 8 ? "✓" : (score >= 6 ? "△" : "✗");
            sb.append("■ ").append(entry.getKey()).append("(").append(score)
              .append("/10)").append(mark).append("\n");
        }
        if (improvementNotes != null && !improvementNotes.isBlank()) {
            sb.append("改进建议：").append(improvementNotes);
        }
        return sb.toString();
    }
}
