package com.writing.novel.persistence.sqlite;

import com.writing.novel.domain.VolumePacingDirective;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Optional;

/** NCS-200: 卷级节奏指令持久化（volume_pacing_directives 表） */
public class VolumePacingDirectiveRepository {

    private final ConnectionProvider cp;

    public VolumePacingDirectiveRepository(ConnectionProvider cp) {
        this.cp = cp;
    }

    public void save(VolumePacingDirective d) {
        try (Connection conn = cp.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO volume_pacing_directives (project_id,volume_index,from_chapter,to_chapter," +
                        "metrics_json,reader_avg,review_count,trend_warning,pacing_text,directive_text,created_at) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, d.getProjectId());
            ps.setInt(2, d.getVolumeIndex());
            ps.setInt(3, d.getFromChapter());
            ps.setInt(4, d.getToChapter());
            ps.setString(5, d.getMetricsJson());
            ps.setDouble(6, d.getReaderAvg());
            ps.setInt(7, d.getReviewCount());
            ps.setString(8, d.getTrendWarning());
            ps.setString(9, d.getPacingText());
            ps.setString(10, d.getDirectiveText());
            ps.setString(11, d.getCreatedAt().toString());
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    /** 下一卷注入：volumeIndex 之前最近一条指令（无则空） */
    public Optional<VolumePacingDirective> findLatestBefore(String projectId, int volumeIndex) {
        return query("SELECT * FROM volume_pacing_directives WHERE project_id=? AND volume_index<? " +
                "ORDER BY volume_index DESC, id DESC LIMIT 1", projectId, volumeIndex);
    }

    public Optional<VolumePacingDirective> findLatest(String projectId) {
        return query("SELECT * FROM volume_pacing_directives WHERE project_id=? AND volume_index>=? " +
                "ORDER BY volume_index DESC, id DESC LIMIT 1", projectId, 0);
    }

    private Optional<VolumePacingDirective> query(String sql, String projectId, int volumeIndex) {
        try (Connection conn = cp.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId);
            ps.setInt(2, volumeIndex);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return Optional.empty();
            VolumePacingDirective d = new VolumePacingDirective();
            d.setId(rs.getLong("id"));
            d.setProjectId(rs.getString("project_id"));
            d.setVolumeIndex(rs.getInt("volume_index"));
            d.setFromChapter(rs.getInt("from_chapter"));
            d.setToChapter(rs.getInt("to_chapter"));
            d.setMetricsJson(rs.getString("metrics_json"));
            d.setReaderAvg(rs.getDouble("reader_avg"));
            d.setReviewCount(rs.getInt("review_count"));
            d.setTrendWarning(rs.getString("trend_warning"));
            d.setPacingText(rs.getString("pacing_text"));
            d.setDirectiveText(rs.getString("directive_text"));
            d.setCreatedAt(Instant.parse(rs.getString("created_at")));
            return Optional.of(d);
        } catch (SQLException e) { throw new RuntimeException(e); }
    }
}
