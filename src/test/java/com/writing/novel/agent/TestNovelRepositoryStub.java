package com.writing.novel.agent;

import com.writing.novel.spi.NovelRepository;
import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.Character;
import com.writing.novel.domain.Checkpoint;
import com.writing.novel.domain.ContinuityIssue;
import com.writing.novel.domain.Entity;
import com.writing.novel.domain.EntityEdge;
import com.writing.novel.domain.EvaluationRecord;
import com.writing.novel.domain.Foreshadowing;
import com.writing.novel.domain.GenerationLog;
import com.writing.novel.domain.KnowledgeEntry;
import com.writing.novel.domain.NarrativeThread;
import com.writing.novel.domain.Outline;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.StyleProfile;
import com.writing.novel.domain.Summary;
import com.writing.novel.domain.WritingPreferences;

import java.util.List;
import java.util.Optional;


/**
 * 测试用 NovelRepository 空实现（所有方法返回空/默认值）。
 * 测试可继承并覆盖需要的方法。
 */
public class TestNovelRepositoryStub implements NovelRepository {

    // --- p22-06: 接口方法无 silent default，测试桩必须显式声明能力（缺能力=编译失败） ---
    @Override public int deleteExpiredEdges(String projectId, int beforeChapter) { return 0; }
    @Override public int archiveOldLowSeverityIssues(String projectId, int beforeChapter) { return 0; }
    @Override public int archiveAgedHighIssues(String projectId, int beforeChapter) { return 0; }
    @Override public int deleteResolvedNarrativeThreads(String projectId) { return 0; }
    @Override public int deleteDuplicateKnowledgeEntries(String projectId) { return 0; }
    @Override public void saveCustomPreset(String name, String contentJson) {}
    @Override public java.util.Map<String, String> findCustomPresets() { return java.util.Map.of(); }
    @Override public int retireStaleForeshadowings(String projectId, int beforeChapter) { return 0; }
    @Override public int deleteKnowledgeEntriesByProject(String projectId) { return 0; }

    @Override public String saveProject(Project p) { return p.getId(); }
    @Override public Optional<Project> findProject(String id) { return Optional.empty(); }
    @Override public List<Project> findAllProjects() { return List.of(); }
    @Override public void deleteProject(String id) {}
    @Override public void saveKnowledgeEntry(KnowledgeEntry e) {}
    @Override public List<KnowledgeEntry> findKnowledgeEntriesByProject(String p) { return List.of(); }
    @Override public Optional<KnowledgeEntry> findKnowledgeEntry(String id) { return Optional.empty(); }
    @Override public List<KnowledgeEntry> findKnowledgeEntriesByType(String p, String t) { return List.of(); }
    @Override public String saveCharacter(Character c) { return c.getId(); }
    @Override public Optional<Character> findCharacter(String id) { return Optional.empty(); }
    @Override public List<Character> findCharactersByProject(String p) { return List.of(); }
    @Override public void saveOutline(Outline o) {}
    @Override public Optional<Outline> findOutlineByProject(String p) { return Optional.empty(); }
    @Override public String saveChapter(Chapter c) { return c.getId(); }
    @Override public Optional<Chapter> findChapter(String id) { return Optional.empty(); }
    @Override public Optional<Chapter> findChapterByIndex(String p, int i) { return Optional.empty(); }
    @Override public List<Chapter> findChaptersByProject(String p) { return List.of(); }
    @Override public List<Chapter> findChapterMetaByProject(String p) { return List.of(); }
    @Override public int getTotalWordCount(String p) { return 0; }
    @Override public List<Chapter> findRecentChapters(String p, int c) { return List.of(); }
    @Override public List<Chapter> findChaptersByIndexRange(String p, int from, int to) { return List.of(); }
    @Override public int countChaptersByProject(String p) { return 0; }
    @Override public void saveSummary(Summary s) {}
    @Override public List<Summary> findSummariesByProject(String p) { return List.of(); }
    @Override public void deleteSummary(String summaryId) {}
    @Override public void saveForeshadowing(Foreshadowing f) {}
    @Override public List<Foreshadowing> findForeshadowingsByProject(String p) { return List.of(); }
    @Override public List<Foreshadowing> findUnresolvedForeshadowings(String p) { return List.of(); }
    @Override public void saveStyleProfile(StyleProfile p) {}
    @Override public Optional<StyleProfile> findStyleProfileByProject(String p) { return Optional.empty(); }
    @Override public void saveContinuityIssue(ContinuityIssue i) {}
    @Override public List<ContinuityIssue> findContinuityIssuesByProject(String p) { return List.of(); }
    @Override public List<ContinuityIssue> findUnresolvedIssues(String p) { return List.of(); }
    @Override public String saveCheckpoint(Checkpoint c) { return c.getId(); }
    @Override public Optional<Checkpoint> findCheckpoint(String id) { return Optional.empty(); }
    @Override public List<Checkpoint> findPendingCheckpoints(String p) { return List.of(); }
    @Override public Optional<Checkpoint> findLatestPendingCheckpoint(String p) { return Optional.empty(); }

