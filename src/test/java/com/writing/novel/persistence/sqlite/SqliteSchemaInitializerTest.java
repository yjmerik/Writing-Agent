package com.writing.novel.persistence.sqlite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;

class SqliteSchemaInitializerTest {

    @TempDir
    Path tempDir;

    @Test
    void 初始化后所有表存在() throws SQLException {
        String dbPath = tempDir.resolve("test.db").toString();
        ConnectionProvider cp = new ConnectionProvider(dbPath);
        SqliteSchemaInitializer init = new SqliteSchemaInitializer(cp);
        init.initialize();

        try (Connection conn = cp.getConnection();
             Statement st = conn.createStatement()) {
            for (String table : new String[]{
                    "projects", "knowledge_entries", "characters",
                    "outlines", "volumes", "chapter_synopses",
                    "chapters", "summaries", "foreshadowings",
                    "style_profiles", "continuity_issues", "checkpoints"}) {
                ResultSet rs = st.executeQuery(
                        "SELECT name FROM sqlite_master WHERE type='table' AND name='" + table + "'");
                assertTrue(rs.next(), "表应存在: " + table);
            }
        }
    }

    @Test
    void 新库writing_tasks含task_type列默认WRITE_ALL() throws SQLException {
        String dbPath = tempDir.resolve("fresh.db").toString();
        ConnectionProvider cp = new ConnectionProvider(dbPath);
        new SqliteSchemaInitializer(cp).initialize();

        try (Connection conn = cp.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(writing_tasks)")) {
            boolean found = false;
            while (rs.next()) {
                if ("task_type".equals(rs.getString("name"))) {
                    found = true;
                    assertEquals("'WRITE_ALL'", rs.getString("dflt_value"),
                            "task_type 默认值应为 WRITE_ALL");
                }
            }
            assertTrue(found, "新库 writing_tasks 应含 task_type 列");
        }
    }

    @Test
    void 旧库无task_type列时迁移补列且存量行读为WRITE_ALL() throws SQLException {
        String dbPath = tempDir.resolve("legacy.db").toString();
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE writing_tasks (id TEXT PRIMARY KEY, project_id TEXT, status TEXT, " +
                    "current_chapter INTEGER, total_chapters INTEGER, chapters_written INTEGER, " +
                    "total_words INTEGER, error_message TEXT, created_at TEXT, updated_at TEXT)");
            st.execute("INSERT INTO writing_tasks (id, project_id, status, total_chapters, created_at, updated_at) " +
                    "VALUES ('t1','p1','RUNNING',10,'2026-01-01T00:00:00Z','2026-01-01T00:00:00Z')");
        }

        ConnectionProvider cp = new ConnectionProvider(dbPath);
        new SqliteSchemaInitializer(cp).initialize();

        try (Connection conn = cp.getConnection();
             Statement st = conn.createStatement()) {
            ResultSet rs = st.executeQuery("PRAGMA table_info(writing_tasks)");
            boolean found = false;
            while (rs.next()) {
                if ("task_type".equals(rs.getString("name"))) {
                    found = true;
                    assertEquals("'WRITE_ALL'", rs.getString("dflt_value"));
                }
            }
            assertTrue(found, "旧库迁移后应补上 task_type 列");
            rs = st.executeQuery("SELECT task_type FROM writing_tasks WHERE id='t1'");
            assertTrue(rs.next());
            assertEquals("WRITE_ALL", rs.getString("task_type"), "存量行应读为 WRITE_ALL");
        }
    }
}
