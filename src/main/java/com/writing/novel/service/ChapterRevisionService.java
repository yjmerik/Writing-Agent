package com.writing.novel.service;

import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.ChapterApproval;
import com.writing.novel.domain.ChapterVersion;
import com.writing.novel.domain.RevisionProposal;
import com.writing.novel.spi.NovelRepository;
import com.writing.novel.telemetry.EventLog;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/** Single persistence boundary for manual and generated chapter revisions. */
public class ChapterRevisionService {

    public interface RevisionEffects {
        void refresh(String projectId, Chapter chapter);
    }

    private static final int COMPLETED_CONTENT_MIN = 500;
    private static final int LOCK_STRIPES = 64;
    private static final ReentrantLock[] REVISION_LOCKS = createRevisionLocks();

    private final NovelRepository repository;
    private final WritingTaskExecutor writingTaskExecutor;
    private final RevisionEffects effects;
    /** NCS-009: 来源撤回传播；null 时不触发（旧装配/测试） */
    private final SupportWithdrawalService supportWithdrawal;
    /** NCS-013: 统一修订请求登记；null 时不登记（旧装配/测试） */
    private final RevisionRequestService revisionRequests;

    public ChapterRevisionService(NovelRepository repository) {
        this(repository, null, null, null, null);
    }

    public ChapterRevisionService(NovelRepository repository,
                                  WritingTaskExecutor writingTaskExecutor,
                                  RevisionEffects effects) {
        this(repository, writingTaskExecutor, effects, null, null);
    }

    public ChapterRevisionService(NovelRepository repository,
                                  WritingTaskExecutor writingTaskExecutor,
                                  RevisionEffects effects,
                                  SupportWithdrawalService supportWithdrawal) {
        this(repository, writingTaskExecutor, effects, supportWithdrawal, null);
    }

    /** NCS-013: 带统一修订请求登记的构造 */
    public ChapterRevisionService(NovelRepository repository,
                                  WritingTaskExecutor writingTaskExecutor,
                                  RevisionEffects effects,
                                  SupportWithdrawalService supportWithdrawal,
                                  RevisionRequestService revisionRequests) {
        this.repository = repository;
        this.writingTaskExecutor = writingTaskExecutor;
        this.effects = effects;
        this.supportWithdrawal = supportWithdrawal;
        this.revisionRequests = revisionRequests;
    }

    public Chapter createDraft(String projectId, String title, String content) {
        return withProjectWork(projectId, () -> {
            requireProject(projectId);
            String safeTitle = requireTitle(title);
            String safeContent = content == null ? "" : content;
            int nextIndex = repository.findChaptersByProject(projectId).stream()
                    .mapToInt(Chapter::getChapterIndex).max().orElse(0) + 1;
            Chapter draft = new Chapter(projectId, nextIndex, safeTitle);
            if (!safeContent.isEmpty()) draft.setContent(safeContent);
            draft.setStatus(safeContent.length() >= COMPLETED_CONTENT_MIN
                    ? Chapter.Status.DONE : Chapter.Status.DRAFTING);
            repository.saveChapter(draft);
            refreshIsolated(projectId, draft);
            return draft;
        });
    }

    public Chapter edit(String projectId, int chapterIndex, int expectedVersion,
                        String title, String content, String source) {
        return withProjectWork(projectId, () -> editInOwnedWork(
                projectId, chapterIndex, expectedVersion, title, content, source));
    }

    /** Used by an already-owned rewrite/passage workflow. */
    public Chapter editInOwnedWork(String projectId, int chapterIndex, int expectedVersion,
                                   String title, String content, String source) {
        return withChapterRevisionLock(projectId, chapterIndex,
                () -> doEdit(projectId, chapterIndex, expectedVersion, title, content, source));
    }

