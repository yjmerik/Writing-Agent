package com.writing.novel.domain;

import java.util.List;

/**
 * 读者检验报告——每章写完后由 ReaderReviewAgent 生成。
 * p11-I1: 增加多维度评分（engagement/coherence/character/hook），支持持久化。
 */
public class ReviewReport {
    private final int chapterIndex;
    private int score;                  // overall 1-10
    private int engagement;             // 吸引力 1-10
    private int coherence;              // 连贯性 1-10
    private int character;              // 角色塑造 1-10
    private int hook;                   // 章末钩子 1-10
    private int style;                  // NCS-125: 文风一致性 1-10
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

    public ReviewReport(int chapterIndex, int score, int engagement, int coherence,
                        int character, int hook, String summary,
                        List<String> issues, String improvementSuggestion) {
        this(chapterIndex, score, engagement, coherence, character, hook, 0, summary, issues, improvementSuggestion);
    }

    /** NCS-125: 带 style 维度的构造 */
    public ReviewReport(int chapterIndex, int score, int engagement, int coherence,
                        int character, int hook, int style, String summary,
                        List<String> issues, String improvementSuggestion) {
        this.chapterIndex = chapterIndex;
        this.score = score;
        this.engagement = engagement;
        this.coherence = coherence;
        this.character = character;
        this.style = style;
        this.hook = hook;
        this.summary = summary;
        this.issues = issues;
        this.improvementSuggestion = improvementSuggestion;
    }

    /** 是否通过（>=7分） */
    public boolean isPassed() { return score >= 7; }

    public int getChapterIndex() { return chapterIndex; }
    public int getScore() { return score; }
    public int getEngagement() { return engagement; }
    public int getCoherence() { return coherence; }
    public int getCharacter() { return character; }
    public int getHook() { return hook; }
    public int getStyle() { return style; }
    public String getSummary() { return summary; }
    public List<String> getIssues() { return issues; }
    public String getImprovementSuggestion() { return improvementSuggestion; }
}
