package com.writing.novel.service;

import com.writing.novel.domain.Character;
import com.writing.novel.domain.EntityEdge;
import com.writing.novel.domain.StoryFact;
import com.writing.novel.persistence.FactEvidenceRepository;
import com.writing.novel.persistence.sqlite.SqliteStoryCanon;
import com.writing.novel.spi.NovelRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NCS-010（方案 §07/R01/R15）：人物时点读取合同。
 *
 * <p>标准读取请求 ReadContext(subject, narrativePosition)：只返回该时点已确立的状态事实
 * （validFrom ≤ N ≤ validTo）、该角色 N 前获知的引文（含来源章）、N 时点有效的关系边。
 * "已知"与"所信"分开：证据仍有效=已知（与当前事实一致）；证据已失效=所信
 * （角色仍这样认为，但客观世界可能已变——信念可以是错的，不与事实自动合并）。
 */
public class CharacterAsOfService {

    private final NovelRepository repository;
    private final SqliteStoryCanon canon;
    private final FactEvidenceRepository evidenceRepository;

    public CharacterAsOfService(NovelRepository repository,
                                SqliteStoryCanon canon,
                                FactEvidenceRepository evidenceRepository) {
        this.repository = repository;
        this.canon = canon;
        this.evidenceRepository = evidenceRepository;
    }

    public Map<String, Object> asOf(String projectId, String characterName, int atChapter) {
        Character character = repository.findCharactersByProject(projectId).stream()
                .filter(c -> characterName.equals(c.getName()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("人物不存在: " + characterName));

        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> characterInfo = new LinkedHashMap<>();
        characterInfo.put("id", character.getId());
        characterInfo.put("name", character.getName());
        characterInfo.put("role", character.getRole());
        characterInfo.put("status", character.getStatus().name());
        result.put("character", characterInfo);
        result.put("asOfChapter", atChapter);

        // 1. 时点事实：该人物为 subject 的 ACTIVE 事实，validFrom<=N 且（validTo 空或 >=N）
        List<Map<String, Object>> facts = new ArrayList<>();
        for (StoryFact fact : canon.recallBySubject(projectId, characterName)) {
            if (fact.getValidFromChapter() > atChapter) continue;
            if (fact.getValidToChapter() != null && fact.getValidToChapter() < atChapter) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("factId", fact.getId());
            m.put("type", fact.getFactType().name());
            m.put("assertion", fact.getAssertion());
            m.put("predicate", fact.getPredicate());
            m.put("objectValue", fact.getObjectValue());
            m.put("polarity", fact.getPolarity());
            m.put("validFromChapter", fact.getValidFromChapter());
            m.put("validToChapter", fact.getValidToChapter());
            facts.add(m);
        }
        result.put("facts", facts);

        // 2/3. 知情与信念：N 前获知的引文按其事实当前状态分流
        Map<String, StoryFact.Status> factStatus = new HashMap<>();
        for (StoryFact fact : canon.findAll(projectId)) {
            factStatus.put(fact.getId(), fact.getStatus());
        }
        List<Map<String, Object>> knows = new ArrayList<>();
        List<Map<String, Object>> believes = new ArrayList<>();
        for (Map<String, Object> learned : evidenceRepository.findLearnedByCharacter(
                projectId, character.getId(), atChapter)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("factId", learned.get("factId"));
            m.put("quote", learned.get("quote"));
            m.put("learnedAtChapter", learned.get("learnedAtChapter"));
            m.put("sourceChapter", learned.get("chapterIndex"));
            m.put("sourceVersion", learned.get("chapterVersion"));
            m.put("evidenceStale", learned.get("evidenceStale"));
            StoryFact.Status status = factStatus.get((String) learned.get("factId"));
            boolean factActive = status == StoryFact.Status.ACTIVE && !Boolean.TRUE.equals(learned.get("evidenceStale"));
            if (factActive) {
                knows.add(m);
            } else {
                // 所信：角色仍持有该认识，但对应事实已失去支持/被取代——可能是错误信念
                m.put("beliefStatus", status == null ? "UNKNOWN" : status.name());
                believes.add(m);
            }
        }
        result.put("knows", knows);
        result.put("believes", believes);

        // 读者依据正文已呈现的来源获知；不以角色获知时间代替叙述位置。
        List<Map<String, Object>> readerKnows = new ArrayList<>();
        for (StoryFact fact : canon.findAll(projectId)) {
            for (var source : evidenceRepository.findByFact(projectId, fact.getId())) {
                if (((Number) source.get("chapterIndex")).intValue() > atChapter) continue;
                if (source.get("quote") == null || source.get("quote").toString().isBlank()) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("factId", fact.getId()); row.put("quote", source.get("quote"));
                row.put("sourceChapter", source.get("chapterIndex"));
                row.put("sourceVersion", source.get("chapterVersion"));
                row.put("evidenceStale", source.get("evidenceStale"));
                readerKnows.add(row);
            }
        }
        result.put("readerKnows", readerKnows);

        // 4. 关系边：N 时点有效（validFrom<=N<=validTo）；target 以实体名呈现
        Map<String, String> entityNames = new HashMap<>();
        for (var entity : repository.findEntitiesByProject(projectId)) {
            entityNames.put(entity.getId(), entity.getName());
        }
        List<Map<String, Object>> relationships = new ArrayList<>();
        String entityId = entityNames.entrySet().stream()
                .filter(e -> characterName.equals(e.getValue()))
                .map(Map.Entry::getKey).findFirst().orElse(null);
        if (entityId != null) {
            for (EntityEdge edge : repository.findEdgesBySource(entityId)) {
                if (!edge.isValidAt(atChapter)) continue;
                relationships.add(edgeView(edge, entityNames, false));
            }
            for (EntityEdge edge : repository.findEdgesByTarget(entityId)) {
                if (!edge.isValidAt(atChapter)) continue;
                relationships.add(edgeView(edge, entityNames, true));
            }
        }
        result.put("relationships", relationships);
        return result;
    }

    private Map<String, Object> edgeView(EntityEdge edge, Map<String, String> names, boolean incoming) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("relationship", edge.getRelationship().name());
        m.put("source", names.getOrDefault(incoming ? edge.getTargetId() : edge.getSourceId(), "未知实体"));
        m.put("target", names.getOrDefault(incoming ? edge.getSourceId() : edge.getTargetId(), "未知实体"));
        m.put("validFromChapter", edge.getValidFromChapter());
        m.put("validToChapter", edge.getValidToChapter());
        m.put("direction", incoming ? "incoming" : "outgoing");
        return m;
    }
}
