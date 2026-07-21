package com.writing.novel.domain;

/**
 * 图数据库实体类型。
 * 涵盖小说中所有可被提取和追踪的命名实体。
 */
public enum EntityType {
    CHARACTER,   // 人物（主角、配角、反派）
    ITEM,        // 物品（武器、丹药、法宝、信物）
    LOCATION,    // 地点（宗门、秘境、城池、洞府）
    TECHNIQUE,   // 功法/技能/法术/阵法
    FACTION,     // 势力/家族/组织
    EVENT,       // 历史事件/重要仪式
    CONCEPT      // 抽象概念/特殊规则
}
