package com.writing.novel.memory;

import com.writing.novel.domain.Character;

import java.util.ArrayList;
import java.util.List;

/**
 * 写作上下文 — ContextAssembler 的组装结果。
 * 包含 spec 第 5 节公式的各部分，可转为 system/user prompt。
 */
public class WritingContext {

    private String styleSection;
    private String worldbuildingSection;
    private String volumeOutlineSection;
    private String recentSummarySection;
    private String recentTextSection;
    private List<Character> relevantCharacters = new ArrayList<>();
    private String relevantSettings;
    private String relevantForeshadowings;
    private String taskSection;
    private String premiseSection; // 故事原旨（总纲+构思），最高优先级，永不被裁剪
    private String entityConsistencySection;  // 图数据库：实体一致性提醒
    private String writingConstraints;        // 量化写作约束
    private String qualityFeedbackSection;    // 上卷质量评价反馈

    /** system prompt：故事原旨 + 风格 + 世界观 + 相关设定 + 出场人物 */
    public String toSystemPrompt() {
        StringBuilder sb = new StringBuilder();
        // 【故事原旨】最高优先级——让 LLM 永远知道"这本书讲什么"
        if (premiseSection != null && !premiseSection.isBlank()) {
            sb.append("【故事原旨（全书最高纲领，所有章节必须服务于这个核心）】\n")
              .append(premiseSection).append("\n\n");
        }
        if (styleSection != null && !styleSection.isBlank()) {
            sb.append("【文风要求】\n").append(styleSection).append("\n\n");
        }
        if (worldbuildingSection != null && !worldbuildingSection.isBlank()) {
            sb.append("【世界观设定】\n").append(worldbuildingSection).append("\n\n");
        }
        if (entityConsistencySection != null && !entityConsistencySection.isBlank()) {
            sb.append(entityConsistencySection).append("\n\n");
        }
        if (relevantSettings != null && !relevantSettings.isBlank()) {
            sb.append("【相关设定】\n").append(relevantSettings).append("\n\n");
        }
        if (!relevantCharacters.isEmpty()) {
            sb.append("【出场人物（必须严格遵守以下设定，不得改名或改变性格）】\n");
            for (Character c : relevantCharacters) {
                sb.append("■ ").append(c.getName()).append("（").append(c.getRole()).append("）\n");
                if (c.getPersonality() != null) sb.append("  性格：").append(c.getPersonality()).append("\n");
                if (c.getAbility() != null) sb.append("  能力：").append(c.getAbility()).append("\n");
                if (c.getMotivation() != null) sb.append("  动机：").append(c.getMotivation()).append("\n");
                if (c.getAppearance() != null) sb.append("  外貌：").append(c.getAppearance()).append("\n");
                if (c.getRelationships() != null) sb.append("  人物关系：").append(c.getRelationships()).append("\n");
                if (c.getGrowthArc() != null) sb.append("  成长弧：").append(c.getGrowthArc()).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    /** user prompt：本卷大纲 + 近期摘要 + 上章原文 + 伏笔 + 本章任务 */
    public String toUserPrompt() {
        StringBuilder sb = new StringBuilder();
        // 开头强锚定主角名，防止 LLM 从"上一章原文"中继承错误的名字（滚雪球效应）
        if (relevantCharacters != null && !relevantCharacters.isEmpty()) {
            var mainChars = relevantCharacters.stream()
                    .filter(c -> c.getRole() != null && c.getRole().contains("主角"))
                    .toList();
            if (!mainChars.isEmpty()) {
                sb.append("⚠️ 重要提醒：本章主角的名字是「")
                  .append(mainChars.get(0).getName()).append("」，")
                  .append("正文中必须使用这个名字，不得使用任何其他名字。\n\n");
            }
        }
        if (volumeOutlineSection != null && !volumeOutlineSection.isBlank()) {
            sb.append("【本卷大纲】\n").append(volumeOutlineSection).append("\n\n");
        }
        if (recentSummarySection != null && !recentSummarySection.isBlank()) {
            sb.append("【前情摘要】\n").append(recentSummarySection).append("\n\n");
        }
        if (recentTextSection != null && !recentTextSection.isBlank()) {
            sb.append("【上一章结尾（用于衔接）】\n").append(recentTextSection).append("\n\n");
        }
        if (relevantForeshadowings != null && !relevantForeshadowings.isBlank()) {
            sb.append("【待回收伏笔】\n").append(relevantForeshadowings).append("\n\n");
        }
        sb.append("【本章任务】\n").append(taskSection != null ? taskSection : "");
        if (qualityFeedbackSection != null && !qualityFeedbackSection.isBlank()) {
            sb.append("\n\n").append(qualityFeedbackSection);
        }
        if (writingConstraints != null && !writingConstraints.isBlank()) {
            sb.append("\n\n").append(writingConstraints);
        }
        return sb.toString().trim();
    }

    public String getStyleSection() { return styleSection; }
    public void setStyleSection(String s) { this.styleSection = s; }
    public String getWorldbuildingSection() { return worldbuildingSection; }
    public void setWorldbuildingSection(String s) { this.worldbuildingSection = s; }
    public String getVolumeOutlineSection() { return volumeOutlineSection; }
    public void setVolumeOutlineSection(String s) { this.volumeOutlineSection = s; }
    public String getRecentSummarySection() { return recentSummarySection; }
    public void setRecentSummarySection(String s) { this.recentSummarySection = s; }
    public String getRecentTextSection() { return recentTextSection; }
    public void setRecentTextSection(String s) { this.recentTextSection = s; }
    public List<Character> getRelevantCharacters() { return relevantCharacters; }
    public void setRelevantCharacters(List<Character> c) { this.relevantCharacters = c; }
    public String getRelevantSettings() { return relevantSettings; }
    public void setRelevantSettings(String s) { this.relevantSettings = s; }
    public String getRelevantForeshadowings() { return relevantForeshadowings; }
    public void setRelevantForeshadowings(String s) { this.relevantForeshadowings = s; }
    public String getTaskSection() { return taskSection; }
    public void setTaskSection(String s) { this.taskSection = s; }
    public String getPremiseSection() { return premiseSection; }
    public void setPremiseSection(String s) { this.premiseSection = s; }
    public String getEntityConsistencySection() { return entityConsistencySection; }
    public void setEntityConsistencySection(String s) { this.entityConsistencySection = s; }
    public String getWritingConstraints() { return writingConstraints; }
    public void setWritingConstraints(String s) { this.writingConstraints = s; }
    public String getQualityFeedbackSection() { return qualityFeedbackSection; }
    public void setQualityFeedbackSection(String s) { this.qualityFeedbackSection = s; }
}
