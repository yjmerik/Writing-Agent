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
}
