package com.writing.novel.persistence.sqlite;

import com.writing.novel.domain.RevisionRequest;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** NCS-013: 统一修订请求持久化。 */
public class RevisionRequestRepository {

    private final ConnectionProvider cp;

    public RevisionRequestRepository(ConnectionProvider cp) {
        this.cp = cp;
    }

    public void save(RevisionRequest r) {
        String sql = "INSERT INTO revision_requests (id, project_id, origin, intent, target_anchors, " +
                "base_versions, protect_anchors, status, scope_decision, acceptance, cancel_reason, " +
                "created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?) " +
                "ON CONFLICT(id) DO UPDATE SET status=excluded.status, scope_decision=excluded.scope_decision, " +
                "acceptance=excluded.acceptance, cancel_reason=excluded.cancel_reason, " +
                "updated_at=excluded.updated_at";
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, r.getId());
            ps.setString(2, r.getProjectId());
            ps.setString(3, r.getOrigin().name());
            ps.setString(4, r.getIntent());
            ps.setString(5, r.getTargetAnchorsJson());
            ps.setString(6, r.getBaseVersionsJson());
            ps.setString(7, r.getProtectAnchorsJson());
            ps.setString(8, r.getStatus().name());
            ps.setString(9, r.getScopeDecision());
            ps.setString(10, r.getAcceptance());
            ps.setString(11, r.getCancelReason());
            ps.setString(12, r.getCreatedAt().toString());
            ps.setString(13, r.getUpdatedAt().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public Optional<RevisionRequest> find(String projectId, String id) {
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM revision_requests WHERE project_id=? AND id=?")) {
            ps.setString(1, projectId);
            ps.setString(2, id);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? Optional.of(map(rs)) : Optional.empty();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<RevisionRequest> findByProject(String projectId) {
        List<RevisionRequest> list = new ArrayList<>();
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM revision_requests WHERE project_id=? ORDER BY created_at DESC")) {
            ps.setString(1, projectId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(map(rs));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return list;
    }

    private RevisionRequest map(ResultSet rs) throws SQLException {
        RevisionRequest r = new RevisionRequest();
        r.setId(rs.getString("id"));
        r.setProjectId(rs.getString("project_id"));
        r.setOrigin(RevisionRequest.Origin.valueOf(rs.getString("origin")));
        r.setIntent(orEmpty(rs.getString("intent")));
        r.setTargetAnchorsJson(orEmpty(rs.getString("target_anchors")));
        r.setBaseVersionsJson(orEmpty(rs.getString("base_versions")));
        r.setProtectAnchorsJson(orEmpty(rs.getString("protect_anchors")));
        r.setStatus(RevisionRequest.Status.valueOf(rs.getString("status")));
        r.setScopeDecision(rs.getString("scope_decision"));
        r.setAcceptance(rs.getString("acceptance"));
        r.setCancelReason(rs.getString("cancel_reason"));
        String created = rs.getString("created_at");
        r.setCreatedAt(created == null ? Instant.now() : Instant.parse(created));
        String updated = rs.getString("updated_at");
        r.setUpdatedAt(updated == null ? Instant.now() : Instant.parse(updated));
        return r;
    }

    private static String orEmpty(String v) {
        return v == null ? "" : v;
    }
}