    private Chapter doEdit(String projectId, int chapterIndex, int expectedVersion,
                           String title, String content, String source) {
        Chapter current = requireChapter(projectId, chapterIndex);
        if (current.getVersion() != expectedVersion) {
            throw new IllegalStateException("章节版本已变化");
        }
        String safeTitle = requireTitle(title);
        String safeSource = normalizedSource(source);
        String safeContent = "DIRECT_EDIT".equals(safeSource)
                ? requireBody(content) : (content == null ? "" : content);
        if ("DIRECT_EDIT".equals(safeSource)
                && current.getStatus() != Chapter.Status.DRAFTING
                && safeContent.length() < COMPLETED_CONTENT_MIN) {
            throw new IllegalArgumentException("已完成章节正文不能少于500字");
        }

        // p33-05（体验 #9）：相同内容+标题的保存是幂等空操作——不推进版本、不留快照。
        // 作者界面重复点击或断线重试不再产生垃圾版本；语义上"这次保存"已经生效。
        if (current.getTitle().equals(safeTitle) && current.getContent().equals(safeContent)) {
            refreshIsolated(projectId, current);
            return current;
        }
        ChapterVersion snapshot = new ChapterVersion(
                UUID.randomUUID().toString(), projectId, chapterIndex, current.getVersion(),
                current.getTitle(), current.getContent(), safeSource, Instant.now());
        current.setTitle(safeTitle);
        current.setContent(safeContent);
        if ("RESTORE".equals(safeSource)) {
            current.setStatus(safeContent.length() >= COMPLETED_CONTENT_MIN
                    ? Chapter.Status.DONE : Chapter.Status.DRAFTING);
        } else if (current.getStatus() == Chapter.Status.DRAFTING
                && safeContent.length() >= COMPLETED_CONTENT_MIN) {
            current.setStatus(Chapter.Status.DONE);
        }
        if (!repository.saveChapterRevision(snapshot, current, expectedVersion)) {
            throw new IllegalStateException("章节版本已变化");
        }
        // p36-07: 屏障重绑/批准失效历史已在 saveChapterRevision 同一事务内原子完成——
        // 事务后吞异常的 rebind 已删除，避免"正文已推进而屏障仍绑旧稿号"的半套状态。
        withdrawStaleEvidence(projectId, chapterIndex, current.getVersion());
        if (revisionRequests != null && "DIRECT_EDIT".equals(normalizedSource(source))) {
            revisionRequests.recordAndComplete(projectId, "DIRECT_EDIT",
                    "作者直接编辑第 " + chapterIndex + " 章",
                    "[{\"chapterIndex\":" + chapterIndex + "}]",
                    "{\"" + chapterIndex + "\":" + expectedVersion + "}", "[]",
                    "作者直存并推进到 v" + current.getVersion());
        }
        refreshIsolated(projectId, current);
        return current;
    }

    /**
     * NCS-009: 正文版本推进后传播证据失效（同 p32-01 容错模式——
     * 传播失败不回滚已保存正文，留事件待重试；方法幂等可重入）。
     */
    private void withdrawStaleEvidence(String projectId, int chapterIndex, int newVersion) {
        if (supportWithdrawal == null) return;
        try {
            supportWithdrawal.onChapterRevised(projectId, chapterIndex, newVersion);
        } catch (Exception e) {
            EventLog.emit("SUPPORT_WITHDRAWAL_FAILED", Map.of(
                    "projectId", projectId,
                    "chapterIndex", chapterIndex,
                    "newVersion", newVersion,
                    "errorClass", e.getClass().getSimpleName(),
                    "message", EventLog.truncate(e.getMessage(), 150)));
        }
    }

    /**
     * p32-01（体验报告 #2/P1）: 正文事务已提交，派生刷新失败不得回滚为"保存失败"。
     * 失败时保留已保存内容，标记 derivedRefreshPending 并发 CHAPTER_REFRESH_FAILED 事件，
     * 派生数据（摘要/索引/收割）由后续写作或下次刷新重算。
     */
    private void refreshIsolated(String projectId, Chapter chapter) {
        try {
            refresh(projectId, chapter);
        } catch (Exception e) {
            chapter.setDerivedRefreshPending(true);
            EventLog.emit("CHAPTER_REFRESH_FAILED", Map.of(
                    "projectId", projectId,
                    "chapterIndex", chapter.getChapterIndex(),
                    "version", chapter.getVersion(),
                    "errorClass", e.getClass().getSimpleName(),
                    "message", String.valueOf(e.getMessage())));
        }
    }

