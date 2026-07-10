package com.writing.novel.persistence.sqlite;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** SQLite 连接提供者。单连接 + 线程局部，适合本地单进程 MVP。 */
public class ConnectionProvider {
    private final String dbPath;

    public ConnectionProvider(String dbPath) {
        this.dbPath = dbPath;
    }

    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dbPath);
    }

    public String getDbPath() { return dbPath; }
}
