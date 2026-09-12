package com.writing.novel.persistence;

import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.ChapterApproval;
import com.writing.novel.domain.Project;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * p36-02：不可变审阅身份与批准审计表契约。
 * chapter_approvals 以 (project_id, idempotency_key) 唯一；普通章节保存不得触碰批准行；
 * contentHash 只由标题/正文决定；schema 重复初始化不丢数据。
 */
class ApprovalSchemaTest {

    @TempDir
    Path tempDir;

    @Test
    void 旧库连续初始化两次无丢行() throws SQLException {
        String dbPath = tempDir.resolve("legacy.db").toString();
        // 旧库：只有 p36-02 之前就存在的表，且已有作者数据
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE projects (id TEXT PRIMARY KEY, title TEXT, concept TEXT, tags TEXT, " +
                    "mode TEXT, state TEXT, current_chapter_index INTEGER, genre TEXT, " +
                    "created_at TEXT, updated_at TEXT)");
            st.execute("CREATE TABLE chapters (id TEXT PRIMARY KEY, project_id TEXT, chapter_index INTEGER, " +
                    "title TEXT, content TEXT, word_count INTEGER, version INTEGER, " +
                    "status TEXT DEFAULT 'DONE', created_at TEXT, updated_at TEXT)");
            st.execute("CREATE TABLE review_barriers (id TEXT PRIMARY KEY, project_id TEXT, " +
                    "chapter_index INTEGER, chapter_version INTEGER, reason TEXT, status TEXT, " +
                    "created_at TEXT, resolved_at TEXT)");
            st.execute("INSERT INTO projects (id,title,concept,tags,mode,state,current_chapter_index," +
                    "created_at,updated_at) VALUES ('p1','旧项目','概念','', 'SOLO','WRITING',1," +
                    "'2026-01-01T00:00:00Z','2026-01-01T00:00:00Z')");
            st.execute("INSERT INTO chapters (id,project_id,chapter_index,title,content,word_count,version," +
                    "status,created_at,updated_at) VALUES ('c1','p1',1,'第一章','旧正文',3,1," +
                    "'DONE','2026-01-01T00:00:00Z','2026-01-01T00:00:00Z')");
            st.execute("INSERT INTO review_barriers (id,project_id,chapter_index,chapter_version,reason," +
                    "status,created_at) VALUES ('b1','p1',1,1,'根源矛盾','ACTIVE','2026-01-01T00:00:00Z')");
        }

        ConnectionProvider cp = new ConnectionProvider(dbPath);
        new SqliteSchemaInitializer(cp).initialize();

        // 第一次初始化后通过正式仓储写入更多数据，含批准审计行
        SqliteNovelRepository repo = new SqliteNovelRepository(cp);
        Chapter chapter = new Chapter("p1", 2, "第二章");
        chapter.setContent("初始化后写入的正文，长度足以入库。");
        repo.saveChapter(chapter);
        insertApproval(cp, "a1", "p1", 1, 1, ChapterApproval.contentHash("第一章", "旧正文"),
                "rs-old", "legacy-key", "旧库批准");

        // 连续第二次初始化：不得丢任何行
        new SqliteSchemaInitializer(cp).initialize();

