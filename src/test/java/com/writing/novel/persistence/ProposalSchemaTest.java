package com.writing.novel.persistence;

import com.writing.novel.domain.RevisionProposal;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * p37-01：持久修订候选 schema 契约。
 * 重复迁移无损；嵌套 JSON/换行能恢复；跨项目 requestKey 隔离；APPLIED 不回到 READY（状态守卫）。
 */
class ProposalSchemaTest {

    @TempDir
    Path tempDir;

    private RevisionProposal insertProposal(ConnectionProvider cp, String id, String projectId,
                                            String requestKey, RevisionProposal.Status status,
                                            String beforeJson, String afterJson, String instructions)
            throws SQLException {
        RevisionProposal proposal = new RevisionProposal(id, projectId,
                RevisionProposal.TargetType.CHAPTER, "chapter-row-" + id, 1, null,
                2, RevisionProposal.chapterSourceHash("标题", "正文"), instructions,
                beforeJson, afterJson, null, status, requestKey, null,
                Instant.parse("2026-09-14T00:00:00Z"), Instant.parse("2026-09-14T00:00:00Z"));
        try (Connection conn = cp.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO revision_proposals (id, project_id, target_type, target_id, chapter_index, " +
                        "passage_index, base_version, source_hash, instructions, before_json, after_json, " +
                        "summary, status, request_key, result_json, created_at, updated_at) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, proposal.getId());
            ps.setString(2, proposal.getProjectId());
            ps.setString(3, proposal.getTargetType().name());
            ps.setString(4, proposal.getTargetId());
            ps.setInt(5, proposal.getChapterIndex());
            ps.setNull(6, java.sql.Types.NULL);
            ps.setInt(7, proposal.getBaseVersion());
            ps.setString(8, proposal.getSourceHash());
            ps.setString(9, proposal.getInstructions());
            ps.setString(10, proposal.getBeforeJson());
            ps.setString(11, proposal.getAfterJson());
            ps.setString(12, proposal.getSummary());
            ps.setString(13, proposal.getStatus().name());
            ps.setString(14, proposal.getRequestKey());
            ps.setString(15, proposal.getResultJson());
            ps.setString(16, proposal.getCreatedAt().toString());
            ps.setString(17, proposal.getUpdatedAt().toString());
            ps.executeUpdate();
        }
        return proposal;
    }

    private RevisionProposal readProposal(ConnectionProvider cp, String id) throws SQLException {
        try (Connection conn = cp.getConnection(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM revision_proposals WHERE id='" + id + "'")) {
            assertTrue(rs.next(), "候选行应存在: " + id);
            return new RevisionProposal(rs.getString("id"), rs.getString("project_id"),
                    RevisionProposal.TargetType.valueOf(rs.getString("target_type")),
                    rs.getString("target_id"),
                    rs.getObject("chapter_index") == null ? null : rs.getInt("chapter_index"),
                    rs.getObject("passage_index") == null ? null : rs.getInt("passage_index"),
                    rs.getInt("base_version"), rs.getString("source_hash"),
                    rs.getString("instructions"), rs.getString("before_json"),
                    rs.getString("after_json"), rs.getString("summary"),
                    RevisionProposal.Status.valueOf(rs.getString("status")),
                    rs.getString("request_key"), rs.getString("result_json"),
                    Instant.parse(rs.getString("created_at")),
                    Instant.parse(rs.getString("updated_at")));
        }
    }

    @Test
    void 重复迁移无损且表结构就绪() throws SQLException {
        String dbPath = tempDir.resolve("legacy.db").toString();
        try (Connection conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             Statement st = conn.createStatement()) {
            // 旧库：只有项目表（p37-01 之前就存在的最小结构）
            st.execute("CREATE TABLE projects (id TEXT PRIMARY KEY, title TEXT)");
            st.execute("INSERT INTO projects (id, title) VALUES ('p-old', '旧项目')");
        }
        ConnectionProvider cp = new ConnectionProvider(dbPath);
        new SqliteSchemaInitializer(cp).initialize();
        insertProposal(cp, "prop-1", "p-old", "key-1", RevisionProposal.Status.READY,
                "{\"before\":1}", "{\"after\":2}", "指令");
        // 连续第二次初始化：候选行与旧数据均保留
        new SqliteSchemaInitializer(cp).initialize();
        try (Connection conn = cp.getConnection(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM revision_proposals")) {
            rs.next();
            assertEquals(1, rs.getInt(1), "重复迁移不得丢候选行");
        }
        try (Connection conn = cp.getConnection(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM projects")) {
            rs.next();
            assertEquals(1, rs.getInt(1), "重复迁移不得丢旧数据");
        }
    }

    @Test
    void 嵌套JSON与换行能完整恢复() throws SQLException {
        ConnectionProvider cp = new ConnectionProvider(
                tempDir.resolve("json.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        String beforeJson = "{\"chapter\":{\"title\":\"第\n一章\",\"lines\":[\"段落一\",\"段落\n二\"]},\"nested\":{\"deep\":{\"v\":null}}}";
        String afterJson = "{\"chapter\":{\"title\":\"第\n一章（改）\",\"lines\":[\"新段落\"]},\"quote\":\"含\\\"转义\\\"与\t制表\"}";
        String instructions = "多行指令：\n1. 增加对话\n2. 减少铺陈";
        insertProposal(cp, "prop-json", "p-json", "key-json", RevisionProposal.Status.READY,
                beforeJson, afterJson, instructions);

        RevisionProposal restored = readProposal(cp, "prop-json");
        assertEquals(beforeJson, restored.getBeforeJson(), "嵌套 JSON/换行 before 原样恢复");
        assertEquals(afterJson, restored.getAfterJson(), "转义/制表 after 原样恢复");
        assertEquals(instructions, restored.getInstructions(), "多行指令原样恢复");
    }

    @Test
    void 跨项目相同requestKey互不冲突() throws SQLException {
        ConnectionProvider cp = new ConnectionProvider(
                tempDir.resolve("idem.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        insertProposal(cp, "prop-a", "p-a", "same-key", RevisionProposal.Status.READY,
                "{}", "{}", "A 项目");
        // 同项目同 requestKey：唯一约束拒绝
        assertThrows(SQLException.class, () -> insertProposal(cp, "prop-a2", "p-a", "same-key",
                RevisionProposal.Status.READY, "{}", "{}", "重复键"),
                "同项目相同 requestKey 必须违反唯一约束");
        // 跨项目同 requestKey：允许
        assertDoesNotThrow(() -> insertProposal(cp, "prop-b", "p-b", "same-key",
                RevisionProposal.Status.READY, "{}", "{}", "B 项目"));
    }

    @Test
    void APPLIED是终态不得回到READY且取消留记录() {
        RevisionProposal proposal = new RevisionProposal("prop-guard", "p-guard",
                RevisionProposal.TargetType.CHAPTER, "chapter-1", 1, null,
                2, RevisionProposal.chapterSourceHash("t", "c"), "指令",
                "{}", "{}", null, RevisionProposal.Status.READY, "key-guard", null,
                Instant.now(), Instant.now());

        proposal.markApplied("{\"newVersion\":3}");
        assertEquals(RevisionProposal.Status.APPLIED, proposal.getStatus());
        assertEquals("{\"newVersion\":3}", proposal.getResultJson());
        IllegalStateException e1 = assertThrows(IllegalStateException.class, proposal::markReady,
                "APPLIED 不回到 READY");
        assertTrue(e1.getMessage().contains("READY"));
        assertThrows(IllegalStateException.class, proposal::markStale, "APPLIED 不得转 STALE");
        assertThrows(IllegalStateException.class, proposal::cancel, "APPLIED 不得取消");

        // 取消留记录：CANCELLED 是持久状态而非删除
        RevisionProposal cancelled = new RevisionProposal("prop-cancel", "p-guard",
                RevisionProposal.TargetType.CHAPTER, "chapter-2", 2, null,
                1, RevisionProposal.chapterSourceHash("t", "c"), "指令",
                "{}", "{}", null, RevisionProposal.Status.CANCELLED, "key-cancel", null,
                Instant.now(), Instant.now());
        assertEquals(RevisionProposal.Status.CANCELLED, cancelled.getStatus());
        assertNotNull(cancelled.getBeforeJson(), "取消候选保留 before 记录");
    }

    @Test
    void 来源指纹与p36正文身份同构且非章节对象用规范JSON哈希() {
        String chapterHash = RevisionProposal.chapterSourceHash("标题甲", "正文第一段。");
        assertEquals(chapterHash, com.writing.novel.domain.ChapterApproval.contentHash("标题甲", "正文第一段。"),
                "章节候选来源指纹必须与 p36 批准身份同构");

        String jsonHash = RevisionProposal.canonicalJsonSha256("{\"name\":\"张三\"}");
        assertTrue(jsonHash.matches("[0-9a-f]{64}"));
        assertEquals(jsonHash, RevisionProposal.canonicalJsonSha256("{\"name\":\"张三\"}"),
                "规范 JSON 哈希确定性");
        assertNotEquals(jsonHash, RevisionProposal.canonicalJsonSha256("{\"name\":\"张三\" }"),
                "字节级差异必须产生不同指纹");
    }
}
