package com.writing.novel.persistence.sqlite;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * SQLite 连接提供者。
 * p13-mc-p1-03: 启用 WAL 模式 + busy_timeout + 外键约束，提升并发读写可靠性。
 */
public class ConnectionProvider {
    private final String dbPath;
    private volatile boolean pragmasInitialized = false;

    public ConnectionProvider(String dbPath) {
        this.dbPath = dbPath;
    }

    public Connection getConnection() throws SQLException {
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        // mc-p1-03: 每个连接设置 PRAGMA（轻量，确保一致性）
        if (!pragmasInitialized) {
            synchronized (this) {
                if (!pragmasInitialized) {
                    try (Statement st = conn.createStatement()) {
                        st.execute("PRAGMA journal_mode=WAL");
                        st.execute("PRAGMA busy_timeout=5000");
                        st.execute("PRAGMA foreign_keys=ON");
                        st.execute("PRAGMA synchronous=NORMAL");
                    } catch (SQLException ignored) {
                        // WAL 设置失败不阻断（旧库可能已锁定）
                    }
                    pragmasInitialized = true;
                }
            }
        }
        return conn;
    }

    public String getDbPath() { return dbPath; }
}
