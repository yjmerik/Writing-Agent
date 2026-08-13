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
        try (Statement st = conn.createStatement()) {
            // A2: journal_mode=WAL 是持久化属性（存 DB header），只需设一次
            if (!pragmasInitialized) {
                st.execute("PRAGMA journal_mode=WAL");
                pragmasInitialized = true;
            }
            // 以下 PRAGMA 是 per-connection 属性，每个连接都必须设置
            st.execute("PRAGMA busy_timeout=5000");
            st.execute("PRAGMA foreign_keys=ON");
            st.execute("PRAGMA synchronous=NORMAL");
        } catch (SQLException ignored) {
            // PRAGMA 设置失败不阻断（旧库可能已锁定）
        }
        return conn;
    }

    public String getDbPath() { return dbPath; }
}
