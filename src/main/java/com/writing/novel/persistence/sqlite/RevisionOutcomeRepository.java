package com.writing.novel.persistence.sqlite;

import com.writing.novel.domain.RevisionOutcome;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** NCS-026: 运行经验持久化。 */
public class RevisionOutcomeRepository {

    private final ConnectionProvider cp;

    public RevisionOutcomeRepository(ConnectionProvider cp) {
        this.cp = cp;
    }

    public void save(RevisionOutcome o) {
        String sql = "INSERT INTO revision_outcomes (id, project_id, change_class, decision, " +
                "instructions, summary, reason, created_at) VALUES (?,?,?,?,?,?,?,?)";
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, o.getId());
            ps.setString(2, o.getProjectId());
            ps.setString(3, o.getChangeClass());
            ps.setString(4, o.getDecision());
            ps.setString(5, o.getInstructions());
            ps.setString(6, o.getSummary());
            ps.setString(7, o.getReason());
            ps.setString(8, o.getCreatedAt().toString());
            ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public List<RevisionOutcome> findRecent(String projectId, String changeClass, int limit) {
        String sql = "SELECT * FROM revision_outcomes WHERE project_id=?" +
                (changeClass == null ? "" : " AND change_class=?") +
                " ORDER BY created_at DESC LIMIT ?";
        List<RevisionOutcome> list = new ArrayList<>();
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            int index = 1;
            ps.setString(index++, projectId);
            if (changeClass != null) ps.setString(index++, changeClass);
            ps.setInt(index, limit);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(map(rs));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return list;
    }

    public boolean delete(String projectId, String id) {
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM revision_outcomes WHERE project_id=? AND id=?")) {
            ps.setString(1, projectId);
            ps.setString(2, id);
            return ps.executeUpdate() > 0;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private RevisionOutcome map(ResultSet rs) throws Exception {
        RevisionOutcome o = new RevisionOutcome();
        o.setId(rs.getString("id"));
        o.setProjectId(rs.getString("project_id"));
        o.setChangeClass(rs.getString("change_class"));
        o.setDecision(rs.getString("decision"));
        o.setInstructions(rs.getString("instructions"));
        o.setSummary(rs.getString("summary"));
        o.setReason(rs.getString("reason"));
        String created = rs.getString("created_at");
        o.setCreatedAt(created == null ? Instant.now() : Instant.parse(created));
        return o;
    }
}
