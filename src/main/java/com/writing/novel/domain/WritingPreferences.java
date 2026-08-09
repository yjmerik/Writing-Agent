package com.writing.novel.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 写作偏好——作者可控制的写作参数。
 * 通过 WritingPreferencesAgent 自然语言交互修改，也可直接 REST API 调整。
 * 每项目一份，在项目创建时初始化默认值。
 */
public class WritingPreferences {
    private final String id;
    private final String projectId;

    // 对话控制
    private double dialogueDensity;               // 0.0-1.0，目标对话占比，默认 0.35
    private boolean enableDialogueEnforcement;     // 默认 true

    // 爽点控制
    private boolean enableClimaxRotation;          // 默认 true

    // 角色声音
    private boolean enableCharacterVoice;          // 默认 true

    // 引用素材
    private boolean enableKnowledgeQuotes;         // 默认 true
    private String preferredQuoteSources;          // JSON 数组 ["兵法","古籍","诗词"]

    // 过渡控制
    private boolean preventTransitionOverlap;      // 默认 true

    // 反馈闭环控制
    private boolean enableFeedbackInjection;       // 默认 true：注入历史 ContinuityIssue 反馈
    private int feedbackWindow;                     // 默认 10：回看多少章的反馈

    // 对话差异化模式
    private String dialogueDifferentiation;         // "contrast" | "individual" | "none"

    // 阈值参数化（p7-4）
    private int plotStallWindow;                    // 默认 3：连续多少章低张力视为停滞
    private int characterUpgradeSpan;               // 默认 2：角色升级跨度阈值
    private int minChapterLength;                   // 默认 2000：章节最低字数（出现跨度>=此值才升级）

    // p10-b1: 信息密度调控
    private int maxBeatsPerChapter;                 // 默认 4：每章核心事件/场景节拍上限

    // p11-F2: 字数动态化
    private int maxChapterLength;                   // 默认 6000：章节最高字数
    private String chapterLengthVariation;          // UNIFORM/VARIED，默认 VARIED（长短交替）

    // p11-F3: 节奏过快检测
    private int fastPaceWindow;                     // 默认 4：连续多少章高张力视为推进过快

    // p11.1-J3: 伏笔悬置告警
    private int foreshadowSuspenseThreshold;        // 默认 8：伏笔悬置超过 N 章未回收→告警

    // p12-K1: 百万字规模参数
    private int targetChapters;                     // 默认 24：目标总章数（百万字≈240章）
    private boolean layeredOutline;                 // 默认 false：是否使用分层大纲模式

    private Instant updatedAt;

    public WritingPreferences(String projectId) {
        this.id = UUID.randomUUID().toString();
        this.projectId = projectId;
        this.dialogueDensity = 0.35;
        this.enableDialogueEnforcement = true;
        this.enableClimaxRotation = true;
        this.enableCharacterVoice = true;
        this.enableKnowledgeQuotes = true;
        this.preferredQuoteSources = "[]";
        this.preventTransitionOverlap = true;
        this.enableFeedbackInjection = true;
        this.feedbackWindow = 10;
        this.dialogueDifferentiation = "contrast";
        this.plotStallWindow = 3;
        this.characterUpgradeSpan = 2;
        this.minChapterLength = 2000;
        this.maxBeatsPerChapter = 4;
        this.maxChapterLength = 6000;
        this.chapterLengthVariation = "VARIED";
        this.fastPaceWindow = 4;
        this.foreshadowSuspenseThreshold = 8;
        // mc-p1-05: 默认对齐百万字首版目标
        this.targetChapters = 80;
        this.layeredOutline = true;
        this.updatedAt = Instant.now();
    }

    /** 批量更新（从 WritingPreferencesAgent JSON 解析后调用） */
    public void applyUpdates(java.util.Map<String, Object> updates) {
        updates.forEach((key, value) -> {
            switch (key) {
                case "dialogueDensity" -> this.dialogueDensity = ((Number) value).doubleValue();
                case "enableDialogueEnforcement" -> this.enableDialogueEnforcement = (Boolean) value;
                case "enableClimaxRotation" -> this.enableClimaxRotation = (Boolean) value;
                case "enableCharacterVoice" -> this.enableCharacterVoice = (Boolean) value;
                case "enableKnowledgeQuotes" -> this.enableKnowledgeQuotes = (Boolean) value;
                case "preferredQuoteSources" -> {
                    if (value instanceof java.util.List) {
                        this.preferredQuoteSources = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(value).toString();
                    }
                }
                case "preventTransitionOverlap" -> this.preventTransitionOverlap = (Boolean) value;
                case "enableFeedbackInjection" -> this.enableFeedbackInjection = (Boolean) value;
                case "feedbackWindow" -> this.feedbackWindow = ((Number) value).intValue();
                case "dialogueDifferentiation" -> this.dialogueDifferentiation = (String) value;
                case "plotStallWindow" -> this.plotStallWindow = ((Number) value).intValue();
                case "characterUpgradeSpan" -> this.characterUpgradeSpan = ((Number) value).intValue();
                case "minChapterLength" -> this.minChapterLength = ((Number) value).intValue();
                case "maxBeatsPerChapter" -> this.maxBeatsPerChapter = ((Number) value).intValue();
                case "maxChapterLength" -> this.maxChapterLength = ((Number) value).intValue();
                case "chapterLengthVariation" -> this.chapterLengthVariation = (String) value;
                case "fastPaceWindow" -> this.fastPaceWindow = ((Number) value).intValue();
                case "foreshadowSuspenseThreshold" -> this.foreshadowSuspenseThreshold = ((Number) value).intValue();
                case "targetChapters" -> this.targetChapters = ((Number) value).intValue();
                case "layeredOutline" -> this.layeredOutline = (Boolean) value;
            }
        });
        this.updatedAt = Instant.now();
    }

