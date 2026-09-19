package com.writing.novel.service;

import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.ImpactItem;
import com.writing.novel.domain.StoryFact;
import com.writing.novel.persistence.sqlite.SqliteStoryCanon;
import com.writing.novel.persistence.sqlite.StoryFactRepository;
import com.writing.novel.spi.NovelRepository;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * NCS-014（方案 §12/R04/F01）：文本变更影响分析——确定性实现。
 *
 * <p>章文本修订后扫描后文章节：
 * ① 收集锚定于修订章旧稿的事实（fact_evidence：chapter_index=修订章 且 chapter_version<新版本，
 *   以及 chapter_established=修订章的收割事实）及其 subject；
 * ② 逐个后文章节做词法命中：正文包含受影响 subject → MUST_REPAIR（事实依赖）；
 *   仅含该章其他实体名 → RECHECK；无命中 → UNAFFECTED_WITH_REASON（理由保留）。
 * 覆盖说明声明扫描范围与局限——词法扫描不能证明语义无影响，未知部分显式保留，
 * 不把"未找到边"当"不受影响"的证据。
 */
public class ImpactAnalysisService {

    public record ImpactPlan(int revisedChapterIndex, int revisedToVersion,
                             List<ImpactItem> items, String coverageNote) {}

    private final NovelRepository repository;
    private final SqliteStoryCanon canon;
    private final StoryFactRepository factRepository;

    public ImpactAnalysisService(NovelRepository repository, SqliteStoryCanon canon) {
        this.repository = repository;
        this.canon = canon;
        this.factRepository = null;
    }

    public ImpactAnalysisService(NovelRepository repository, SqliteStoryCanon canon,
                                 StoryFactRepository factRepository) {
        this.repository = repository;
        this.canon = canon;
        this.factRepository = factRepository;
    }

