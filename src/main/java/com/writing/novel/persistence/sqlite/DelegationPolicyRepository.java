package com.writing.novel.persistence.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.Map;

/** NCS-025: 委托策略持久化（项目单行 JSON）。 */
public class DelegationPolicyRepository {

    private final ConnectionProvider cp;

    public DelegationPolicyRepository(ConnectionProvider cp) {
        this.cp = cp;
    }

    public Map<String, String> findRules(String projectId) {
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT rules_json FROM delegation_policies WHERE project_id=?")) {
            ps.setString(1, projectId);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return new LinkedHashMap<>();
            String json = rs.getString("rules_json");
            if (json == null || json.isBlank()) return new LinkedHashMap<>();
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            Map<String, String> out = new LinkedHashMap<>();
            node.fields().forEachRemaining(entry ->
                    out.put(entry.getKey(), entry.getValue().asText("CONFIRM")));
            return out;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void saveRules(String projectId, Map<String, String> rules) {
        String sql = "INSERT INTO delegation_policies (project_id, rules_json, updated_at) VALUES (?,?,?) " +
                "ON CONFLICT(project_id) DO UPDATE SET rules_json=excluded.rules_json, updated_at=excluded.updated_at";
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId);
            ps.setString(2, new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(rules));
            ps.setString(3, java.time.Instant.now().toString());
            ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
