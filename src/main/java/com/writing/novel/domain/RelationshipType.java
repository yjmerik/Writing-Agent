package com.writing.novel.domain;

import java.util.Set;

/**
 * 实体间关系类型。
 * 构成小说的关系图谱，用于实体召回和一致性维护。
 *
 * 对称关系（ALLY/ENEMY/LOVE/RIVAL）在去重时双向检查。
 */
public enum RelationshipType {
    // --- 物品/位置关系（有方向） ---
    OWNS,        // A 拥有 B
    LOCATED_IN,  // A 位于 B
    MEMBER_OF,   // A 是 B 的成员
    CREATES,     // A 创造 B
    USES,        // A 使用 B
    CONTAINS,    // A 包含 B
    PRODUCES,    // A 生产 B

    // --- 人物关系（对称） ---
    ALLY,        // A 和 B 是盟友
    ENEMY,       // A 和 B 是敌对
    LOVE,        // A 和 B 有恋爱/亲密关系
    RIVAL,       // A 和 B 是竞争对手（非敌对）
    KNOWS,       // A 认识 B（非亲密关系）

    // --- 人物关系（有方向，适合权谋/悬疑） ---
    MENTOR,      // A 是 B 的导师
    BETRAYS,     // A 背叛 B
    SUSPECTS,    // A 怀疑 B
    PROTECTS,    // A 保护 B
    SERVES,      // A 效力于 B

    // --- 通用 ---
    RELATED_TO;  // A 与 B 有关联（通用）

    /** 对称关系集合——去重时双向检查 */
    private static final Set<RelationshipType> SYMMETRIC = Set.of(
            ALLY, ENEMY, LOVE, RIVAL, KNOWS
    );

    /** 此关系是否对称（A→B 等价于 B→A） */
    public boolean isSymmetric() {
        return SYMMETRIC.contains(this);
    }
}
