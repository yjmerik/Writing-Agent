package com.writing.novel.domain;

/**
 * 场景结构类型——题材无关的抽象叙事功能分类。
 * 每一章可归类为一种主导场景类型，用于检测结构套路化。
 */
public enum SceneType {
    /** 对抗/博弈：谈判、辩论、智力较量、权力博弈 */
    CONFRONTATION("对抗博弈"),
    /** 行动/追逐/战斗：打斗、逃跑、物理冲突 */
    ACTION("行动战斗"),
    /** 调查/推理：搜集线索、分析证据、解谜 */
    INVESTIGATION("调查推理"),
    /** 情感/关系：人物间的情感互动、关系发展 */
    EMOTIONAL("情感关系"),
    /** 揭示/转折：真相揭露、重大转折、身份揭晓 */
    REVELATION("揭示转折"),
    /** 过渡/铺垫：场景转换、日常、信息交代 */
    TRANSITION("过渡铺垫"),
    /** 仪式/盛典：典礼、宴会、比试大会等群体场景 */
    CEREMONY("仪式盛典");

    private final String label;

    SceneType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public static SceneType fromString(String s) {
        if (s == null) return TRANSITION;
        String upper = s.trim().toUpperCase();
        for (SceneType t : values()) {
            if (t.name().equals(upper) || t.label.equals(s.trim())) return t;
        }
        // 宽松匹配
        for (SceneType t : values()) {
            if (upper.contains(t.name()) || s.contains(t.label)) return t;
        }
        return TRANSITION;
    }
}
