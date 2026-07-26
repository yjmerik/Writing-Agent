package com.writing.novel.domain;

import java.util.List;

/**
 * 读者检验报告——每章写完后由 ReaderReviewAgent 生成。
 */
public class ReviewReport {
    private final int chapterIndex;
    private int score;                  // 1-10
    private String summary;             // 简评
    private List<String> issues;        // 具体问题
    private String improvementSuggestion; // 改进建议

    public ReviewReport(int chapterIndex, int score, String summary,
                        List<String> issues, String improvementSuggestion) {
        this.chapterIndex = chapterIndex;
        this.score = score;
        this.summary = summary;
        this.issues = issues;
        this.improvementSuggestion = improvementSuggestion;
    }

    /** 是否通过（>=7分） */
    public boolean isPassed() { return score >= 7; }

    public int getChapterIndex() { return chapterIndex; }
    public int getScore() { return score; }
    public String getSummary() { return summary; }
    public List<String> getIssues() { return issues; }
    public String getImprovementSuggestion() { return improvementSuggestion; }
}
