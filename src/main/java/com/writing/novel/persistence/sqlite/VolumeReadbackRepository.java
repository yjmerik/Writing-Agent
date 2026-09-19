package com.writing.novel.persistence.sqlite;

import com.writing.novel.domain.VolumeReadback;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** NCS-020: 卷末回读持久化——每卷最新一条。 */
public class VolumeReadbackRepository {

    private final ConnectionProvider cp;

    public VolumeReadbackRepository(ConnectionProvider cp) {
        this.cp = cp;
    }

    public void save(VolumeReadback r) {
        String sql = "INSERT INTO volume_readbacks (id, project_id, volume_index, report_json, status, " +
                "acknowledged_at, created_at) VALUES (?,?,?,?,?,?,?) " +
                "ON CONFLICT(id) DO UPDATE SET report_json=excluded.report_json, " +
                "status=excluded.status, acknowledged_at=excluded.acknowledged_at";
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, r.getId());
            ps.setString(2, r.getProjectId());
            ps.setInt(3, r.getVolumeIndex());
            ps.setString(4, r.getReportJson());
            ps.setString(5, r.getStatus());
            ps.setString(6, r.getAcknowledgedAt() == null ? null : r.getAcknowledgedAt().toString());
            ps.setString(7, r.getCreatedAt().toString());
            ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public Optional<VolumeReadback> findLatest(String projectId, int volumeIndex) {
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM volume_readbacks WHERE project_id=? AND volume_index=? " +
                     "ORDER BY created_at DESC LIMIT 1")) {
            ps.setString(1, projectId);
            ps.setInt(2, volumeIndex);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? Optional.of(map(rs)) : Optional.empty();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public List<VolumeReadback> findByProject(String projectId) {
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM volume_readbacks WHERE project_id=? ORDER BY volume_index")) {
            ps.setString(1, projectId);
            var out = new java.util.ArrayList<VolumeReadback>();
            ResultSet rs = ps.executeQuery();
            while (rs.next()) out.add(map(rs));
            return out;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private VolumeReadback map(ResultSet rs) throws Exception {
        VolumeReadback r = new VolumeReadback();
        r.setId(rs.getString("id"));
        r.setProjectId(rs.getString("project_id"));
        r.setVolumeIndex(rs.getInt("volume_index"));
        r.setReportJson(rs.getString("report_json"));
        r.setStatus(rs.getString("status"));
        String ack = rs.getString("acknowledged_at");
        r.setAcknowledgedAt(ack == null ? null : Instant.parse(ack));
        String created = rs.getString("created_at");
        r.setCreatedAt(created == null ? Instant.now() : Instant.parse(created));
        return r;
    }
}
