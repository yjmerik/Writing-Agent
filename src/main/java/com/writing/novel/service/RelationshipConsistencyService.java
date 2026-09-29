package com.writing.novel.service;

import com.writing.novel.domain.ContinuityIssue;
import com.writing.novel.domain.EntityEdge;
import com.writing.novel.domain.EntityType;
import com.writing.novel.domain.StoryFact;
import com.writing.novel.spi.NovelRepository;
import com.writing.novel.spi.StoryCanon;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * NCS-122: 关系双轨一致性——EntityEdge（时间作用域图边）与 StoryFact.RELATIONSHIP_STATE
 * （真典事实）两套写入此前无交叉校验（W13），N 时点关系查询与真典关系可能矛盾。
 *
 * <p>本服务提供：
 * <ul>
 *   <li>{@link #crossCheck}：当前有效边 vs 同对角色 RELATIONSHIP_STATE 事实的零重叠检测
 *       （advisory MEDIUM——不自动重写，供检查链与作者裁决）</li>
 *   <li>{@link #unifiedRelationships}：N 时点统一口径读取（边表优先，真典补充无边的对），
 *       {@code CharacterAsOfService} 消费</li>
 * </ul>
 */
@Component
public class RelationshipConsistencyService {

    private final NovelRepository repository;
    private final StoryCanon canon;

    public RelationshipConsistencyService(NovelRepository repository, StoryCanon canon) {
        this.repository = repository;
        this.canon = canon;
    }

    /**
     * 双轨交叉校验：同对 CHARACTER 存在当前边与 RELATIONSHIP_STATE 事实、
     * 但两者描述零关键词重叠 → MEDIUM advisory issue（矛盾信号，交检查链/作者）。
     */
    public List<ContinuityIssue> crossCheck(String projectId, int chapterIndex) {
        List<ContinuityIssue> issues = new ArrayList<>();
        var idToName = new HashMap<String, String>();
        for (var e : repository.findEntitiesByProject(projectId)) {
            if (e.getType() == EntityType.CHARACTER) idToName.put(e.getId(), e.getName());
        }
        var currentEdges = repository.findEdgesByProject(projectId).stream()
                .filter(EntityEdge::isCurrent)
                .filter(e -> idToName.containsKey(e.getSourceId()) && idToName.containsKey(e.getTargetId()))
                .toList();
        if (currentEdges.isEmpty()) return issues;

        // 真典关系事实按 subject 归组
        Map<String, List<StoryFact>> factsBySubject = new HashMap<>();
        for (var f : canon.recall(projectId)) {
            if (f.getFactType() == StoryFact.FactType.RELATIONSHIP_STATE) {
                factsBySubject.computeIfAbsent(f.getSubject(), k -> new ArrayList<>()).add(f);
            }
        }
        for (var edge : currentEdges) {
            String a = idToName.get(edge.getSourceId());
            String b = idToName.get(edge.getTargetId());
            List<StoryFact> pairFacts = new ArrayList<>();
            for (var f : factsBySubject.getOrDefault(a, List.of())) {
                if (f.getAssertion() != null && f.getAssertion().contains(b)) pairFacts.add(f);
            }
            for (var f : factsBySubject.getOrDefault(b, List.of())) {
                if (f.getAssertion() != null && f.getAssertion().contains(a)) pairFacts.add(f);
            }
            if (pairFacts.isEmpty()) continue;
            boolean anyOverlap = pairFacts.stream().anyMatch(f ->
                    keywordOverlap(String.valueOf(edge.getRelationship()), f.getAssertion()));
            if (!anyOverlap) {
                issues.add(new ContinuityIssue(projectId, chapterIndex,
                        ContinuityIssue.Type.CHARACTER, ContinuityIssue.Severity.MEDIUM,
                        "关系双轨不一致：图边[" + a + "→" + b + ": " + edge.getRelationship()
                                + "] 与真典[" + pairFacts.get(0).getAssertion() + "] 描述矛盾，需裁决"));
            }
        }
        return issues;
    }

    /**
     * N 时点统一关系口径：当前有效边优先；无边覆盖的角色对由真典
     * RELATIONSHIP_STATE（生效区间内）补充——双源不再各说各话。
     */
    public List<String> unifiedRelationships(String projectId, String characterName, int atChapter) {
        List<String> out = new ArrayList<>();
        var idToName = new HashMap<String, String>();
        var nameToId = new HashMap<String, String>();
        for (var e : repository.findEntitiesByProject(projectId)) {
            if (e.getType() == EntityType.CHARACTER) {
                idToName.put(e.getId(), e.getName());
                nameToId.put(e.getName(), e.getId());
            }
        }
        String selfId = nameToId.get(characterName);
        java.util.Set<String> coveredCounterparts = new java.util.HashSet<>();
        for (var edge : repository.findEdgesByProject(projectId)) {
            if (!edge.isCurrent()) continue;
            String other = null;
            if (selfId != null && edge.getSourceId().equals(selfId)) other = idToName.get(edge.getTargetId());
            else if (selfId != null && edge.getTargetId().equals(selfId)) other = idToName.get(edge.getSourceId());
            if (other == null) continue;
            coveredCounterparts.add(other);
            out.add(other + ": " + edge.getRelationship());
        }
        // 真典补充：与 characterName 相关、生效区间内、且无边覆盖对端的关系事实
        for (var f : canon.recall(projectId)) {
            if (f.getFactType() != StoryFact.FactType.RELATIONSHIP_STATE
                    || !characterName.equals(f.getSubject())) continue;
            if (f.getValidFromChapter() > atChapter) continue;
            if (f.getValidToChapter() != null && f.getValidToChapter() < atChapter) continue;
            String assertion = f.getAssertion() == null ? "" : f.getAssertion();
            for (var entry : nameToId.entrySet()) {
                if (entry.getKey().equals(characterName)) continue;
                if (assertion.contains(entry.getKey()) && !coveredCounterparts.contains(entry.getKey())) {
                    out.add(entry.getKey() + ": " + assertion + "（真典）");
                    coveredCounterparts.add(entry.getKey());
                }
            }
        }
        return out;
    }

    /** 枚举 → 中文关键词桥接（边表枚举名是英文，真典断言是中文——无桥接则永不重叠） */
    private static final Map<String, List<String>> RELATION_KEYWORDS = Map.ofEntries(
            Map.entry("ALLY", List.of("盟友", "同盟", "结盟", "并肩")),
            Map.entry("ENEMY", List.of("敌", "仇", "对立")),
            Map.entry("LOVE", List.of("恋", "爱", "亲密", "情愫")),
            Map.entry("RIVAL", List.of("竞争", "对手", "较劲")),
            Map.entry("KNOWS", List.of("认识", "相识", "旧识")),
            Map.entry("MENTOR", List.of("师", "教导", "授业")),
            Map.entry("BETRAYS", List.of("背叛", "出卖")),
            Map.entry("SUSPECTS", List.of("怀疑", "猜疑")),
            Map.entry("PROTECTS", List.of("保护", "护", "庇")),
            Map.entry("SERVES", List.of("效力", "效忠", "部下")),
            Map.entry("OWNS", List.of("拥有", "持有")),
            Map.entry("RELATED_TO", List.of("关联", "相关", "有关")));

    /** 关系与断言的关键词重叠（枚举经中文桥接；原生中文词 2 字滑窗） */
    static boolean keywordOverlap(String relation, String assertion) {
        if (relation == null || assertion == null) return false;
        for (String kw : RELATION_KEYWORDS.getOrDefault(relation, List.of())) {
            if (assertion.contains(kw)) return true;
        }
        String rel = relation.toLowerCase();
        for (int i = 0; i + 2 <= rel.length(); i++) {
            if (assertion.contains(rel.substring(i, i + 2))) return true;
        }
        return false;
    }
}
