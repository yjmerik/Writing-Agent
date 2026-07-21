package com.writing.novel.domain;

/**
 * 实体间关系类型。
 * 构成小说的关系图谱，用于实体召回和一致性维护。
 */
public enum RelationshipType {
    OWNS,        // A 拥有 B
    LOCATED_IN,  // A 位于 B
    MEMBER_OF,   // A 是 B 的成员
    CREATES,     // A 创造 B
    USES,        // A 使用 B
    ALLY,        // A 和 B 是盟友
    ENEMY,       // A 和 B 是敌对
    CONTAINS,    // A 包含 B
    PRODUCES,    // A 生产 B
    KNOWS,       // A 认识 B（非亲密关系）
    RELATED_TO   // A 与 B 有关联（通用）
}