    /** 转换为 Map（用于 Agent prompt 渲染） */
    public java.util.Map<String, Object> toMap() {
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        m.put("dialogueDensity", dialogueDensity);
        m.put("enableDialogueEnforcement", enableDialogueEnforcement);
        m.put("enableClimaxRotation", enableClimaxRotation);
        m.put("enableCharacterVoice", enableCharacterVoice);
        m.put("enableKnowledgeQuotes", enableKnowledgeQuotes);
        m.put("preferredQuoteSources", preferredQuoteSources);
        m.put("preventTransitionOverlap", preventTransitionOverlap);
        m.put("enableFeedbackInjection", enableFeedbackInjection);
        m.put("feedbackWindow", feedbackWindow);
        m.put("dialogueDifferentiation", dialogueDifferentiation);
        m.put("plotStallWindow", plotStallWindow);
        m.put("characterUpgradeSpan", characterUpgradeSpan);
        m.put("minChapterLength", minChapterLength);
        m.put("maxBeatsPerChapter", maxBeatsPerChapter);
        m.put("maxChapterLength", maxChapterLength);
        m.put("chapterLengthVariation", chapterLengthVariation);
        m.put("fastPaceWindow", fastPaceWindow);
        m.put("foreshadowSuspenseThreshold", foreshadowSuspenseThreshold);
        m.put("targetChapters", targetChapters);
        m.put("layeredOutline", layeredOutline);
        m.put("updatedAt", updatedAt.toString());
        return m;
    }

    // Getters / Setters

    public String getId() { return id; }
    public String getProjectId() { return projectId; }
    public double getDialogueDensity() { return dialogueDensity; }
    public void setDialogueDensity(double d) { this.dialogueDensity = d; this.updatedAt = Instant.now(); }
    public boolean isEnableDialogueEnforcement() { return enableDialogueEnforcement; }
    public void setEnableDialogueEnforcement(boolean b) { this.enableDialogueEnforcement = b; this.updatedAt = Instant.now(); }
    public boolean isEnableClimaxRotation() { return enableClimaxRotation; }
    public void setEnableClimaxRotation(boolean b) { this.enableClimaxRotation = b; this.updatedAt = Instant.now(); }
    public boolean isEnableCharacterVoice() { return enableCharacterVoice; }
    public void setEnableCharacterVoice(boolean b) { this.enableCharacterVoice = b; this.updatedAt = Instant.now(); }
    public boolean isEnableKnowledgeQuotes() { return enableKnowledgeQuotes; }
    public void setEnableKnowledgeQuotes(boolean b) { this.enableKnowledgeQuotes = b; this.updatedAt = Instant.now(); }
    public String getPreferredQuoteSources() { return preferredQuoteSources; }
    public void setPreferredQuoteSources(String s) { this.preferredQuoteSources = s; this.updatedAt = Instant.now(); }
    public boolean isPreventTransitionOverlap() { return preventTransitionOverlap; }
    public void setPreventTransitionOverlap(boolean b) { this.preventTransitionOverlap = b; this.updatedAt = Instant.now(); }
    public boolean isEnableFeedbackInjection() { return enableFeedbackInjection; }
    public void setEnableFeedbackInjection(boolean b) { this.enableFeedbackInjection = b; this.updatedAt = Instant.now(); }
    public int getFeedbackWindow() { return feedbackWindow; }
    public void setFeedbackWindow(int w) { this.feedbackWindow = w; this.updatedAt = Instant.now(); }
    public String getDialogueDifferentiation() { return dialogueDifferentiation; }
    public void setDialogueDifferentiation(String d) { this.dialogueDifferentiation = d; this.updatedAt = Instant.now(); }
    public int getPlotStallWindow() { return plotStallWindow; }
    public void setPlotStallWindow(int w) { this.plotStallWindow = w; this.updatedAt = Instant.now(); }
    public int getCharacterUpgradeSpan() { return characterUpgradeSpan; }
    public void setCharacterUpgradeSpan(int s) { this.characterUpgradeSpan = s; this.updatedAt = Instant.now(); }
    public int getMinChapterLength() { return minChapterLength; }
    public void setMinChapterLength(int l) { this.minChapterLength = l; this.updatedAt = Instant.now(); }
    public int getMaxBeatsPerChapter() { return maxBeatsPerChapter; }
    public void setMaxBeatsPerChapter(int b) { this.maxBeatsPerChapter = b; this.updatedAt = Instant.now(); }
    public int getMaxChapterLength() { return maxChapterLength; }
    public void setMaxChapterLength(int l) { this.maxChapterLength = l; this.updatedAt = Instant.now(); }
    public String getChapterLengthVariation() { return chapterLengthVariation; }
    public void setChapterLengthVariation(String v) { this.chapterLengthVariation = v; this.updatedAt = Instant.now(); }
    public int getFastPaceWindow() { return fastPaceWindow; }
    public void setFastPaceWindow(int w) { this.fastPaceWindow = w; this.updatedAt = Instant.now(); }
    public int getForeshadowSuspenseThreshold() { return foreshadowSuspenseThreshold; }
    public void setForeshadowSuspenseThreshold(int t) { this.foreshadowSuspenseThreshold = t; this.updatedAt = Instant.now(); }
    public int getTargetChapters() { return targetChapters; }
    public void setTargetChapters(int t) { this.targetChapters = t; this.updatedAt = Instant.now(); }
    public boolean isLayeredOutline() { return layeredOutline; }
    public void setLayeredOutline(boolean l) { this.layeredOutline = l; this.updatedAt = Instant.now(); }
    public Instant getUpdatedAt() { return updatedAt; }
}
