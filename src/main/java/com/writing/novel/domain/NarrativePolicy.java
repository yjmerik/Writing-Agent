package com.writing.novel.domain;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * NCS-190: 叙事策略——叙事质感的区间设定点，单一事实源。
 *
 * <p>spec docs/specs/2026-10-09-narrative-quality-architecture.md §1。所有字段均为
 * <b>区间</b>（双向），供后续约束组装（NCS-191）、模板渲染（NCS-192）、场景规划
 * （NCS-193）与质感门禁（NCS-195）统一消费，替代散落且单向的硬编码约束。
 *
 * <p>题材默认两档：HISTORICAL（慢节奏质感，来自《山河执棋人》实测病灶的对症区间：
 * 对话段占比 77%→≤55%、均段 17.3 字→叙述块≥120 字、零转场→≥80 字）与其余题材
 * 「现状等效」宽松区间（不约束，保证快节奏 golden 不回归）。节拍上限镜像
 * GenreRuleProvider.getDefaultMaxBeats 的题材分布。
 */
public class NarrativePolicy {

    // 对话段占比区间（含引号段落 / 总段落）
    private double dialogueParagraphRatioMin;
    private double dialogueParagraphRatioMax;
    // 引号内字符占比区间（StyleMetricsService.dialogueDensity 同口径）
    private double dialogueCharRatioMin;
    private double dialogueCharRatioMax;
    // 成段叙述最短块（连续非对白段落总字数下限；治"叙述标签化碎片"）
    private int narrationRunMinChars;
    // 节拍数区间（按章型：标准/喘息/开篇）
    private int beatsStandardMin;
    private int beatsStandardMax;
    private int beatsBreatherMin;
    private int beatsBreatherMax;
    private int beatsOpeningMin;
    private int beatsOpeningMax;
    // 每节拍最低字数预算（计划侧节奏算术的算术底）
    private int charsPerBeatMin;
    // 场景转场最短字数（时间/空间/感官落点过渡）
    private int transitionMinChars;
    // 每章场景数区间
    private int sceneBreaksMin;
    private int sceneBreaksMax;
    // 单段上限（现状 chapter-writing.st 已有 200 字）
    private int paragraphMaxChars;
    // 章首定场上限许可（0=禁止铺陈开场，>0=允许但不超过该字数）
    private int openingSettingAllowance;
    // 每场景感官锚点下限
    private int sensoryAnchorPerScene;

    private Instant updatedAt;

    /** HISTORICAL：慢节奏质感区间；其余题材：现状等效宽松区间（不约束） */
    public static NarrativePolicy forGenre(GenreProfile genre) {
        NarrativePolicy p = new NarrativePolicy();
        boolean textured = genre == GenreProfile.HISTORICAL;
        // 节拍上限镜像 GenreRuleProvider.getDefaultMaxBeats（HISTORICAL/MYSTERY/THRILLER=3，其余=4）
        int beatsMax = (genre == GenreProfile.HISTORICAL || genre == GenreProfile.MYSTERY
                || genre == GenreProfile.THRILLER) ? 3 : 4;
        if (textured) {
            p.dialogueParagraphRatioMin = 0.30;
            p.dialogueParagraphRatioMax = 0.55;
            p.dialogueCharRatioMin = 0.25;
            p.dialogueCharRatioMax = 0.50;
            p.narrationRunMinChars = 120;
            p.beatsStandardMin = 2;
            p.beatsStandardMax = 3;
            p.beatsBreatherMin = 1;
            p.beatsBreatherMax = 2;
            p.beatsOpeningMin = 2;
            p.beatsOpeningMax = 3;
            p.charsPerBeatMin = 1200;
            p.transitionMinChars = 80;
            p.sceneBreaksMin = 2;
            p.sceneBreaksMax = 4;
            p.paragraphMaxChars = 200;
            p.openingSettingAllowance = 150;
            p.sensoryAnchorPerScene = 2;
        } else {
            p.dialogueParagraphRatioMin = 0.0;
            p.dialogueParagraphRatioMax = 1.0;
            p.dialogueCharRatioMin = 0.0;
            p.dialogueCharRatioMax = 1.0;
            p.narrationRunMinChars = 0;
            p.beatsStandardMin = 1;
            p.beatsStandardMax = beatsMax;
            p.beatsBreatherMin = 1;
            p.beatsBreatherMax = beatsMax;
            p.beatsOpeningMin = 1;
            p.beatsOpeningMax = beatsMax;
            p.charsPerBeatMin = 0;
            p.transitionMinChars = 0;
            p.sceneBreaksMin = 1;
            p.sceneBreaksMax = 6;
            p.paragraphMaxChars = 200;
            p.openingSettingAllowance = 0;
            p.sensoryAnchorPerScene = 0;
        }
        p.updatedAt = Instant.now();
        return p;
    }

