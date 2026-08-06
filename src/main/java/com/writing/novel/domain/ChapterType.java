package com.writing.novel.domain;

/**
 * p11-F1: 章节类型——区分高潮章/发展章/喘息章/过渡章。
 * 用于节奏自适应：不同类型的章节有不同的字数/结构/爽点约束。
 */
public enum ChapterType {
    /** 高潮章：核心冲突爆发，tension≥8，四段式结构，≥2爽点 */
    CLIMAX("高潮章"),
    /** 发展章：推进剧情，tension 5-7，弹性结构，1爽点 */
    DEVELOPMENT("发展章"),
    /** 喘息章：不推主线，tension≤3，自由结构，0爽点，深化角色/世界观 */
    BREATHING("喘息章"),
    /** 过渡章：场景转换/信息铺垫，tension 3-5，自由结构 */
    TRANSITION("过渡章");

    private final String label;

    ChapterType(String label) { this.label = label; }
    public String getLabel() { return label; }

    /** 根据张力等级推断章节类型 */
    public static ChapterType fromTension(int tension) {
        if (tension >= 8) return CLIMAX;
        if (tension >= 5) return DEVELOPMENT;
        if (tension >= 3) return TRANSITION;
        return BREATHING;
    }
}
