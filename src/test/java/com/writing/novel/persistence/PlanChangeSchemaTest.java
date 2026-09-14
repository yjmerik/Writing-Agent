package com.writing.novel.persistence;

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
 * p38-01：计划变更凭证与承诺处置模型。
 * 迁移幂等；预览内容持久不可被改写；承诺用 ID 不靠描述；跨项目不能引用。
 */
class PlanChangeSchemaTest {

    @TempDir
    Path tempDir;

    @Test
    void 重复迁移幂等且表结构就绪() throws SQLException {
        String dbPath = tempDir.resolve("legacy.db").toString();
        try (Connection conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE projects (id TEXT PRIMARY KEY, title TEXT)");
            st.execute("INSERT INTO projects VALUES ('p1','旧')");
        }
        ConnectionProvider cp = new ConnectionProvider(dbPath);
        new SqliteSchemaInitializer(cp).initialize();
        insertPlanChange(cp, "pc1", "p1", "hash-1", 20, 3, "[{\"promiseId\":\"f1\"}]", "[21,22]");
        new SqliteSchemaInitializer(cp).initialize();
        try (Connection conn = cp.getConnection(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM plan_changes")) {
            rs.next();
            assertEquals(1, rs.getInt(1), "重复迁移不丢行");
        }
    }

    @Test
    void 预览内容持久且承诺用ID() throws SQLException {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("pc.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        insertPlanChange(cp, "pc2", "p2", "hash-2", 20, 5,
                "[{\"promiseId\":\"f-abc\",\"description\":\"旧发夹回收\",\"expectedResolveChapter\":22}]",
                "[21,22,23]");
        try (Connection conn = cp.getConnection(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM plan_changes WHERE preview_id='pc2'")) {
            assertTrue(rs.next());
            assertEquals("hash-2", rs.getString("source_plan_hash"), "预览内容持久");
            assertEquals(20, rs.getInt("target_chapters"));
            assertEquals("[21,22,23]", rs.getString("pruned_chapters_json"));
        }
        // PromiseDecision 用 promiseId 而非描述
        try (Connection conn = cp.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO promise_decisions (id, plan_change_id, promise_id, action, reason, created_at) " +
                        "VALUES ('pd1','pc2','f-abc','MIGRATE',NULL,'2026-01-01T00:00:00Z')")) {
            ps.executeUpdate();
        }
        try (Connection conn = cp.getConnection(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT promise_id FROM promise_decisions WHERE id='pd1'")) {
            assertTrue(rs.next());
            assertEquals("f-abc", rs.getString("promise_id"), "承诺用 ID 匹配");
        }
    }

    @Test
    void 跨项目planChangeId不冲突() throws SQLException {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("cross.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        insertPlanChange(cp, "pc-a", "p-a", "h", 20, 0, "[]", "[]");
        assertDoesNotThrow(() -> insertPlanChange(cp, "pc-b", "p-b", "h", 20, 0, "[]", "[]"));
        // 同项目同 previewId → UNIQUE 拒绝
        assertThrows(SQLException.class, () -> insertPlanChange(cp, "pc-a", "p-a", "h", 20, 0, "[]", "[]"),
                "同项目重复 previewId 拒绝");
        // 同 previewId 不同项目 → 允许
        assertDoesNotThrow(() -> insertPlanChange(cp, "pc-a", "p-b", "h", 20, 0, "[]", "[]"));
    }

    private void insertPlanChange(ConnectionProvider cp, String previewId, String projectId,
                                  String hash, int target, int boundary,
                                  String promisesJson, String prunedJson) throws SQLException {
        try (Connection conn = cp.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO plan_changes (preview_id, project_id, source_plan_hash, target_chapters, " +
                        "written_boundary, affected_promises_json, pruned_chapters_json, status, created_at) " +
                        "VALUES (?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, previewId);
            ps.setString(2, projectId);
            ps.setString(3, hash);
            ps.setInt(4, target);
            ps.setInt(5, boundary);
            ps.setString(6, promisesJson);
            ps.setString(7, prunedJson);
            ps.setString(8, "PENDING");
            ps.setString(9, Instant.now().toString());
            ps.executeUpdate();
        }
    }
}
