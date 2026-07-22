package com.writing.novel.domain;

/**
 * 爽点类型——用于追踪和轮换，避免连续多章使用相同爽点模式。
 */
public enum ClimaxType {
    INFO_GAP,            // 信息差：主角知道而角色不知道
    STRATEGIC,           // 智斗：用策略而非蛮力
    EMOTIONAL_REVERSAL,  // 情感反转：敌变友/友变敌/真相揭露
    RULE_EXPLOIT,        // 规则利用：利用世界观独特规则
    ANTI_EXPECTATION     // 反预期：建立预期后打破
}
