package com.writing.novel.persistence.sqlite;

import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * NCS-133: 长任务 SQLite 持久化——job 表（RUNNING/DONE/FAILED/CANCELLED），
 * 重启后终态可查；进度以 phase 字符串 + 百分比落库（阶段级上报）。
 * 过期清理：终态且 finished_at 早于保留窗的行删除。
 */
@Component
public class LongTaskJobRepository {

    private static final long DEFAULT_RETENTION_HOURS = 72;

    private final ConnectionProvider cp;

    public LongTaskJobRepository(ConnectionProvider cp) {
        this.cp = cp;
    }

    public void save(String jobId, String projectId, String kind, String label, String status,
                     String resultJson, String error, String phase, Integer percent,
                     Instant startedAt, Instant finishedAt) {
        String sql = "INSERT INTO long_task_job (job_id, project_id, kind, label, status, result_json, "
                + "error, phase, percent, started_at, finished_at, updated_at) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(job_id) DO UPDATE SET "
                + "status=excluded.status, result_json=excluded.result_json, error=excluded.error, "
                + "phase=excluded.phase, percent=excluded.percent, finished_at=excluded.finished_at, "
                + "updated_at=excluded.updated_at";
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, jobId);
            ps.setString(2, projectId);
            ps.setString(3, kind);
            ps.setString(4, label);
            ps.setString(5, status);
            ps.setString(6, resultJson);
            ps.setString(7, error);
            ps.setString(8, phase);
            Object percentObj = percent;
            ps.setObject(9, percentObj);
            ps.setString(10, startedAt == null ? null : startedAt.toString());
            ps.setString(11, finishedAt == null ? null : finishedAt.toString());
            ps.setString(12, Instant.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public Optional<Map<String, Object>> find(String projectId, String jobId) {
        String sql = "SELECT * FROM long_task_job WHERE job_id=? AND project_id=?";
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, jobId);
            ps.setString(2, projectId);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return Optional.empty();
            return Optional.of(mapRow(rs));
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    /** 清理：终态且早于保留窗的行；返回删除数。重启时调用。 */
    public int purgeExpired(int retentionHours) {
        String cutoff = Instant.now().minusSeconds(Math.max(1, retentionHours) * 3600L).toString();
        String sql = "DELETE FROM long_task_job WHERE status IN ('DONE','FAILED','CANCELLED') "
                + "AND finished_at IS NOT NULL AND finished_at < ?";
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, cutoff);
            return ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    /** 重启恢复：把 RUNNING 行标记 CANCELLED（进程已死，工作权已不存在） */
    public int cancelOrphans() {
        String sql = "UPDATE long_task_job SET status='CANCELLED', "
                + "error='进程重启，任务中断（请重新发起）', finished_at=?, updated_at=? WHERE status='RUNNING'";
        String now = Instant.now().toString();
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, now);
            ps.setString(2, now);
            return ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    private static Map<String, Object> mapRow(ResultSet rs) throws SQLException {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("jobId", rs.getString("job_id"));
        m.put("projectId", rs.getString("project_id"));
        m.put("kind", rs.getString("kind"));
        m.put("label", rs.getString("label"));
        m.put("status", rs.getString("status"));
        m.put("resultJson", rs.getString("result_json"));
        m.put("error", rs.getString("error"));
        m.put("phase", rs.getString("phase"));
        int percent = rs.getInt("percent");
        m.put("percent", rs.wasNull() ? null : percent);
        m.put("startedAt", rs.getString("started_at"));
        m.put("finishedAt", rs.getString("finished_at"));
        return m;
    }
}