    // --- Entity ---
    @Override public void saveEntity(Entity e) {}
    @Override public Optional<Entity> findEntity(String id) { return Optional.empty(); }
    @Override public Optional<Entity> findEntityByName(String p, String n) { return Optional.empty(); }
    @Override public List<Entity> findEntitiesByProject(String p) { return List.of(); }
    @Override public List<Entity> findEntitiesInChapterRange(String p, int f, int t) { return List.of(); }

    // --- EntityEdge ---
    @Override public void saveEntityEdge(EntityEdge e) {}
    @Override public List<EntityEdge> findEdgesBySource(String id) { return List.of(); }
    @Override public List<EntityEdge> findEdgesByTarget(String id) { return List.of(); }
    @Override public List<EntityEdge> findEdgesByProject(String p) { return List.of(); }

    // --- EvaluationRecord ---
    @Override public void saveEvaluationRecord(EvaluationRecord r) {}
    @Override public Optional<EvaluationRecord> findLatestEvaluation(String p) { return Optional.empty(); }

    // --- p11-I1: ChapterReview ---
    @Override public void saveChapterReview(com.writing.novel.domain.ChapterReview r) {}
    @Override public Optional<com.writing.novel.domain.ChapterReview> findChapterReview(String p, int c) { return Optional.empty(); }
    @Override public List<com.writing.novel.domain.ChapterReview> findChapterReviewsByProject(String p) { return List.of(); }

    // --- p12-K2: WritingTask ---
    @Override public void saveWritingTask(com.writing.novel.domain.WritingTask t) {}
    @Override public Optional<com.writing.novel.domain.WritingTask> findWritingTask(String t) { return Optional.empty(); }
    @Override public Optional<com.writing.novel.domain.WritingTask> findActiveWritingTask(String p) { return Optional.empty(); }
    @Override public List<com.writing.novel.domain.WritingTask> findWritingTasksByProject(String p) { return List.of(); }

    // --- WritingPreferences ---
    @Override public void saveWritingPreferences(WritingPreferences p) {}
    @Override public Optional<WritingPreferences> findWritingPreferences(String p) { return Optional.empty(); }

    // --- GenerationLog ---
    @Override public void saveGenerationLog(GenerationLog log) {}
    @Override public List<GenerationLog> findRecentGenerationLogs(String p, String a, int l) { return List.of(); }
    @Override public List<GenerationLog> findAllGenerationLogs(String p) { return List.of(); }
    @Override public List<GenerationLog> findGenerationLogsByProject(String p) { return List.of(); }

    // --- NarrativeThread ---
    @Override public void saveNarrativeThread(NarrativeThread t) {}
    @Override public List<NarrativeThread> findAllNarrativeThreads(String p) { return List.of(); }
}
