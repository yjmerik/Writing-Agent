package com.writing.novel.persistence.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

/**
 * NCS-001: 项目级写入门禁租约的持久化（SQLite）。
 * epoch 单调递增、跨重启保持；bump 为原子 UPSERT 自增。
 */
public class WriteLeaseRepository {

    private final ConnectionProvider cp;

    public WriteLeaseRepository(ConnectionProvider cp) {
        this.cp = cp;
    }

    /** 当前 epoch；无租约行时为 0（合法初始态，旧任务 leaseEpoch=0 与之匹配） */
    public int currentEpoch(String projectId) {
        String sql = "SELECT epoch FROM project_write_leases WHERE project_id=?";
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getInt("epoch") : 0;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** 递增并返回新 epoch（原子 UPSERT 自增，并发 bump 不丢失） */
    public int bump(String projectId, String reason) {
        String sql = "INSERT INTO project_write_leases (project_id, epoch, reason, updated_at) " +
                "VALUES (?, 1, ?, ?) " +
                "ON CONFLICT(project_id) DO UPDATE SET " +
                "epoch = epoch + 1, reason = excluded.reason, updated_at = excluded.updated_at " +
                "RETURNING epoch";
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId);
            ps.setString(2, reason);
            ps.setString(3, Instant.now().toString());
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) throw new SQLException("bump 未返回新 epoch");
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
