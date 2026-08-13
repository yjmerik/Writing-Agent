package com.writing.novel.persistence.sqlite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A2: PRAGMA 每连接修复测试。
 * foreign_keys/busy_timeout 是 per-connection 属性，必须每个连接都设置。
 */
class ConnectionProviderPragmaTest {

    @TempDir
    Path tempDir;

    @Test
    void 每个连接都有foreign_keys和busy_timeout() throws Exception {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("pragma.db").toString());

        // 第一个连接
        try (Connection c1 = cp.getConnection()) {
            assertEquals(1, c1.createStatement().executeQuery("PRAGMA foreign_keys").getInt(1));
            assertEquals(5000, c1.createStatement().executeQuery("PRAGMA busy_timeout").getInt(1));
        }

        // 第二个连接（A2 修复前这里会是 0/0）
        try (Connection c2 = cp.getConnection()) {
            assertEquals(1, c2.createStatement().executeQuery("PRAGMA foreign_keys").getInt(1),
                    "第二个连接的 foreign_keys 应为 ON");
            assertEquals(5000, c2.createStatement().executeQuery("PRAGMA busy_timeout").getInt(1),
                    "第二个连接的 busy_timeout 应为 5000");
        }

        // 第三个连接
        try (Connection c3 = cp.getConnection()) {
            assertEquals(1, c3.createStatement().executeQuery("PRAGMA foreign_keys").getInt(1),
                    "第三个连接的 foreign_keys 应为 ON");
        }
    }
}