    /** Serialize a chapter mutation with its derived-data refresh. */
    public static <T> T withChapterRevisionLock(String projectId, int chapterIndex,
                                                java.util.function.Supplier<T> work) {
        ReentrantLock lock = revisionLock(projectId, chapterIndex);
        lock.lock();
        try {
            return work.get();
        } finally {
            lock.unlock();
        }
    }

    public static void withChapterRevisionLock(String projectId, int chapterIndex, Runnable work) {
        withChapterRevisionLock(projectId, chapterIndex, () -> {
            work.run();
            return null;
        });
    }

    /** Runs only the short persistence phase when the chapter still has the expected version. */
    public static final class DerivedWriteBoundary {
        private final NovelRepository repository;
        private final String projectId;
        private final int chapterIndex;
        private final int expectedVersion;

        public DerivedWriteBoundary(NovelRepository repository, String projectId,
                                    int chapterIndex, int expectedVersion) {
            this.repository = repository;
            this.projectId = projectId;
            this.chapterIndex = chapterIndex;
            this.expectedVersion = expectedVersion;
        }

        public <T> T apply(java.util.function.Supplier<T> write, T staleResult) {
            return withChapterRevisionLock(projectId, chapterIndex, () -> {
                boolean current = repository.findChapterByIndex(projectId, chapterIndex)
                        .map(chapter -> chapter.getVersion() == expectedVersion)
                        .orElse(false);
                return current ? write.get() : staleResult;
            });
        }
    }

    private static ReentrantLock revisionLock(String projectId, int chapterIndex) {
        int hash = 31 * projectId.hashCode() + chapterIndex;
        return REVISION_LOCKS[Math.floorMod(hash, LOCK_STRIPES)];
    }

    private static ReentrantLock[] createRevisionLocks() {
        ReentrantLock[] locks = new ReentrantLock[LOCK_STRIPES];
        for (int i = 0; i < locks.length; i++) locks[i] = new ReentrantLock(true);
        return locks;
    }

    public Chapter restore(String projectId, int chapterIndex, int historyVersion, int expectedVersion) {
        Chapter result = withProjectWork(projectId, () -> {
            Chapter current = requireChapter(projectId, chapterIndex);
            if (current.getVersion() != expectedVersion) {
                throw new IllegalStateException("章节版本已变化");
            }
            ChapterVersion target = repository.findChapterVersion(projectId, chapterIndex, historyVersion)
                    .orElseThrow(() -> new IllegalArgumentException("章节历史版本不存在: " + historyVersion));
            return editInOwnedWork(projectId, chapterIndex, expectedVersion,
                    target.getTitle(), target.getContent(), "RESTORE");
        });
        if (revisionRequests != null) {
            revisionRequests.recordAndComplete(projectId, "RESTORE",
                    "以第 " + chapterIndex + " 章 v" + historyVersion + " 的内容恢复为新稿",
                    "[{\"chapterIndex\":" + chapterIndex + ",\"historyVersion\":" + historyVersion + "}]",
                    "{\"" + chapterIndex + "\":" + expectedVersion + "}", "[]",
                    "恢复完成（旧历史保留）");
        }
        return result;
    }

    /** p37-14: 候选应用结果——仓储持久 commit result + 应用后的当前章（含派生刷新标记）。 */
    public record ProposalApplyResult(NovelRepository.ProposalCommitResult commit, Chapter chapter) {
    }

    /** 统一修订边界：候选应用入口（获取项目工作权）。 */
    public ProposalApplyResult applyProposal(String projectId, String proposalId) {
        return withProjectWork(projectId, () -> applyProposalInOwnedWork(projectId, proposalId));
    }

