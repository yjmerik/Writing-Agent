package com.writing.novel.persistence.sqlite;

import com.writing.novel.memory.RecallFeedbackService;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * NCS-114: 召回使用反馈统计持久化——每 (project, chapter, kind) 一行追加，
 * 聚合读累计 injected/hit。
 */
@Component
public class RecallFeedbackRepository implements RecallFeedbackService.Store {

    private final ConnectionProvider cp;

    public RecallFeedbackRepository(ConnectionProvider cp) {
        this.cp = cp;
    }

    @Override
    public void save(String projectId, int chapterIndex, String kind, int injected, int hit) {
        String sql = "INSERT INTO recall_feedback (project_id, chapter_index, kind, injected, hit) " +
                "VALUES (?,?,?,?,?)";
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId);
            ps.setInt(2, chapterIndex);
            ps.setString(3, kind);
            ps.setInt(4, injected);
            ps.setInt(5, hit);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    @Override
    public long[] aggregate(String projectId, String kind) {
        String sql = "SELECT COALESCE(SUM(injected),0) AS inj, COALESCE(SUM(hit),0) AS hit " +
                "FROM recall_feedback WHERE project_id=? AND kind=?";
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId);
            ps.setString(2, kind);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return new long[]{0, 0};
            return new long[]{rs.getLong("inj"), rs.getLong("hit")};
        } catch (SQLException e) { throw new RuntimeException(e); }
    }
}
