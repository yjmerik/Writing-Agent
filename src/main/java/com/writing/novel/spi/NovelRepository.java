package com.writing.novel.spi;

import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.Character;
import com.writing.novel.domain.ChapterReview;
import com.writing.novel.domain.Checkpoint;
import com.writing.novel.domain.ContinuityIssue;
import com.writing.novel.domain.Entity;
import com.writing.novel.domain.EntityEdge;
import com.writing.novel.domain.EvaluationRecord;
import com.writing.novel.domain.Foreshadowing;
import com.writing.novel.domain.KnowledgeEntry;
import com.writing.novel.domain.Outline;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.StyleProfile;
import com.writing.novel.domain.Summary;
import com.writing.novel.domain.GenerationLog;
import com.writing.novel.domain.NarrativeThread;
import com.writing.novel.domain.WritingPreferences;
import com.writing.novel.domain.WritingTask;

import java.util.List;
import java.util.Optional;

/**
 * 小说数据的持久化 SPI。业务层只依赖此接口。
 * MVP 实现：SqliteNovelRepository；云端实现：Mysql/Postgres 版本。
 */
public interface NovelRepository {

    // --- Project ---
    String saveProject(Project project);
    Optional<Project> findProject(String id);
    List<Project> findAllProjects();
    void deleteProject(String id);

    // --- Worldbuilding / KnowledgeEntry ---
    void saveKnowledgeEntry(KnowledgeEntry entry);
    List<KnowledgeEntry> findKnowledgeEntriesByProject(String projectId);
    Optional<KnowledgeEntry> findKnowledgeEntry(String id);
    List<KnowledgeEntry> findKnowledgeEntriesByType(String projectId, String type);

    // --- Character ---
    String saveCharacter(Character character);
    Optional<Character> findCharacter(String id);
    List<Character> findCharactersByProject(String projectId);

    // --- Outline / Volume / Synopsis ---
    void saveOutline(Outline outline);
    Optional<Outline> findOutlineByProject(String projectId);

    // --- Chapter ---
    String saveChapter(Chapter chapter);
    Optional<Chapter> findChapter(String id);
    Optional<Chapter> findChapterByIndex(String projectId, int chapterIndex);
    List<Chapter> findChaptersByProject(String projectId);

    // p12-K3: 轻量查询——不全量加载正文
    /** 只返回 index/title/wordCount（content 为 null），用于章节列表展示 */
    List<Chapter> findChapterMetaByProject(String projectId);
    /** SUM 聚合求总字数——不全量加载正文 */
    int getTotalWordCount(String projectId);
    List<Chapter> findRecentChapters(String projectId, int count);
    /** B2: 按章号范围加载（不全表） */
    List<Chapter> findChaptersByIndexRange(String projectId, int fromIndex, int toIndex);
    /** B2: 章节计数（不加载正文） */
    int countChaptersByProject(String projectId);

    // --- Summary ---
    void saveSummary(Summary summary);
    List<Summary> findSummariesByProject(String projectId);
    /** so-fix: 删除指定摘要（用于清理被卷级摘要覆盖的细粒度摘要） */
    void deleteSummary(String summaryId);

    // --- Foreshadowing ---
    void saveForeshadowing(Foreshadowing f);
    List<Foreshadowing> findForeshadowingsByProject(String projectId);
    List<Foreshadowing> findUnresolvedForeshadowings(String projectId);

    // --- StyleProfile ---
    void saveStyleProfile(StyleProfile profile);
    Optional<StyleProfile> findStyleProfileByProject(String projectId);

    // --- ContinuityIssue ---
    void saveContinuityIssue(ContinuityIssue issue);
    List<ContinuityIssue> findContinuityIssuesByProject(String projectId);
    List<ContinuityIssue> findUnresolvedIssues(String projectId);

    // --- Checkpoint ---
    String saveCheckpoint(Checkpoint checkpoint);
    Optional<Checkpoint> findCheckpoint(String id);
    List<Checkpoint> findPendingCheckpoints(String projectId);
    Optional<Checkpoint> findLatestPendingCheckpoint(String projectId);

    // --- Entity (Graph) ---
    void saveEntity(Entity entity);
    Optional<Entity> findEntity(String id);
    Optional<Entity> findEntityByName(String projectId, String name);
    List<Entity> findEntitiesByProject(String projectId);
    List<Entity> findEntitiesInChapterRange(String projectId, int fromChapter, int toChapter);

    // --- EntityEdge (Graph) ---
    void saveEntityEdge(EntityEdge edge);
    List<EntityEdge> findEdgesBySource(String entityId);
    List<EntityEdge> findEdgesByTarget(String entityId);
    List<EntityEdge> findEdgesByProject(String projectId);

    // --- p10-w3-03: ArchiveCompactor 归档衰减（修根因 B：图表只增不减）---
    /** 物理删除过期实体边（validTo>0 且 < beforeChapter），返回删除数。默认 0（实现可选） */
    default int deleteExpiredEdges(String projectId, int beforeChapter) { return 0; }
    /** 归档旧的非 HIGH 连续性问题（resolved=1），返回归档数。默认 0 */
    default int archiveOldLowSeverityIssues(String projectId, int beforeChapter) { return 0; }

    // --- EvaluationRecord ---
    void saveEvaluationRecord(EvaluationRecord record);
    Optional<EvaluationRecord> findLatestEvaluation(String projectId);

    // --- p11-I1: ChapterReview（章节评价持久化） ---
    void saveChapterReview(ChapterReview review);
    Optional<ChapterReview> findChapterReview(String projectId, int chapterIndex);
    List<ChapterReview> findChapterReviewsByProject(String projectId);

    // --- p12-K2: WritingTask（异步写作任务） ---
    void saveWritingTask(WritingTask task);
    Optional<WritingTask> findWritingTask(String taskId);
    Optional<WritingTask> findActiveWritingTask(String projectId);
    List<WritingTask> findWritingTasksByProject(String projectId);

    // --- WritingPreferences ---
    void saveWritingPreferences(WritingPreferences prefs);
    Optional<WritingPreferences> findWritingPreferences(String projectId);

    // --- GenerationLog (创意多样性) ---
    void saveGenerationLog(GenerationLog log);
    /** 按项目查询生成日志 */
    List<GenerationLog> findGenerationLogsByProject(String projectId);
    List<GenerationLog> findRecentGenerationLogs(String projectId, String agentType, int limit);
    List<GenerationLog> findAllGenerationLogs(String projectId);

    // --- NarrativeThread (叙事线索) ---
    void saveNarrativeThread(NarrativeThread thread);
    List<NarrativeThread> findAllNarrativeThreads(String projectId);
}
