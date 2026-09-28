package com.writing.novel.persistence.sqlite;

import com.writing.novel.memory.InjectionManifest;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * NCS-096: 上下文注入清单持久化——追加式历史（同章多版本/多次组装各留一行），
 * 查询侧 latest 取最新；payload 为确定性 JSON（不含时间戳）。
 */
@Component
public class InjectionManifestRepository implements InjectionManifest.Store {

    private final ConnectionProvider cp;

    public InjectionManifestRepository(ConnectionProvider cp) {
        this.cp = cp;
    }

    @Override
    public void save(InjectionManifest manifest) {
        String sql = "INSERT INTO context_injection_manifest " +
                "(project_id, chapter_index, chapter_version, payload, created_at) VALUES (?,?,?,?,?)";
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, manifest.getProjectId());
            ps.setInt(2, manifest.getChapterIndex());
            ps.setInt(3, manifest.getChapterVersion());
            ps.setString(4, manifest.toJson());
            ps.setString(5, Instant.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    /** 该章最新一次组装的清单（跨版本取最新） */
    public Optional<InjectionManifest> latest(String projectId, int chapterIndex) {
        return queryOne("SELECT payload FROM context_injection_manifest " +
                "WHERE project_id=? AND chapter_index=? ORDER BY id DESC LIMIT 1", projectId, chapterIndex);
    }

    /** 指定稿件版本的最新清单 */
    public Optional<InjectionManifest> forVersion(String projectId, int chapterIndex, int chapterVersion) {
        return queryOne("SELECT payload FROM context_injection_manifest " +
                "WHERE project_id=? AND chapter_index=? AND chapter_version=? " +
                "ORDER BY id DESC LIMIT 1", projectId, chapterIndex, String.valueOf(chapterVersion));
    }

    /** 该章全部清单（旧→新），诊断/评测用 */
    public List<InjectionManifest> history(String projectId, int chapterIndex) {
        String sql = "SELECT payload FROM context_injection_manifest " +
                "WHERE project_id=? AND chapter_index=? ORDER BY id ASC";
        List<InjectionManifest> result = new ArrayList<>();
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId);
            ps.setInt(2, chapterIndex);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                result.add(InjectionManifest.fromJson(rs.getString("payload")));
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return result;
    }

    private Optional<InjectionManifest> queryOne(String sql, String projectId, int chapterIndex,
                                                 String... extra) {
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId);
            ps.setInt(2, chapterIndex);
            for (int i = 0; i < extra.length; i++) {
                ps.setString(3 + i, extra[i]);
            }
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return Optional.empty();
            return Optional.of(InjectionManifest.fromJson(rs.getString("payload")));
        } catch (SQLException e) { throw new RuntimeException(e); }
    }
}
