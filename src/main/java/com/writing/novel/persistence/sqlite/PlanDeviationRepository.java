package com.writing.novel.persistence.sqlite;

import com.writing.novel.domain.PlanDeviation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** NCS-019: 计划偏离持久化——每章保留最新一条偏离记录。 */
public class PlanDeviationRepository {

    private final ConnectionProvider cp;

    public PlanDeviationRepository(ConnectionProvider cp) {
        this.cp = cp;
    }

    public void save(PlanDeviation d) {
        String sql = "INSERT INTO plan_deviations (id, project_id, chapter_index, chapter_version, " +
                "deviation_json, decision, decided_at, created_at) VALUES (?,?,?,?,?,?,?,?) " +
                "ON CONFLICT(id) DO UPDATE SET decision=excluded.decision, decided_at=excluded.decided_at, " +
                "deviation_json=excluded.deviation_json, chapter_version=excluded.chapter_version";
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, d.getId());
            ps.setString(2, d.getProjectId());
            ps.setInt(3, d.getChapterIndex());
            ps.setInt(4, d.getChapterVersion());
            ps.setString(5, d.getDeviationJson());
            ps.setString(6, d.getDecision());
            ps.setString(7, d.getDecidedAt() == null ? null : d.getDecidedAt().toString());
            ps.setString(8, d.getCreatedAt().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public Optional<PlanDeviation> findLatest(String projectId, int chapterIndex) {
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM plan_deviations WHERE project_id=? AND chapter_index=? " +
                     "ORDER BY created_at DESC LIMIT 1")) {
            ps.setString(1, projectId);
            ps.setInt(2, chapterIndex);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? Optional.of(map(rs)) : Optional.empty();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<PlanDeviation> findByProject(String projectId) {
        List<PlanDeviation> list = new ArrayList<>();
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM plan_deviations WHERE project_id=? ORDER BY chapter_index")) {
            ps.setString(1, projectId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(map(rs));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return list;
    }

    private PlanDeviation map(ResultSet rs) throws SQLException {
        PlanDeviation d = new PlanDeviation();
        d.setId(rs.getString("id"));
        d.setProjectId(rs.getString("project_id"));
        d.setChapterIndex(rs.getInt("chapter_index"));
        d.setChapterVersion(rs.getInt("chapter_version"));
        d.setDeviationJson(rs.getString("deviation_json"));
        d.setDecision(rs.getString("decision"));
        String decided = rs.getString("decided_at");
        d.setDecidedAt(decided == null ? null : Instant.parse(decided));
        String created = rs.getString("created_at");
        d.setCreatedAt(created == null ? Instant.now() : Instant.parse(created));
        return d;
    }
}
