package com.writing.novel.domain;

/**
 * NCS-003（方案 §05/J02）: 创作约定聚合——作者与 Agent 之间的创作契约。
 *
 * <p>至少包含：作品原旨、类型与读者预期、主题张力、叙述视角、语言样本、篇幅区间、
 * 允许的自主程度、作者明确不想要的模式。契约允许随作品发展产生新版本（append-only 修订）。
 * 修订 0 表示"空契约"——新建作品后未填写，是合法状态（无正文也能启动创作流程）。
 */
public class AuthorIntent {

    /** 各文本字段上限（字符）；语言样本允许更长 */
    public static final int MAX_FIELD_CHARS = 8000;
    public static final int MAX_SAMPLE_CHARS = 20000;

    private String projectId;
    private int revision;
    private String premise = "";            // 作品原旨
    private String genreExpectation = "";   // 类型与读者预期
    private String themes = "";             // 主题张力
    private String pov = "";                // 叙述视角
    private String languageSamples = "";    // 作者认可的语言样本
    private String lengthRange = "";        // 篇幅区间
    private String autonomy = "";           // 允许的自主程度（委托边界）
    private String unwantedPatterns = "";   // 不想要的模式
    private String updatedAt = "";

    public boolean isEmpty() {
        return premise.isBlank() && genreExpectation.isBlank() && themes.isBlank()
                && pov.isBlank() && languageSamples.isBlank() && lengthRange.isBlank()
                && autonomy.isBlank() && unwantedPatterns.isBlank();
    }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public int getRevision() { return revision; }
    public void setRevision(int revision) { this.revision = revision; }
    public String getPremise() { return premise; }
    public void setPremise(String premise) { this.premise = premise; }
    public String getGenreExpectation() { return genreExpectation; }
    public void setGenreExpectation(String v) { this.genreExpectation = v; }
    public String getThemes() { return themes; }
    public void setThemes(String themes) { this.themes = themes; }
    public String getPov() { return pov; }
    public void setPov(String pov) { this.pov = pov; }
    public String getLanguageSamples() { return languageSamples; }
    public void setLanguageSamples(String v) { this.languageSamples = v; }
    public String getLengthRange() { return lengthRange; }
    public void setLengthRange(String v) { this.lengthRange = v; }
    public String getAutonomy() { return autonomy; }
    public void setAutonomy(String autonomy) { this.autonomy = autonomy; }
    public String getUnwantedPatterns() { return unwantedPatterns; }
    public void setUnwantedPatterns(String v) { this.unwantedPatterns = v; }
    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
}
