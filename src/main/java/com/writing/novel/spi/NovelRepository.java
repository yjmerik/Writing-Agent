package com.writing.novel.spi;

import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.ChapterVersion;
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
import com.writing.novel.domain.StoryFact;
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
    /** Atomically replace all worldbuilding entries for one project. */
    void replaceKnowledgeEntries(String projectId, List<KnowledgeEntry> entries);
    List<KnowledgeEntry> findKnowledgeEntriesByProject(String projectId);
    Optional<KnowledgeEntry> findKnowledgeEntry(String id);
    List<KnowledgeEntry> findKnowledgeEntriesByType(String projectId, String type);

    // --- Character ---
    String saveCharacter(Character character);
    Optional<Character> findCharacter(String id);
    List<Character> findCharactersByProject(String projectId);

    // --- Outline / Volume / Synopsis ---
    void saveOutline(Outline outline);
    /**
     * 原子替换大纲，并可在同一事务中取代旧的同主体真典事实、写入新事实。
     * 任一写入失败时，大纲与真典审计链必须全部回滚。
     */
    Optional<StoryFact> replaceOutlineAtomically(Outline outline, StoryFact plotCommitment);
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

    // --- Immutable chapter version history ---
    void saveChapterVersion(ChapterVersion snapshot);
    List<ChapterVersion> findChapterVersions(String projectId, int chapterIndex);
    Optional<ChapterVersion> findChapterVersion(String projectId, int chapterIndex, int version);
    /** Atomically replace the expected current chapter and insert its pre-change snapshot. */
    boolean saveChapterRevision(ChapterVersion snapshot, Chapter revised, int expectedVersion);

    /**
     * p32-02: 状态标记的轻量版本门——仅当章节仍处 expectedVersion 时更新状态，
     * 绝不触碰正文（陈旧内存对象不得整行覆盖作者内容）。
     */
    boolean updateChapterStatusIfVersion(String projectId, int chapterIndex,
                                         Chapter.Status status, int expectedVersion);

    // --- p33-01: 人审屏障（阻止依赖它的后续章节生成） ---
    /** 落盘一条屏障（标记 NEEDS_MANUAL_REVIEW 时立即调用）。 */
    void saveReviewBarrier(com.writing.novel.domain.ReviewBarrier barrier);
    /** 项目全部 ACTIVE 屏障；对处于 NEEDS_MANUAL_REVIEW 但无屏障行的遗留章节惰性补建。 */
    java.util.List<com.writing.novel.domain.ReviewBarrier> findActiveReviewBarriers(String projectId);
    /** 解决指定章节的全部 ACTIVE 屏障（作者放行时调用），返回解决条数。 */
    int resolveReviewBarriers(String projectId, int chapterIndex);
    /** p34-04: 版本推进的正式修订把该章 ACTIVE 屏障重绑到新稿号（原因注明内容已修订）。 */
    int rebindReviewBarriers(String projectId, int chapterIndex, int newVersion);

    // --- p36-03: 版本化审阅上下文与原子批准 ---

    /**
     * 读取章节审阅上下文：当前稿件身份、待审集合指纹（reviewSetHash）、
     * 待审屏障/章节类检查点、当前报告身份及既有批准。只读，不补建屏障。
     * 缺省实现禁止默认成功——未实现版本化批准的仓储必须显式抛出。
     */
    default com.writing.novel.domain.ChapterApproval.ReviewContext readReviewContext(
            String projectId, int chapterIndex) {
        throw new UnsupportedOperationException("readReviewContext 未实现：仓储不支持版本化审阅");
    }

    /**
     * p36-03（C2）：原子比较提交作者批准。单连接事务内完成：
     * 幂等重放 → 双重批准守卫 → 身份比较 → 待审集合比较 →
     * 写批准审计 → 标记精确版本 DONE → 解决匹配稿号屏障（含历史）→
     * 处理同章章节类检查点 → 按剩余待审项退出停点。任一步失败全部回滚。
     */
    default com.writing.novel.domain.ChapterApproval.Result approveChapter(
            com.writing.novel.domain.ChapterApproval.Request request) {
        throw new UnsupportedOperationException("approveChapter 未实现：仓储不支持版本化批准");
    }

    /** p36-11: 该稿件身份是否有作者批准记录（缺省 false——不默认已批准）。 */
    default boolean isChapterApproved(String projectId, int chapterIndex, int version, String contentHash) {
        return false;
    }

    // --- p37-02: 持久修订候选存取与原子应用 ---

    /** 保存修订候选（持久 DB 资源，刷新/重启后可恢复）。缺省实现禁止默认成功。 */
    default void saveProposal(com.writing.novel.domain.RevisionProposal proposal) {
        throw new UnsupportedOperationException("saveProposal 未实现：仓储不支持持久候选");
    }

    /** 候选仅可在所属项目内读取。 */
    default java.util.Optional<com.writing.novel.domain.RevisionProposal> findProposal(
            String projectId, String proposalId) {
        throw new UnsupportedOperationException("findProposal 未实现：仓储不支持持久候选");
    }

    /** 项目全部候选（含各状态），按创建时间倒序。 */
    default java.util.List<com.writing.novel.domain.RevisionProposal> listProposals(String projectId) {
        throw new UnsupportedOperationException("listProposals 未实现：仓储不支持持久候选");
    }

    /** READY 候选用 CAS 取消（留记录）；非 READY 返回 false。 */
    default boolean cancelProposal(String projectId, String proposalId) {
        throw new UnsupportedOperationException("cancelProposal 未实现：仓储不支持持久候选");
    }

    /** p37-08: 项目内指定意见的全部证据（视图渲染用）。 */
    default java.util.List<java.util.Map<String, Object>> findDirectiveEvidence(
            String projectId, String directiveId) {
        throw new UnsupportedOperationException("findDirectiveEvidence 未实现");
    }

    /** p37-14: 来源冲突时独立短事务标 STALE（仅 READY→STALE）；绝不触碰正文。 */
    default boolean markProposalStale(String projectId, String proposalId) {
        throw new UnsupportedOperationException("markProposalStale 未实现：仓储不支持持久候选");
    }

    /**
     * 章节候选原子应用：单连接事务内完成候选消费 + saveChapterRevision 语义
     * （快照/正文/屏障重绑/批准失效）+ 候选 APPLIED 结果。重复应用返回已持久
     * result 不另增版本；来源冲突抛 ProposalSourceConflictException 并在独立短事务
     * 标 STALE，绝不改正文；故障回滚后候选保持 READY。
     */
    default ProposalCommitResult commitProposalChapter(String projectId, String proposalId,
                                                       ChapterVersion snapshot, Chapter revised,
                                                       com.writing.novel.domain.ChapterApproval.Identity expectedIdentity) {
        throw new UnsupportedOperationException("commitProposalChapter 未实现：仓储不支持持久候选");
    }

    /** 候选应用结果：replayed=true 表示同候选重复应用返回已持久结果。 */
    record ProposalCommitResult(boolean replayed, String proposalId,
                                String resultJson, int chapterVersion) {
    }

    /** 候选来源冲突：当前稿身份与候选/请求身份不一致。携带当前权威身份供刷新。 */
    final class ProposalSourceConflictException extends RuntimeException {

        private final String proposalId;
        private final com.writing.novel.domain.ChapterApproval.Identity currentIdentity;

        public ProposalSourceConflictException(String proposalId,
                                               com.writing.novel.domain.ChapterApproval.Identity currentIdentity) {
            super("候选来源已变化，请刷新后重新生成");
            this.proposalId = proposalId;
            this.currentIdentity = currentIdentity;
        }

        public String getProposalId() { return proposalId; }
        public com.writing.novel.domain.ChapterApproval.Identity getCurrentIdentity() { return currentIdentity; }
    }

    /** 版本化批准冲突：身份/待审集合过期、重复批准或幂等键语义冲突。携带当前权威状态供客户端刷新。 */
    final class ReviewConflictException extends RuntimeException {

        public enum Reason { IDENTITY_STALE, REVIEW_SET_STALE, ALREADY_APPROVED, IDEMPOTENCY_CONFLICT }

        private final Reason reason;
        private final transient com.writing.novel.domain.ChapterApproval.Identity currentIdentity;
        private final String currentReviewSetHash;

        public ReviewConflictException(Reason reason, String message,
                                       com.writing.novel.domain.ChapterApproval.Identity currentIdentity,
                                       String currentReviewSetHash) {
            super(message);
            this.reason = reason;
            this.currentIdentity = currentIdentity;
            this.currentReviewSetHash = currentReviewSetHash;
        }

        public Reason getReason() { return reason; }
        public com.writing.novel.domain.ChapterApproval.Identity getCurrentIdentity() { return currentIdentity; }
        public String getCurrentReviewSetHash() { return currentReviewSetHash; }
    }

    // --- p33-06: 作者意见闭环 ---
    void saveAuthorDirective(com.writing.novel.domain.AuthorDirective directive);

    // --- p38-06: 成书版本存取（缺省禁止默认成功） ---

    default void saveBookEdition(com.writing.novel.domain.BookEdition edition,
            List<com.writing.novel.domain.BookEdition.EditionChapter> chapters) {
        throw new UnsupportedOperationException("saveBookEdition 未实现");
    }

    default java.util.Optional<com.writing.novel.domain.BookEdition> findBookEdition(
            String projectId, String editionId) {
        throw new UnsupportedOperationException("findBookEdition 未实现");
    }

    /** p38-07: 项目全部成书版本（按创建时间倒序）。 */
    default List<com.writing.novel.domain.BookEdition> listBookEditions(String projectId) {
        throw new UnsupportedOperationException("listBookEditions 未实现");
    }

    default List<com.writing.novel.domain.BookEdition.EditionChapter> findBookEditionChapters(
            String editionId) {
        throw new UnsupportedOperationException("findBookEditionChapters 未实现");
    }

    default void approveBookEdition(String projectId, String editionId, String manifestHash) {
        throw new UnsupportedOperationException("approveBookEdition 未实现");
    }

    // --- p38-10: 问题裁决存取与批准门禁（缺省禁止默认成功） ---

    default void saveDisposition(com.writing.novel.domain.ReviewDisposition disposition) {
        throw new UnsupportedOperationException("saveDisposition 未实现");
    }

    default List<com.writing.novel.domain.ReviewDisposition> findDispositions(
            String projectId, int chapterIndex, int chapterVersion, String contentHash) {
        throw new UnsupportedOperationException("findDispositions 未实现");
    }

    /** p38-10: 该章当前身份下是否存在有效裁决（identity 匹配且非过期 DEFER）。 */
    default boolean hasValidDisposition(String projectId, String issueId,
                                        com.writing.novel.domain.ChapterApproval.Identity identity) {
        throw new UnsupportedOperationException("hasValidDisposition 未实现");
    }

    // --- p38-02: 计划变更持久存取（缺省禁止默认成功） ---

    default void savePlanChange(com.writing.novel.domain.PlanChange planChange) {
        throw new UnsupportedOperationException("savePlanChange 未实现");
    }

    default java.util.Optional<com.writing.novel.domain.PlanChange> findPlanChange(
            String projectId, String previewId) {
        throw new UnsupportedOperationException("findPlanChange 未实现");
    }

    default com.writing.novel.domain.PlanChange commitPlanChange(
            String projectId, String previewId, String sourcePlanHash,
            List<com.writing.novel.domain.PlanChange.PromiseDecision> decisions, String key) {
        throw new UnsupportedOperationException("commitPlanChange 未实现");
    }

    /**
     * Atomically confirm a plan preview and persist its preferences, outline and promise decisions.
     * Implementations must revalidate the source within the same transaction and roll back all
     * objects on failure. A failed request remains retryable; no multi-write default is safe.
     */
    default com.writing.novel.domain.PlanChange commitPlanEdition(
            String projectId, String previewId, String sourcePlanHash,
            WritingPreferences preferences, Outline outline,
            List<com.writing.novel.domain.PlanChange.PromiseDecision> decisions, String requestKey) {
        throw new UnsupportedOperationException("原子计划确认未实现");
    }

    /** 审计 P1-1: 补偿回退——把 CONFIRMED 回退为 PENDING（plan_change 写入失败的回退路径）。 */
    default void reopenPlanChange(String projectId, String previewId) {
        throw new UnsupportedOperationException("reopenPlanChange 未实现");
    }

    // --- p37-07: 意见与证据版本化仓储（缺省禁止默认成功） ---

    /** CAS 更新意见内容/范围/启用：expectedRevision 匹配才生效，成功后 revision+1。 */
    default void updateDirective(com.writing.novel.domain.AuthorDirective directive, int expectedRevision) {
        throw new UnsupportedOperationException("updateDirective 未实现");
    }

    /** 适用意见：enabled 且范围含该章且未撤销；HARD/PREFERENCE 持续，ONCE 有确认证据后不再适用。 */
    default java.util.List<com.writing.novel.domain.AuthorDirective> findApplicableDirectives(
            String projectId, int chapterIndex) {
        throw new UnsupportedOperationException("findApplicableDirectives 未实现");
    }

    /** 保存逐章证据：相同（意见×稿×指纹×引用）幂等，返回持久化证据。 */
    default com.writing.novel.domain.AuthorDirective.Evidence saveDirectiveEvidence(
            String projectId, com.writing.novel.domain.AuthorDirective.Evidence evidence) {
        throw new UnsupportedOperationException("saveDirectiveEvidence 未实现");
    }

    /** 作者确认证据：事务内重读章与意见版本；旧稿/UNMET/意见修订过期一律拒绝。 */
    default com.writing.novel.domain.AuthorDirective.Evidence confirmDirectiveEvidence(
            String projectId, String evidenceId,
            com.writing.novel.domain.ChapterApproval.Identity expectedIdentity, int directiveRevision) {
        throw new UnsupportedOperationException("confirmDirectiveEvidence 未实现");
    }

    /** 显式撤销（HARD 必须携带理由）：CAS expectedRevision，留 revision 审计。 */
    default void revokeDirective(String projectId, String directiveId, String reason, int expectedRevision) {
        throw new UnsupportedOperationException("revokeDirective 未实现");
    }
    /** 全部意见（含各状态），按创建时间倒序。 */
    java.util.List<com.writing.novel.domain.AuthorDirective> findAuthorDirectives(String projectId);
    java.util.Optional<com.writing.novel.domain.AuthorDirective> findAuthorDirective(String projectId, String id);

    // --- p33-07: 候选线索层 ---
    void saveForeshadowCandidate(com.writing.novel.domain.ForeshadowCandidate candidate);
    /** 全部候选（含各状态）。 */
    java.util.List<com.writing.novel.domain.ForeshadowCandidate> findForeshadowCandidates(String projectId);
    /** 提升：候选转正式伏笔（HIGH）并标记 PROMOTED。 */
    void promoteForeshadowCandidate(String projectId, String candidateId);
    void rejectForeshadowCandidate(String projectId, String candidateId);

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
    /** 物理删除过期实体边（validTo>0 且 < beforeChapter），返回删除数。p22-06: 无默认——实现必须显式提供 */
    int deleteExpiredEdges(String projectId, int beforeChapter);
    /** 归档旧的非 HIGH 连续性问题（resolved=1），返回归档数。p22-06: 无默认 */
    int archiveOldLowSeverityIssues(String projectId, int beforeChapter);
    /** p16-01: 老化归档更早的未复发 HIGH 问题（resolved=1）。p22-06: 无默认 */
    int archiveAgedHighIssues(String projectId, int beforeChapter);

    // --- p11-q0-06: 归档衰减补齐（foreshadowings/narrative_threads/knowledge_entries，W3-03 只做 2/5）---
    /** 物理删除已回收（RESOLVED）的叙事线索，避免 narrative_threads 表无界增长。p22-06: 无默认 */
    int deleteResolvedNarrativeThreads(String projectId);
    /** 删除同项目内重名的知识条目（按 name 去重保留其一）。p22-06: 无默认 */
    int deleteDuplicateKnowledgeEntries(String projectId);

    // --- p17-05: 自定义预设持久化（跨项目共享） ---
    /** 保存/更新自定义预设（name 为键，content 为 JSON 全文）。p22-06: 无默认 */
    void saveCustomPreset(String name, String contentJson);
    /** 读取全部自定义预设（name → JSON）。p22-06: 无默认 */
    java.util.Map<String, String> findCustomPresets();
    /** 长期悬置（planted<beforeChapter）且非 mustResolve 的 LOW 优先级伏笔标记放弃（resolved=1）。p22-06: 无默认 */
    int retireStaleForeshadowings(String projectId, int beforeChapter);
    /** p11-q1-01: 删除项目全部知识条目（ConceiveGate 重生成前清旧，避免累积）。p22-06: 无默认 */
    int deleteKnowledgeEntriesByProject(String projectId);

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