    /** 区间与数值合法性校验（REST/覆盖写入时调用；违规抛 IllegalArgumentException） */
    public void validate() {
        ratio("dialogueParagraphRatio", dialogueParagraphRatioMin, dialogueParagraphRatioMax);
        ratio("dialogueCharRatio", dialogueCharRatioMin, dialogueCharRatioMax);
        range("beatsStandard", beatsStandardMin, beatsStandardMax);
        range("beatsBreather", beatsBreatherMin, beatsBreatherMax);
        range("beatsOpening", beatsOpeningMin, beatsOpeningMax);
        range("sceneBreaks", sceneBreaksMin, sceneBreaksMax);
        nonNegative("narrationRunMinChars", narrationRunMinChars);
        nonNegative("charsPerBeatMin", charsPerBeatMin);
        nonNegative("transitionMinChars", transitionMinChars);
        nonNegative("paragraphMaxChars", paragraphMaxChars);
        nonNegative("openingSettingAllowance", openingSettingAllowance);
        nonNegative("sensoryAnchorPerScene", sensoryAnchorPerScene);
    }

    private void ratio(String name, double min, double max) {
        if (min < 0 || max > 1 || min > max) {
            throw new IllegalArgumentException(name + " 区间非法: [" + min + ", " + max + "]");
        }
    }

    private void range(String name, int min, int max) {
        if (min < 0 || min > max) {
            throw new IllegalArgumentException(name + " 区间非法: [" + min + ", " + max + "]");
        }
    }

    private void nonNegative(String name, int v) {
        if (v < 0) throw new IllegalArgumentException(name + " 不能为负: " + v);
    }

    /** 项目级覆盖写入（与 WritingPreferences.applyUpdates 同风格；未知键忽略） */
    public void applyUpdates(Map<String, Object> updates) {
        updates.forEach((key, value) -> {
            switch (key) {
                case "dialogueParagraphRatioMin" -> this.dialogueParagraphRatioMin = ((Number) value).doubleValue();
                case "dialogueParagraphRatioMax" -> this.dialogueParagraphRatioMax = ((Number) value).doubleValue();
                case "dialogueCharRatioMin" -> this.dialogueCharRatioMin = ((Number) value).doubleValue();
                case "dialogueCharRatioMax" -> this.dialogueCharRatioMax = ((Number) value).doubleValue();
                case "narrationRunMinChars" -> this.narrationRunMinChars = ((Number) value).intValue();
                case "beatsStandardMin" -> this.beatsStandardMin = ((Number) value).intValue();
                case "beatsStandardMax" -> this.beatsStandardMax = ((Number) value).intValue();
                case "beatsBreatherMin" -> this.beatsBreatherMin = ((Number) value).intValue();
                case "beatsBreatherMax" -> this.beatsBreatherMax = ((Number) value).intValue();
                case "beatsOpeningMin" -> this.beatsOpeningMin = ((Number) value).intValue();
                case "beatsOpeningMax" -> this.beatsOpeningMax = ((Number) value).intValue();
                case "charsPerBeatMin" -> this.charsPerBeatMin = ((Number) value).intValue();
                case "transitionMinChars" -> this.transitionMinChars = ((Number) value).intValue();
                case "sceneBreaksMin" -> this.sceneBreaksMin = ((Number) value).intValue();
                case "sceneBreaksMax" -> this.sceneBreaksMax = ((Number) value).intValue();
                case "paragraphMaxChars" -> this.paragraphMaxChars = ((Number) value).intValue();
                case "openingSettingAllowance" -> this.openingSettingAllowance = ((Number) value).intValue();
                case "sensoryAnchorPerScene" -> this.sensoryAnchorPerScene = ((Number) value).intValue();
                default -> { /* 未知键忽略：前向兼容 */ }
            }
        });
        this.updatedAt = Instant.now();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new HashMap<>();
        m.put("dialogueParagraphRatioMin", dialogueParagraphRatioMin);
        m.put("dialogueParagraphRatioMax", dialogueParagraphRatioMax);
        m.put("dialogueCharRatioMin", dialogueCharRatioMin);
        m.put("dialogueCharRatioMax", dialogueCharRatioMax);
        m.put("narrationRunMinChars", narrationRunMinChars);
        m.put("beatsStandardMin", beatsStandardMin);
        m.put("beatsStandardMax", beatsStandardMax);
        m.put("beatsBreatherMin", beatsBreatherMin);
        m.put("beatsBreatherMax", beatsBreatherMax);
        m.put("beatsOpeningMin", beatsOpeningMin);
        m.put("beatsOpeningMax", beatsOpeningMax);
        m.put("charsPerBeatMin", charsPerBeatMin);
        m.put("transitionMinChars", transitionMinChars);
        m.put("sceneBreaksMin", sceneBreaksMin);
        m.put("sceneBreaksMax", sceneBreaksMax);
        m.put("paragraphMaxChars", paragraphMaxChars);
        m.put("openingSettingAllowance", openingSettingAllowance);
        m.put("sensoryAnchorPerScene", sensoryAnchorPerScene);
        return m;
    }

