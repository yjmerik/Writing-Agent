package com.writing.novel.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * p11-I1: 章节评价持久化对象。
 * 存储每章的读者评审报告（多维度评分 + 问题 + 改进建议）。
 */
public class ChapterReview {
    private String id;
    private String projectId;
    private int chapterIndex;
    private int overallScore;          // 1-10
    private int engagement;            // 吸引力
    private int coherence;             // 连贯性
    private int character;             // 角色塑造
    private int hook;                  // 章末钩子
    private String summary;            // 简评
    private String issues;             // 问题列表（分号分隔）
    private String improvement;        // 改进建议
    private Instant createdAt;

    public ChapterReview(String projectId, int chapterIndex) {
        this.id = UUID.randomUUID().toString();
        this.projectId = projectId;
        this.chapterIndex = chapterIndex;
        this.createdAt = Instant.now();
    }

    /** 从 ReviewReport 构造持久化对象 */
    public static ChapterReview from(String projectId, ReviewReport report) {
        ChapterReview r = new ChapterReview(projectId, report.getChapterIndex());
        r.overallScore = report.getScore();
        r.engagement = report.getEngagement();
        r.coherence = report.getCoherence();
        r.character = report.getCharacter();
        r.hook = report.getHook();
        r.summary = report.getSummary();
        r.issues = report.getIssues() != null ? String.join("；", report.getIssues()) : "";
        r.improvement = report.getImprovementSuggestion();
        return r;
    }

    /** 是否通过（>=7分） */
    public boolean isPassed() { return overallScore >= 7; }

    public String getId() { return id; }
    public String getProjectId() { return projectId; }
    public int getChapterIndex() { return chapterIndex; }
    public int getOverallScore() { return overallScore; }
    public void setOverallScore(int s) { this.overallScore = s; }
    public int getEngagement() { return engagement; }
    public void setEngagement(int e) { this.engagement = e; }
    public int getCoherence() { return coherence; }
    public void setCoherence(int c) { this.coherence = c; }
    public int getCharacter() { return character; }
    public void setCharacter(int c) { this.character = c; }
    public int getHook() { return hook; }
    public void setHook(int h) { this.hook = h; }
    public String getSummary() { return summary; }
    public void setSummary(String s) { this.summary = s; }
    public String getIssues() { return issues; }
    public void setIssues(String i) { this.issues = i; }
    public String getImprovement() { return improvement; }
    public void setImprovement(String i) { this.improvement = i; }
    public Instant getCreatedAt() { return createdAt; }
}
