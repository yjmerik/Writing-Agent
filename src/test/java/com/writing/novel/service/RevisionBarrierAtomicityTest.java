package com.writing.novel.service;

import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.ChapterApproval;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.ReviewBarrier;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * p36-07：正式修订在原事务中原子失效批准并记录屏障历史。
 * 修订事务中屏障更新失败则正文/历史全部回滚；v2 批准后编辑 v3，
 * v2 批准仍可查询但不算 v3 批准，且新稿必须重新人审；回退同样生成新稿待审；
 * 无变更保存不新增历史、不失效批准。
 */
class RevisionBarrierAtomicityTest {

    @TempDir
    Path tempDir;

    private ConnectionProvider cp;
    private SqliteNovelRepository repo;
    private ChapterRevisionService service;
    private String projectId;

    private static final String C2 = "第二章待复核的正文内容，长度必须超过五百字门槛。".repeat(40);
    private static final String C3 = "作者修订后的第三章正文内容，长度必须超过五百字门槛。".repeat(30);

    @BeforeEach
    void setUp() {
        cp = new ConnectionProvider(tempDir.resolve("revision.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        service = new ChapterRevisionService(repo, null, null);
        Project project = new Project("修订原子性项目", "概念", "武侠");
        projectId = project.getId();
        repo.saveProject(project);
    }

    /** v2：NEEDS_MANUAL_REVIEW + 匹配稿号屏障（人审中）。 */
    private Chapter seedChapterInReview() {
        Chapter chapter = new Chapter(projectId, 1, "第一章");
        chapter.setContent(C2);
        chapter.setStatus(Chapter.Status.NEEDS_MANUAL_REVIEW);
        repo.saveChapter(chapter);
        repo.saveReviewBarrier(new ReviewBarrier(projectId, 1, chapter.getVersion(), "根源矛盾待复核"));
        return chapter;
    }

    // --- 契约断言 ---

    @Test
    void v2批准后编辑v3_旧批准可查但不算v3批准且须重新人审() throws SQLException {
        Chapter chapter = seedChapterInReview();
        int v2 = chapter.getVersion();
        String v2Hash = ChapterApproval.contentHash("第一章", C2);

        // 作者批准 v2（p36-03 原子批准）
        ChapterApproval.Request request = readRequest(v2, v2Hash);
        repo.approveChapter(request);

        // 编辑产生 v3
        service.edit(projectId, 1, v2, "第一章", C3, "DIRECT_EDIT");

        assertEquals(v2 + 1, repo.findChapterByIndex(projectId, 1).orElseThrow().getVersion());
        // v2 批准历史保留原 identity，可查询
        assertEquals(1, countRows("SELECT COUNT(*) FROM chapter_approvals WHERE chapter_version=" + v2
                + " AND content_hash='" + v2Hash + "'"), "v2 批准行不得改写");
        // 不算 v3 批准
        ChapterApproval.ReviewContext ctx = repo.readReviewContext(projectId, 1);
        assertEquals(v2 + 1, ctx.identity().version());
        assertNull(ctx.approval(), "v3 不得继承 v2 的批准");
        // 已批准稿件再修改必须重新人审：新屏障绑定 v3
        assertFalse(repo.findActiveReviewBarriers(projectId).isEmpty(), "修订后必须有活动屏障");
        assertEquals(1, countRows("SELECT COUNT(*) FROM review_barriers WHERE status='ACTIVE' " +
                "AND chapter_version=" + (v2 + 1)), "新屏障绑定新稿号");
        assertEquals(Chapter.Status.NEEDS_MANUAL_REVIEW,
                repo.findChapterByIndex(projectId, 1).orElseThrow().getStatus(), "新稿保持待审");
        // 屏障历史记录批准失效事件（旧稿号 → 新稿号）
        assertEquals(1, countRows("SELECT COUNT(*) FROM review_barrier_history WHERE event='REVISED' " +
                "AND old_chapter_version=" + v2 + " AND new_chapter_version=" + (v2 + 1)),
                "修订使批准失效必须留痕");
    }

    @Test
    void 有人审屏障时修订_屏障重绑与历史在同一事务() throws SQLException {
        Chapter chapter = seedChapterInReview();
        int v2 = chapter.getVersion();

        service.edit(projectId, 1, v2, "第一章", C3, "DIRECT_EDIT");

        assertEquals(v2 + 1, repo.findChapterByIndex(projectId, 1).orElseThrow().getVersion());
        // 屏障重绑到新稿号并追加历史
        assertEquals(1, countRows("SELECT COUNT(*) FROM review_barriers WHERE status='ACTIVE' " +
                "AND chapter_version=" + (v2 + 1)), "ACTIVE 屏障重绑到新稿号");
        assertEquals(1, countRows("SELECT COUNT(*) FROM review_barrier_history WHERE event='REVISED' " +
                "AND old_chapter_version=" + v2 + " AND new_chapter_version=" + (v2 + 1)),
                "重绑必须追加旧屏障身份历史");
    }

    @Test
    void 修订事务中屏障更新失败则正文与历史全部回滚() throws SQLException {
        Chapter chapter = seedChapterInReview();
        int v2 = chapter.getVersion();
        try (Connection conn = cp.getConnection(); Statement st = conn.createStatement()) {
            st.execute("CREATE TRIGGER inject_barrier_failure BEFORE UPDATE ON review_barriers " +
                    "BEGIN SELECT RAISE(ABORT, 'injected barrier failure'); END;");
        }

        // 屏障重绑在修订事务内执行：注入失败必须整体回滚，而非事务后吞异常
        assertThrows(RuntimeException.class,
                () -> service.edit(projectId, 1, v2, "第一章", C3, "DIRECT_EDIT"));

        Chapter after = repo.findChapterByIndex(projectId, 1).orElseThrow();
        assertEquals(v2, after.getVersion(), "版本不得推进");
        assertEquals(C2, after.getContent(), "正文保持原稿");
        assertEquals("根源矛盾待复核", barrierReason(), "屏障原因未被改写");
        assertEquals(1, countRows("SELECT COUNT(*) FROM review_barriers WHERE status='ACTIVE' " +
                "AND chapter_version=" + v2), "屏障仍绑定旧稿号");
        assertEquals(0, countRows("SELECT COUNT(*) FROM review_barrier_history"), "屏障历史回滚");
        assertEquals(0, countRows("SELECT COUNT(*) FROM chapter_versions"), "版本快照回滚");
    }

    @Test
    void 回退也生成新稿待审() throws SQLException {
        // v1 → 编辑成 v2（留 v1 快照）→ 批准 v2 → 回退到 v1 内容产生 v3
        Chapter chapter = new Chapter(projectId, 1, "第一章");
        String c1 = "最初版本的正内容，回退目标稿。".repeat(40);
        chapter.setContent(c1);
        repo.saveChapter(chapter);
        int v1 = chapter.getVersion();
        service.edit(projectId, 1, v1, "第一章", C2, "DIRECT_EDIT");
        int v2 = v1 + 1;
        assertEquals(v2, repo.findChapterByIndex(projectId, 1).orElseThrow().getVersion());
        String v2Hash = ChapterApproval.contentHash("第一章", C2);
        repo.approveChapter(readRequest(v2, v2Hash));

        service.restore(projectId, 1, v1, v2);

        Chapter after = repo.findChapterByIndex(projectId, 1).orElseThrow();
        assertEquals(v2 + 1, after.getVersion(), "回退产生新稿号");
        assertEquals(c1, after.getContent());
        assertNull(repo.readReviewContext(projectId, 1).approval(), "回退稿不得继承 v2 批准");
        assertTrue(repo.findActiveReviewBarriers(projectId).size() >= 1, "回退稿须重新人审");
        assertEquals(Chapter.Status.NEEDS_MANUAL_REVIEW, after.getStatus(), "回退稿保持待审");
    }

    @Test
    void 无变更保存不新增历史且不失效批准() throws SQLException {
        Chapter chapter = seedChapterInReview();
        int v2 = chapter.getVersion();
        ChapterApproval.Result approval = repo.approveChapter(readRequest(v2,
                ChapterApproval.contentHash("第一章", C2)));

        // 相同标题+相同内容：幂等空操作（批准已产生的 RESOLVED 历史不因此增多）
        int historyBefore = countRows("SELECT COUNT(*) FROM review_barrier_history");
        service.edit(projectId, 1, v2, "第一章", C2, "DIRECT_EDIT");

        assertEquals(v2, repo.findChapterByIndex(projectId, 1).orElseThrow().getVersion(),
                "幂等保存不推进版本");
        assertEquals(historyBefore, countRows("SELECT COUNT(*) FROM review_barrier_history"),
                "无变更保存不新增屏障历史");
        assertEquals(0, countRows("SELECT COUNT(*) FROM chapter_versions"), "无变更不留快照");
        assertEquals(approval.approvalId(),
                repo.readReviewContext(projectId, 1).approval().approvalId(),
                "批准仍有效");
    }

    // --- helpers ---

    private ChapterApproval.Request readRequest(int version, String contentHash) {
        ChapterApproval.ReviewContext ctx = repo.readReviewContext(projectId, 1);
        return new ChapterApproval.Request(
                new ChapterApproval.Identity(projectId, 1, version, contentHash),
                ctx.reviewSetHash(), "key-" + UUID.randomUUID(), "作者确认");
    }

    private String barrierReason() {
        return repo.findActiveReviewBarriers(projectId).isEmpty()
                ? "" : repo.findActiveReviewBarriers(projectId).get(0).getReason();
    }

    private int countRows(String sql) throws SQLException {
        try (Connection conn = cp.getConnection(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
