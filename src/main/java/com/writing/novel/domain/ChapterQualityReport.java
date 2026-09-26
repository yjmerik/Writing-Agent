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
    /** p32-05: 报告对应章节的当前版本；绑定版本≠当前版本的条目判过期。 */
    private int chapterVersion;
    private long staleIssueCount;
    private boolean reviewStale;
    /** p34-02（UI 验收 A3）：报告产出时绑定的章节版本（review.chapterVersion）；
     *  与当前稿不同即"旧稿报告"——前端据此显式提示，不得冒充当前审查。 */
    private Integer reportForVersion;
    private boolean reportCurrent = true;
    /** NCS-095: 字数趋势——本章字数低于前 3 章均值 50% 时为 true（只读提示，不阻断） */
    private boolean wordTrendWarning;
    /** NCS-095: 参与对比的前 3 章均值（不足 3 章历史时为 0，且不产生警告） */
    private int previousAvgWordCount;

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
    public int getChapterVersion() { return chapterVersion; }
    public void setChapterVersion(int v) { this.chapterVersion = v; }
    public long getStaleIssueCount() { return staleIssueCount; }
    public void setStaleIssueCount(long v) { this.staleIssueCount = v; }
    public boolean isReviewStale() { return reviewStale; }
    public void setReviewStale(boolean v) { this.reviewStale = v; }
    public Integer getReportForVersion() { return reportForVersion; }
    public void setReportForVersion(Integer v) { this.reportForVersion = v; }
    public boolean isReportCurrent() { return reportCurrent; }
    public void setReportCurrent(boolean v) { this.reportCurrent = v; }
    public boolean isWordTrendWarning() { return wordTrendWarning; }
    public void setWordTrendWarning(boolean v) { this.wordTrendWarning = v; }
    public int getPreviousAvgWordCount() { return previousAvgWordCount; }
    public void setPreviousAvgWordCount(int v) { this.previousAvgWordCount = v; }
}
