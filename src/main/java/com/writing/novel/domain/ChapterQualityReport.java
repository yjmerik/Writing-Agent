package com.writing.novel.domain;

import java.util.List;

/**
 * p11-I2: 聚合章节质量报告。
 * 把每章写完后的所有检测结果（读者评分 + ContinuityIssue + 场景类型 + 套路词数）
 * 聚合成一份统一报告，供前端展示和人工优化参考。
 */
public class ChapterQualityReport {
    private int chapterIndex;
    private String chapterTitle;
    private int wordCount;

    // 读者评审（可能为 null——尚未评价）
    private ChapterReview review;

    // 所有检测器发现的问题
    private List<ContinuityIssue> issues;

    // 场景类型
    private String sceneType;

    // 套路词统计
    private int clicheCount;
    private List<String> topCliches;
    private boolean needsManualReview;
    private boolean qualityRisk;

    public ChapterQualityReport(int chapterIndex, String chapterTitle, int wordCount) {
        this.chapterIndex = chapterIndex;
        this.chapterTitle = chapterTitle;
        this.wordCount = wordCount;
    }

    /** 综合质量等级：A(≥8) / B(6-7) / C(<6) */
    public String getGrade() {
        if (review == null) return "?";
        int s = review.getOverallScore();
        if (s >= 8) return "A";
        if (s >= 6) return "B";
        return "C";
    }

    /** 问题严重度统计 */
    public long getHighIssueCount() {
        return issues != null ? issues.stream()
                .filter(i -> i.getSeverity() == ContinuityIssue.Severity.HIGH)
                .filter(i -> !i.isResolved())
                .count() : 0;
    }

    public long getMediumIssueCount() {
        return issues != null ? issues.stream()
                .filter(i -> i.getSeverity() == ContinuityIssue.Severity.MEDIUM)
                .filter(i -> !i.isResolved())
                .count() : 0;
    }

    public int getChapterIndex() { return chapterIndex; }
    public String getChapterTitle() { return chapterTitle; }
    public int getWordCount() { return wordCount; }
    public ChapterReview getReview() { return review; }
    public void setReview(ChapterReview r) { this.review = r; }
    public List<ContinuityIssue> getIssues() { return issues; }
    public void setIssues(List<ContinuityIssue> issues) { this.issues = issues; }
    public String getSceneType() { return sceneType; }
    public void setSceneType(String s) { this.sceneType = s; }
    public int getClicheCount() { return clicheCount; }
    public void setClicheCount(int c) { this.clicheCount = c; }
    public List<String> getTopCliches() { return topCliches; }
    public void setTopCliches(List<String> c) { this.topCliches = c; }
    public boolean isNeedsManualReview() { return needsManualReview; }
    public void setNeedsManualReview(boolean v) { this.needsManualReview = v; }
    public boolean isQualityRisk() { return qualityRisk; }
    public void setQualityRisk(boolean v) { this.qualityRisk = v; }
}