        try (Connection conn = cp.getConnection(); Statement st = conn.createStatement()) {
            assertEquals(1, countRows(st, "projects"), "项目行不得丢失");
            assertEquals(2, countRows(st, "chapters"), "章节行不得丢失");
            assertEquals(1, countRows(st, "review_barriers"), "屏障行不得丢失");
            assertEquals(1, countRows(st, "chapter_approvals"), "批准审计行不得丢失");
            assertEquals(0, countRows(st, "review_barrier_history"), "历史表应存在且无预填行");
            assertEquals("旧正文", scalar(st, "SELECT content FROM chapters WHERE id='c1'"),
                    "旧章节正文保持原样");
        }
    }

    @Test
    void 标题或正文变化改变hash而状态变化不影响() {
        String base = ChapterApproval.contentHash("标题甲", "正文第一段。");
        // 确定性：同输入同 hash
        assertEquals(base, ChapterApproval.contentHash("标题甲", "正文第一段。"));
        // 64位小写hex
        assertTrue(base.matches("[0-9a-f]{64}"), "应为64位小写hex: " + base);
        // 标题变化改变 hash
        assertNotEquals(base, ChapterApproval.contentHash("标题乙", "正文第一段。"));
        // 正文变化改变 hash
        assertNotEquals(base, ChapterApproval.contentHash("标题甲", "正文第二段。"));
        // 长度前缀杜绝切分歧义：(标题="ab",正文="c") 与 (标题="a",正文="bc") 必须不同
        assertNotEquals(ChapterApproval.contentHash("ab", "c"), ChapterApproval.contentHash("a", "bc"));

        // 状态/updatedAt 不参与：同标题同正文同版本，仅状态不同 → 身份相同
        Chapter done = new Chapter("p1", 1, "标题甲");
        done.setContent("正文第一段。");
        Chapter needsReview = new Chapter("p1", 1, "标题甲");
        needsReview.setContent("正文第一段。");
        needsReview.setStatus(Chapter.Status.NEEDS_MANUAL_REVIEW);
        assertEquals(done.getVersion(), needsReview.getVersion());
        assertEquals(ChapterApproval.Identity.of(done), ChapterApproval.Identity.of(needsReview),
                "状态变化不得改变稿件身份");
        assertNotEquals(done.getUpdatedAt(), needsReview.getUpdatedAt());
    }

    @Test
    void 批准记录不能被普通章保存覆盖() throws SQLException {
        String dbPath = tempDir.resolve("approve.db").toString();
        ConnectionProvider cp = new ConnectionProvider(dbPath);
        new SqliteSchemaInitializer(cp).initialize();
        SqliteNovelRepository repo = new SqliteNovelRepository(cp);

        Project project = new Project("测试项目", "概念", "武侠");
        String projectId = project.getId();
        repo.saveProject(project);
        Chapter chapter = new Chapter(projectId, 1, "第一章");
        chapter.setContent("作者批准时的正文v2。");
        repo.saveChapter(chapter);

        String approvalId = "a-approve";
        String reviewSetHash = "rs-v2";
        String approvalHash = ChapterApproval.contentHash("第一章", chapter.getContent());
        int approvedVersion = chapter.getVersion();
        insertApproval(cp, approvalId, projectId, 1, approvedVersion, approvalHash,
                reviewSetHash, "key-approve", "作者确认");

        // 作者之后继续修订并保存新稿——批准行必须原样保留
        chapter.setContent("作者修订后的正文v3。");
        repo.saveChapter(chapter);
        repo.saveChapter(chapter); // 重复保存同样不得触碰批准表

        try (Connection conn = cp.getConnection(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, chapter_version, content_hash, review_set_hash, " +
                     "idempotency_key, reason, created_at FROM chapter_approvals")) {
            assertTrue(rs.next(), "批准记录必须仍存在");
            assertEquals(approvalId, rs.getString("id"));
            assertEquals(approvedVersion, rs.getInt("chapter_version"),
                    "批准行仍绑定批准时的稿件版本");
            assertEquals(approvalHash, rs.getString("content_hash"), "批准行内容指纹保持v2");
            assertEquals(reviewSetHash, rs.getString("review_set_hash"));
            assertEquals("key-approve", rs.getString("idempotency_key"));
            assertEquals("作者确认", rs.getString("reason"));
            assertFalse(rs.next(), "批准记录不得被复制或重复");
        }
    }

    @Test
    void 相同幂等键同项目唯一且跨项目可用() throws SQLException {
        String dbPath = tempDir.resolve("idem.db").toString();
        ConnectionProvider cp = new ConnectionProvider(dbPath);
        new SqliteSchemaInitializer(cp).initialize();

        insertApproval(cp, "a1", "p1", 1, 2, ChapterApproval.contentHash("t", "c"), "rs", "dup-key", null);
        // 同项目同幂等键：唯一约束必须拒绝第二次写入
        assertThrows(SQLException.class,
                () -> insertApproval(cp, "a2", "p1", 2, 1, ChapterApproval.contentHash("t2", "c2"),
                        "rs2", "dup-key", null),
                "同项目相同幂等键必须违反唯一约束");
        // 相同幂等键属于另一个项目：允许
        assertDoesNotThrow(() -> insertApproval(cp, "a3", "p2", 1, 2,
                ChapterApproval.contentHash("t", "c"), "rs", "dup-key", null));
        try (Connection conn = cp.getConnection(); Statement st = conn.createStatement()) {
            assertEquals(2, countRows(st, "chapter_approvals"), "跨项目同键合法，共两行");
        }
    }

    // --- helpers ---

    private void insertApproval(ConnectionProvider cp, String id, String projectId, int chapterIndex,
                                int version, String contentHash, String reviewSetHash,
                                String idempotencyKey, String reason) throws SQLException {
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO chapter_approvals (id, project_id, chapter_index, chapter_version, " +
                             "content_hash, review_set_hash, idempotency_key, reason, created_at) " +
                             "VALUES (?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, id);
            ps.setString(2, projectId);
            ps.setInt(3, chapterIndex);
            ps.setInt(4, version);
            ps.setString(5, contentHash);
            ps.setString(6, reviewSetHash);
            ps.setString(7, idempotencyKey);
            ps.setString(8, reason);
            ps.setString(9, Instant.now().toString());
            ps.executeUpdate();
        }
    }

    private int countRows(Statement st, String table) throws SQLException {
        ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table);
        rs.next();
        return rs.getInt(1);
    }

    private String scalar(Statement st, String sql) throws SQLException {
        ResultSet rs = st.executeQuery(sql);
        rs.next();
        return rs.getString(1);
    }
}