    /** Used by an already-owned workflow. */
    public ProposalApplyResult applyProposalInOwnedWork(String projectId, String proposalId) {
        RevisionProposal proposal = repository.findProposal(projectId, proposalId)
                .orElseThrow(() -> new IllegalArgumentException("修订候选不存在"));
        if (proposal.getTargetType() != RevisionProposal.TargetType.CHAPTER
                && proposal.getTargetType() != RevisionProposal.TargetType.PASSAGE) {
            throw new IllegalArgumentException("非章节候选请使用对应对象边界");
        }
        return withChapterRevisionLock(projectId, proposal.getChapterIndex(),
                () -> doApplyProposal(projectId, proposal));
    }

    private ProposalApplyResult doApplyProposal(String projectId, RevisionProposal proposal) {
        Chapter current = requireChapter(projectId, proposal.getChapterIndex());
        // 网络重试：候选已 APPLIED——重试派生刷新并重放持久 result，绝不再次写正文
        if (proposal.getStatus() == RevisionProposal.Status.APPLIED) {
            Chapter applied = requireChapter(projectId, proposal.getChapterIndex());
            refreshIsolated(projectId, applied);
            return new ProposalApplyResult(new NovelRepository.ProposalCommitResult(
                    true, proposal.getId(), proposal.getResultJson(), applied.getVersion()), applied);
        }
        if (proposal.getStatus() != RevisionProposal.Status.READY) {
            throw new IllegalStateException("候选不可应用: " + proposal.getStatus());
        }
        // p37-14: 来源身份比较先于一切候选校验——任何全文变动后段落候选都必须冲突，
        // 而不是撞上针对旧全文的校验。冲突在独立短事务标 STALE，绝不改正文。
        ChapterApproval.Identity currentIdentity = ChapterApproval.Identity.of(current);
        boolean sourceMatches = currentIdentity.version() == proposal.getBaseVersion()
                && currentIdentity.contentHash().equals(proposal.getSourceHash());
        if (!sourceMatches) {
            repository.markProposalStale(projectId, proposal.getId());
            throw new NovelRepository.ProposalSourceConflictException(proposal.getId(), currentIdentity);
        }
        com.fasterxml.jackson.databind.node.ObjectNode after = parseProposalAfter(proposal);
        String newTitle;
        String newContent;
        if (proposal.getTargetType() == RevisionProposal.TargetType.CHAPTER) {
            newTitle = requiredCandidateText(after, "title");
            newContent = requiredCandidateText(after, "content");
        } else {
            String[] passages = current.getContent().split("\n", -1);
            int passageIdx = proposal.getPassageIndex() == null ? -1 : proposal.getPassageIndex();
            if (passageIdx < 0 || passageIdx >= passages.length) {
                repository.markProposalStale(projectId, proposal.getId());
                throw new NovelRepository.ProposalSourceConflictException(proposal.getId(), currentIdentity);
            }
            passages[passageIdx] = requiredCandidateText(after, "content");
            newTitle = current.getTitle();
            newContent = String.join("\n", passages);
        }
        // 与 doEdit 同等校验：标题/正文必填、已完成章节 ≥500 字门槛
        String safeTitle = requireTitle(newTitle);
        String safeContent = requireBody(newContent);
        if (current.getStatus() != Chapter.Status.DRAFTING
                && safeContent.length() < COMPLETED_CONTENT_MIN) {
            throw new IllegalArgumentException("已完成章节正文不能少于500字");
        }
        if (current.getTitle().equals(safeTitle) && current.getContent().equals(safeContent)) {
            throw new IllegalStateException("候选内容与当前稿一致，无需应用");
        }
        // 来源身份先于变更捕获；快照沿用 doEdit（变更前不可变快照）
        ChapterVersion snapshot = new ChapterVersion(UUID.randomUUID().toString(), projectId,
                current.getChapterIndex(), current.getVersion(), current.getTitle(), current.getContent(),
                "PROPOSAL", Instant.now());
        Chapter revised = current;
        revised.setTitle(safeTitle);
        revised.setContent(safeContent);
        if (revised.getStatus() == Chapter.Status.DRAFTING
                && safeContent.length() >= COMPLETED_CONTENT_MIN) {
            revised.setStatus(Chapter.Status.DONE);
        }
        NovelRepository.ProposalCommitResult commit = repository.commitProposalChapter(
                projectId, proposal.getId(), snapshot, revised, currentIdentity);
        Chapter applied = repository.findChapterByIndex(projectId, proposal.getChapterIndex())
                .orElseThrow(() -> new IllegalStateException("候选应用后章节不存在"));
        withdrawStaleEvidence(projectId, proposal.getChapterIndex(), applied.getVersion());
        if (revisionRequests != null) {
            String origin = proposal.getTargetType() == RevisionProposal.TargetType.PASSAGE
                    ? "PASSAGE_POLISH" : "CHAPTER_REWRITE";
            revisionRequests.recordAndComplete(projectId, origin,
                    "采纳候选 " + proposal.getId() + "（" + (proposal.getInstructions() == null ? "" : proposal.getInstructions()) + "）",
                    "[{\"chapterIndex\":" + proposal.getChapterIndex() + "}]",
                    "{\"" + proposal.getChapterIndex() + "\":" + currentIdentity.version() + "}", "[]",
                    "候选应用完成，proposalId=" + commit.proposalId());
        }
        // 派生刷新在提交后执行：失败标 pending，不得再次写一版正文
        refreshIsolated(projectId, applied);
        return new ProposalApplyResult(commit, applied);
    }