    // Getters / Setters

    public double getDialogueParagraphRatioMin() { return dialogueParagraphRatioMin; }
    public void setDialogueParagraphRatioMin(double v) { this.dialogueParagraphRatioMin = v; }
    public double getDialogueParagraphRatioMax() { return dialogueParagraphRatioMax; }
    public void setDialogueParagraphRatioMax(double v) { this.dialogueParagraphRatioMax = v; }
    public double getDialogueCharRatioMin() { return dialogueCharRatioMin; }
    public void setDialogueCharRatioMin(double v) { this.dialogueCharRatioMin = v; }
    public double getDialogueCharRatioMax() { return dialogueCharRatioMax; }
    public void setDialogueCharRatioMax(double v) { this.dialogueCharRatioMax = v; }
    public int getNarrationRunMinChars() { return narrationRunMinChars; }
    public void setNarrationRunMinChars(int v) { this.narrationRunMinChars = v; }
    public int getBeatsStandardMin() { return beatsStandardMin; }
    public void setBeatsStandardMin(int v) { this.beatsStandardMin = v; }
    public int getBeatsStandardMax() { return beatsStandardMax; }
    public void setBeatsStandardMax(int v) { this.beatsStandardMax = v; }
    public int getBeatsBreatherMin() { return beatsBreatherMin; }
    public void setBeatsBreatherMin(int v) { this.beatsBreatherMin = v; }
    public int getBeatsBreatherMax() { return beatsBreatherMax; }
    public void setBeatsBreatherMax(int v) { this.beatsBreatherMax = v; }
    public int getBeatsOpeningMin() { return beatsOpeningMin; }
    public void setBeatsOpeningMin(int v) { this.beatsOpeningMin = v; }
    public int getBeatsOpeningMax() { return beatsOpeningMax; }
    public void setBeatsOpeningMax(int v) { this.beatsOpeningMax = v; }
    public int getCharsPerBeatMin() { return charsPerBeatMin; }
    public void setCharsPerBeatMin(int v) { this.charsPerBeatMin = v; }
    public int getTransitionMinChars() { return transitionMinChars; }
    public void setTransitionMinChars(int v) { this.transitionMinChars = v; }
    public int getSceneBreaksMin() { return sceneBreaksMin; }
    public void setSceneBreaksMin(int v) { this.sceneBreaksMin = v; }
    public int getSceneBreaksMax() { return sceneBreaksMax; }
    public void setSceneBreaksMax(int v) { this.sceneBreaksMax = v; }
    public int getParagraphMaxChars() { return paragraphMaxChars; }
    public void setParagraphMaxChars(int v) { this.paragraphMaxChars = v; }
    public int getOpeningSettingAllowance() { return openingSettingAllowance; }
    public void setOpeningSettingAllowance(int v) { this.openingSettingAllowance = v; }
    public int getSensoryAnchorPerScene() { return sensoryAnchorPerScene; }
    public void setSensoryAnchorPerScene(int v) { this.sensoryAnchorPerScene = v; }
    public Instant getUpdatedAt() { return updatedAt; }
}