    public ImpactPlan analyze(String projectId, int revisedChapterIndex, int newVersion) {
        // ① 受影响事实：锚定该章旧稿的证据所属事实 + 该章收割的事实
        Set<String> affectedFactIds = new HashSet<>();
        Map<String, StoryFact> factsById = new LinkedHashMap<>();
        for (StoryFact fact : canon.findAll(projectId)) {
            factsById.put(fact.getId(), fact);
            boolean oldSource = factRepository != null && factRepository.findByFactId(projectId, fact.getId()).stream()
                    .anyMatch(e -> ((Number) e.get("chapterIndex")).intValue() == revisedChapterIndex
                            && ((Number) e.get("chapterVersion")).intValue() < newVersion);
            if (fact.getChapterEstablished() == revisedChapterIndex || oldSource) {
                affectedFactIds.add(fact.getId());
            }
        }
        Set<String> affectedSubjects = new HashSet<>();
        for (String factId : affectedFactIds) {
            StoryFact fact = factsById.get(factId);
            if (fact != null && fact.getSubject() != null) {
                affectedSubjects.add(fact.getSubject());
            }
        }
        // ② 修订章自身的实体（供 RECHECK 判定：修订章涉及的其他实体名）
        var revisedChapter = repository.findChapterByIndex(projectId, revisedChapterIndex);
        Set<String> revisedEntities = new HashSet<>();
        revisedChapter.ifPresent(ch -> {
            for (var entity : repository.findEntitiesByProject(projectId)) {
                if (entity.getName() != null && ch.getContent() != null
                        && ch.getContent().contains(entity.getName())) {
                    revisedEntities.add(entity.getName());
                }
            }
        });
        // 全部人物名也作为词法候选（实体图未覆盖时）
        for (var character : repository.findCharactersByProject(projectId)) {
            if (character.getName() != null) revisedEntities.add(character.getName());
        }
        // 修订章收割事实的 subject 一律视为受影响实体
        revisedEntities.addAll(affectedSubjects);

        // ③ 扫描后文章节
        var entities = repository.findEntitiesByProject(projectId);
        var edges = repository.findEdgesByProject(projectId);
        List<Chapter> chapters = repository.findChaptersByProject(projectId);
        List<ImpactItem> items = new ArrayList<>();
        int scanned = 0;
        for (Chapter ch : chapters) {
            if (ch.getChapterIndex() <= revisedChapterIndex) continue;
            scanned++;
            String content = ch.getContent() == null ? "" : ch.getContent();
            Set<String> hitFacts = new HashSet<>();
            Set<String> hitSubjects = new HashSet<>();
            for (String subject : affectedSubjects) {
                if (subject != null && !subject.isBlank() && content.contains(subject)) {
                    hitSubjects.add(subject);
                    for (String factId : affectedFactIds) {
                        StoryFact f = factsById.get(factId);
                        if (f != null && subject.equals(f.getSubject())) hitFacts.add(factId);
                    }
                }
            }
            if (!hitSubjects.isEmpty()) {
                List<String> reasons = new ArrayList<>();
                for (String subject : hitSubjects) {
                    reasons.add("第 " + ch.getChapterIndex() + " 章正文引用了锚定于修订章的事实主体「"
                            + subject + "」——修订可能使其依赖失效");
                }
                items.add(new ImpactItem(ch.getChapterIndex(), ImpactItem.Classification.MUST_REPAIR,
                        reasons, new ArrayList<>(hitFacts), new ArrayList<>(hitSubjects)));
                continue;
            }
            // 显式关系闭包补足未点名原事实的间接依赖；只列待复核，不假定一定需要改写。
            Set<String> connected = new HashSet<>();
            for (var entity : entities) if (affectedSubjects.contains(entity.getName())) connected.add(entity.getId());
            boolean expanded;
            do {
                expanded = false;
                for (var edge : edges) {
                    if (!edge.isValidAt(ch.getChapterIndex())) continue;
                    if (connected.contains(edge.getSourceId())) expanded |= connected.add(edge.getTargetId());
                    if (connected.contains(edge.getTargetId())) expanded |= connected.add(edge.getSourceId());
                }
            } while (expanded);
            List<String> related = entities.stream()
                    .filter(e -> connected.contains(e.getId()) && e.getName() != null && !e.getName().isBlank()
                            && content.contains(e.getName()) && !affectedSubjects.contains(e.getName()))
                    .map(com.writing.novel.domain.Entity::getName).toList();
            if (!related.isEmpty()) {
                items.add(new ImpactItem(ch.getChapterIndex(), ImpactItem.Classification.RECHECK,
                        List.of("本章涉及事实主体的有效关系链：" + String.join("、", related) + "，需复核间接依赖"),
                        new ArrayList<>(affectedFactIds), related));
                continue;
            }
            // 无锚定事实命中：检查实体名出现 → RECHECK
            List<String> mentioned = new ArrayList<>();
            for (String entity : revisedEntities) {
                if (entity != null && !entity.isBlank() && content.contains(entity)) {
                    mentioned.add(entity);
                }
            }
            if (!mentioned.isEmpty()) {
                items.add(new ImpactItem(ch.getChapterIndex(), ImpactItem.Classification.RECHECK,
                        List.of("第 " + ch.getChapterIndex() + " 章提及修订章涉及的实体（"
                                + String.join("、", mentioned) + "），建议人工复核"),
                        List.of(), mentioned));
            } else {
                items.add(new ImpactItem(ch.getChapterIndex(), ImpactItem.Classification.UNAFFECTED_WITH_REASON,
                        List.of("词法扫描未发现该章使用修订章确立的实体/事实——未找到边不证明不受影响"),
                        List.of(), List.of()));
            }
        }
        String coverageNote = "已扫描第 " + (revisedChapterIndex + 1) + " 章之后共 " + scanned
                + " 章（词法级扫描：旧稿证据、锚定事实主体、实体名及有效关系闭包）；词法扫描无法证明语义无影响——"
                + "隐含依赖（未点名实体的一致性）属未知部分，需按需扩查";
        return new ImpactPlan(revisedChapterIndex, newVersion, items, coverageNote);
    }
}
