package com.writing.novel.persistence.sqlite;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * NCS-190: 叙事策略项目级覆盖持久化——每项目一行 JSON 覆盖表。
 * 表 DDL 由 {@link SqliteSchemaInitializer} 创建（narrative_policy_overrides）。
 */
public class NarrativePolicyRepository {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ConnectionProvider cp;

    public NarrativePolicyRepository(ConnectionProvider cp) {
        this.cp = cp;
    }

    /** 项目覆盖（无覆盖返回空 Map） */
    public Map<String, Object> findOverrides(String projectId) {
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT overrides_json FROM narrative_policy_overrides WHERE project_id=?")) {
            ps.setString(1, projectId);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return new LinkedHashMap<>();
            return MAPPER.readValue(rs.getString("overrides_json"),
                    new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void saveOverrides(String projectId, Map<String, Object> overrides) {
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO narrative_policy_overrides (project_id, overrides_json, updated_at) VALUES (?,?,?) " +
                     "ON CONFLICT(project_id) DO UPDATE SET overrides_json=excluded.overrides_json, " +
                     "updated_at=excluded.updated_at")) {
            ps.setString(1, projectId);
            ps.setString(2, MAPPER.writeValueAsString(overrides));
            ps.setString(3, Instant.now().toString());
            ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void deleteOverrides(String projectId) {
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM narrative_policy_overrides WHERE project_id=?")) {
            ps.setString(1, projectId);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
