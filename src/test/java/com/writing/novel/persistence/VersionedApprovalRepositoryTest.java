package com.writing.novel.persistence;

import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.ChapterApproval;
import com.writing.novel.domain.Checkpoint;
import com.writing.novel.domain.NovelState;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.ReviewBarrier;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.spi.NovelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CyclicBarrier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * p36-03：章节批准与屏障的原子比较提交契约。
 * 幂等重放、双重批准守卫、身份/待审集合比较、并发单赢家与注入失败全回滚。
 */
class VersionedApprovalRepositoryTest {

    @TempDir
    Path tempDir;

    private ConnectionProvider cp;
    private SqliteNovelRepository repo;
    private String projectId;
    private Checkpoint chapterCheckpoint;

    @BeforeEach
    void setUp() throws SQLException {
        cp = new ConnectionProvider(tempDir.resolve("approval.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);

        Project project = new Project("原子批准项目", "概念", "武侠");
        projectId = project.getId();
        project.setState(NovelState.PAUSED_AT_CHECKPOINT);
        repo.saveProject(project);

        Chapter chapter = new Chapter(projectId, 1, "第一章");
        chapter.setContent("待作者复核的正文内容，长度足以构成身份。");
        chapter.setStatus(Chapter.Status.NEEDS_MANUAL_REVIEW);
        repo.saveChapter(chapter);
        repo.saveReviewBarrier(new ReviewBarrier(projectId, 1, chapter.getVersion(), "根源矛盾待复核"));

        chapterCheckpoint = new Checkpoint(projectId, Checkpoint.Type.AFTER_CHAPTER,
                "第1章「第一章」已完成，待审核");
        chapterCheckpoint.setPayload("{\"chapterIndex\":1,\"title\":\"第一章\"}");
        repo.saveCheckpoint(chapterCheckpoint);
    }

    // --- helpers ---

    private Chapter chapterFromDb() {
        return repo.findChapterByIndex(projectId, 1).orElseThrow();
    }

    private ChapterApproval.Request readRequest(String idempotencyKey) {
        ChapterApproval.ReviewContext ctx = repo.readReviewContext(projectId, 1);
        return new ChapterApproval.Request(ctx.identity(), ctx.reviewSetHash(), idempotencyKey, "作者确认");
    }

    private int countRows(String sql) throws SQLException {
        try (Connection conn = cp.getConnection(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    // --- 契约断言 ---

    @Test
    void 审阅上下文反映当前身份与待审集合() {
        Chapter chapter = chapterFromDb();
        ChapterApproval.ReviewContext ctx = repo.readReviewContext(projectId, 1);

        assertEquals(ChapterApproval.Identity.of(chapter), ctx.identity(), "上下文身份=当前章节身份");
        assertTrue(ctx.reviewSetHash().matches("[0-9a-f]{64}"), "reviewSetHash 为 64 位 hex");
        assertEquals(1, ctx.barrierIds().size(), "待审屏障应包含当前屏障");
        assertEquals(1, ctx.pendingCheckpointIds().size(), "待审章节类检查点应包含 AFTER_CHAPTER");
        assertTrue(ctx.pendingCheckpointIds().contains(chapterCheckpoint.getId()));
        assertNull(ctx.reportCurrent(), "无报告时 reportCurrent 为 null");
        assertNull(ctx.approval(), "未批准时 approval 为 null");
        assertTrue(ctx.issues().isEmpty() && ctx.dispositions().isEmpty() && ctx.directives().isEmpty(),
                "预留集合默认空");
    }

    @Test
    void 批准成功解决屏障检查点并退出停点() throws SQLException {
        ChapterApproval.Result result = repo.approveChapter(readRequest("key-ok"));

        assertFalse(result.replayed(), "首次批准不是重放");
        assertEquals(ChapterApproval.Identity.of(chapterFromDb()), result.identity());
        assertEquals(Chapter.Status.DONE, chapterFromDb().getStatus(), "精确版本标记 DONE");
        assertTrue(repo.findActiveReviewBarriers(projectId).isEmpty(), "匹配稿号屏障已解决");
        assertEquals(Checkpoint.Status.APPROVED,
                repo.findCheckpoint(chapterCheckpoint.getId()).orElseThrow().getStatus(),
                "同章章节类检查点已处理");
        assertEquals(1, countRows("SELECT COUNT(*) FROM review_barrier_history WHERE event='RESOLVED'"),
                "屏障放行应追加历史");

        ChapterApproval.ReviewContext after = repo.readReviewContext(projectId, 1);
        assertNotNull(after.approval(), "批准后上下文应携带既有批准");
        assertEquals(result.approvalId(), after.approval().approvalId());
        assertTrue(after.barrierIds().isEmpty());
        assertTrue(after.pendingCheckpointIds().isEmpty());
        assertEquals(NovelState.WRITING, repo.findProject(projectId).orElseThrow().getState(),
                "无剩余待审项时退出停点");
    }

    @Test
    void 同key同请求重试返回相同approvalId且replayed() throws SQLException {
        ChapterApproval.Request request = readRequest("key-retry");
        ChapterApproval.Result first = repo.approveChapter(request);
        ChapterApproval.Result replay = repo.approveChapter(request);

        assertTrue(replay.replayed(), "同 key 同请求应返回重放");
        assertEquals(first.approvalId(), replay.approvalId(), "重放返回原 approvalId");
        assertEquals(1, countRows("SELECT COUNT(*) FROM chapter_approvals"), "不得产生第二条批准");
    }

    @Test
    void 同key不同请求冲突且原结果保持() throws SQLException {
        ChapterApproval.Result first = repo.approveChapter(readRequest("key-dup"));

        // 批准后待审集合已变化：同 key + 新 reviewSetHash = 不同语义
        ChapterApproval.Request mutated = readRequest("key-dup");
        NovelRepository.ReviewConflictException conflict =
                assertThrows(NovelRepository.ReviewConflictException.class, () -> repo.approveChapter(mutated));
        assertEquals(NovelRepository.ReviewConflictException.Reason.IDEMPOTENCY_CONFLICT,
                conflict.getReason());
        assertEquals(1, countRows("SELECT COUNT(*) FROM chapter_approvals"), "冲突不新增批准");
        ChapterApproval.ReviewContext ctx = repo.readReviewContext(projectId, 1);
        assertEquals(first.approvalId(), ctx.approval().approvalId(), "原批准保持原样");
    }

    @Test
    void 不同key重复批准同一身份被拒绝() throws SQLException {
        ChapterApproval.Request first = readRequest("key-a");
        ChapterApproval.Identity identity = first.identity();
        String reviewSetHash = first.reviewSetHash();
        repo.approveChapter(first);

        // 双击场景：不同 key、同一身份（待审集合指纹用批准前读到的旧值）
        ChapterApproval.Request doubleClick = new ChapterApproval.Request(
                identity, reviewSetHash, "key-b", "作者确认");
        NovelRepository.ReviewConflictException conflict =
                assertThrows(NovelRepository.ReviewConflictException.class, () -> repo.approveChapter(doubleClick));
        assertEquals(NovelRepository.ReviewConflictException.Reason.ALREADY_APPROVED, conflict.getReason());
        assertEquals(1, countRows("SELECT COUNT(*) FROM chapter_approvals"), "不同 key 双击不算两次批准");
    }

    @Test
    void 旧version旧hash旧reviewSetHash均拒绝且无任何写入() throws SQLException {
        ChapterApproval.ReviewContext ctx = repo.readReviewContext(projectId, 1);
        ChapterApproval.Identity identity = ctx.identity();
        String reviewSetHash = ctx.reviewSetHash();

        // 旧 version（未来稿号）
        ChapterApproval.Identity staleVersion = new ChapterApproval.Identity(
                projectId, 1, identity.version() + 1, identity.contentHash());
        var e1 = assertThrows(NovelRepository.ReviewConflictException.class,
                () -> repo.approveChapter(new ChapterApproval.Request(staleVersion, reviewSetHash, "k-v", null)));
        assertEquals(NovelRepository.ReviewConflictException.Reason.IDENTITY_STALE, e1.getReason());

        // 旧 contentHash
        ChapterApproval.Identity staleHash = new ChapterApproval.Identity(
                projectId, 1, identity.version(), "0".repeat(64));
        var e2 = assertThrows(NovelRepository.ReviewConflictException.class,
                () -> repo.approveChapter(new ChapterApproval.Request(staleHash, reviewSetHash, "k-h", null)));
        assertEquals(NovelRepository.ReviewConflictException.Reason.IDENTITY_STALE, e2.getReason());

        // 旧 reviewSetHash
        var e3 = assertThrows(NovelRepository.ReviewConflictException.class,
                () -> repo.approveChapter(new ChapterApproval.Request(identity, "f".repeat(64), "k-r", null)));
        assertEquals(NovelRepository.ReviewConflictException.Reason.REVIEW_SET_STALE, e3.getReason());

        // 读取后新增屏障：先前的 reviewSetHash 立即过期
        repo.saveReviewBarrier(new ReviewBarrier(projectId, 2, 1, "另一章新增屏障"));
        var e4 = assertThrows(NovelRepository.ReviewConflictException.class,
                () -> repo.approveChapter(new ChapterApproval.Request(identity, reviewSetHash, "k-dyn", null)));
        assertEquals(NovelRepository.ReviewConflictException.Reason.REVIEW_SET_STALE, e4.getReason());

        // 全部拒绝：无任何写入
        assertEquals(0, countRows("SELECT COUNT(*) FROM chapter_approvals"));
        assertEquals(Chapter.Status.NEEDS_MANUAL_REVIEW, chapterFromDb().getStatus());
        assertEquals(2, countRows("SELECT COUNT(*) FROM review_barriers WHERE status='ACTIVE'"));
        assertEquals(Checkpoint.Status.PENDING,
                repo.findCheckpoint(chapterCheckpoint.getId()).orElseThrow().getStatus());
    }

    @Test
    void 两连接同时用不同key批准同一待办仅一方成功() throws Exception {
        ChapterApproval.ReviewContext ctx = repo.readReviewContext(projectId, 1);
        ChapterApproval.Identity identity = ctx.identity();
        String reviewSetHash = ctx.reviewSetHash();

        CyclicBarrier gate = new CyclicBarrier(2);
        List<ChapterApproval.Result> wins = new ArrayList<>();
        List<Exception> losses = new ArrayList<>();
        java.util.function.Function<String, Runnable> attempt = key -> () -> {
            try {
                gate.await();
                ChapterApproval.Result r = repo.approveChapter(new ChapterApproval.Request(
                        identity, reviewSetHash, key, "并发批准"));
                synchronized (wins) { wins.add(r); }
            } catch (Exception e) {
                synchronized (losses) { losses.add(e); }
            }
        };
        Thread t1 = new Thread(attempt.apply("key-t1"), "approver-1");
        Thread t2 = new Thread(attempt.apply("key-t2"), "approver-2");
        t1.start(); t2.start();
        t1.join(10_000); t2.join(10_000);

        assertEquals(1, wins.size(), "恰好一方成功，实际失败: " + losses);
        assertEquals(1, losses.size(), "另一方必须失败，不得双双成功");
        assertFalse(wins.get(0).replayed());
        assertEquals(1, countRows("SELECT COUNT(*) FROM chapter_approvals"), "只写一条批准");
        assertEquals(Chapter.Status.DONE, chapterFromDb().getStatus());
        assertEquals(1, countRows("SELECT COUNT(*) FROM review_barrier_history WHERE event='RESOLVED'"));
    }

    @Test
    void 批准行写入后注入SQL错误全部回滚() throws SQLException {
        try (Connection conn = cp.getConnection(); Statement st = conn.createStatement()) {
            st.execute("CREATE TRIGGER inject_failure BEFORE UPDATE ON chapters " +
                    "BEGIN SELECT RAISE(ABORT, 'injected failure'); END;");
        }

        ChapterApproval.Request request = readRequest("key-fail");
        assertThrows(RuntimeException.class, () -> repo.approveChapter(request),
                "批准行写入后的章节更新失败必须整体回滚");

        assertEquals(0, countRows("SELECT COUNT(*) FROM chapter_approvals"), "批准审计回滚");
        assertEquals(Chapter.Status.NEEDS_MANUAL_REVIEW, chapterFromDb().getStatus(), "正文状态不变");
        assertEquals(1, countRows("SELECT COUNT(*) FROM review_barriers WHERE status='ACTIVE'"),
                "屏障保持待审");
        assertEquals(Checkpoint.Status.PENDING,
                repo.findCheckpoint(chapterCheckpoint.getId()).orElseThrow().getStatus(), "检查点不变");
        assertEquals(0, countRows("SELECT COUNT(*) FROM review_barrier_history"), "屏障历史回滚");
        assertEquals(NovelState.PAUSED_AT_CHECKPOINT,
                repo.findProject(projectId).orElseThrow().getState(), "项目停点状态不变");
    }
}