    private com.fasterxml.jackson.databind.node.ObjectNode parseProposalAfter(RevisionProposal proposal) {
        try {
            com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(proposal.getAfterJson());
            if (node == null || !node.isObject()) {
                throw new IllegalStateException("候选 after 内容无效");
            }
            return (com.fasterxml.jackson.databind.node.ObjectNode) node;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("候选 after 内容无效", e);
        }
    }

    private String requiredCandidateText(com.fasterxml.jackson.databind.JsonNode node, String field) {
        com.fasterxml.jackson.databind.JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("候选缺少字段: " + field);
        }
        return value.asText();
    }

    public List<ChapterVersion> history(String projectId, int chapterIndex) {
        requireChapter(projectId, chapterIndex);
        return repository.findChapterVersions(projectId, chapterIndex);
    }

    private Chapter requireChapter(String projectId, int chapterIndex) {
        requireProject(projectId);
        return repository.findChapterByIndex(projectId, chapterIndex)
                .orElseThrow(() -> new IllegalArgumentException("章节不存在: " + chapterIndex));
    }

    private void requireProject(String projectId) {
        if (repository.findProject(projectId).isEmpty()) {
            throw new IllegalArgumentException("项目不存在: " + projectId);
        }
    }

    private String requireTitle(String title) {
        if (title == null || title.isBlank()) throw new IllegalArgumentException("章节标题不能为空");
        return title.trim();
    }

    private String requireBody(String content) {
        if (content == null || content.isBlank()) throw new IllegalArgumentException("章节正文不能为空");
        return content;
    }

    private String normalizedSource(String source) {
        return source == null || source.isBlank() ? "DIRECT_EDIT" : source;
    }

    private void refresh(String projectId, Chapter chapter) {
        if (effects != null) effects.refresh(projectId, chapter);
    }

    private <T> T withProjectWork(String projectId, java.util.function.Supplier<T> work) {
        if (writingTaskExecutor == null) return work.get();
        String workId = "chapter-revision-" + UUID.randomUUID();
        writingTaskExecutor.beginWork(projectId, workId);
        try {
            return work.get();
        } finally {
            writingTaskExecutor.endWork(projectId, workId);
        }
    }
}
