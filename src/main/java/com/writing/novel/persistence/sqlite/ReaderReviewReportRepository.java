package com.writing.novel.persistence.sqlite;

import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NCS-138: 读者视角评审报告持久化——追加式（每次评审一行），只读不参与门禁。
 */
@Component
public class ReaderReviewReportRepository {

    private final ConnectionProvider cp;

    public ReaderReviewReportRepository(ConnectionProvider cp) {
        this.cp = cp;
    }

    public void save(String id, String projectId, int chaptersReviewed, int totalChapters,
                     double coveragePercent, String reportJson) {
        String sql = "INSERT INTO reader_review_report (id, project_id, chapters_reviewed, "
                + "total_chapters, coverage_percent, report_json, created_at) VALUES (?,?,?,?,?,?,?)";
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, projectId);
            ps.setInt(3, chaptersReviewed);
            ps.setInt(4, totalChapters);
            ps.setDouble(5, coveragePercent);
            ps.setString(6, reportJson);
            ps.setString(7, Instant.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    /** 最新一次报告（含 JSON） */
    public Map<String, Object> latest(String projectId) {
        String sql = "SELECT * FROM reader_review_report WHERE project_id=? ORDER BY created_at DESC LIMIT 1";
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? mapRow(rs) : null;
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    /** 历史（不含 JSON，列表视图用） */
    public List<Map<String, Object>> history(String projectId, int limit) {
        String sql = "SELECT id, chapters_reviewed, total_chapters, coverage_percent, created_at "
                + "FROM reader_review_report WHERE project_id=? ORDER BY created_at DESC LIMIT ?";
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId);
            ps.setInt(2, limit);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getString("id"));
                m.put("chaptersReviewed", rs.getInt("chapters_reviewed"));
                m.put("totalChapters", rs.getInt("total_chapters"));
                m.put("coveragePercent", rs.getDouble("coverage_percent"));
                m.put("createdAt", rs.getString("created_at"));
                out.add(m);
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    private static Map<String, Object> mapRow(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getString("id"));
        m.put("projectId", rs.getString("project_id"));
        m.put("chaptersReviewed", rs.getInt("chapters_reviewed"));
        m.put("totalChapters", rs.getInt("total_chapters"));
        m.put("coveragePercent", rs.getDouble("coverage_percent"));
        m.put("reportJson", rs.getString("report_json"));
        m.put("createdAt", rs.getString("created_at"));
        return m;
    }
}
