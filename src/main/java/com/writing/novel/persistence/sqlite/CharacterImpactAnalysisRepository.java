package com.writing.novel.persistence.sqlite;

import com.writing.novel.domain.CharacterImpactAnalysis;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/** NCS-045: 影响分析持久化（character_impact_analyses 表，追加式） */
public class CharacterImpactAnalysisRepository {

    private final ConnectionProvider cp;

    public CharacterImpactAnalysisRepository(ConnectionProvider cp) {
        this.cp = cp;
    }

    public void save(CharacterImpactAnalysis a) {
        try (Connection conn = cp.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO character_impact_analyses (project_id, character_id, base_version, " +
                        "scope_json, lists_json, created_at) VALUES (?,?,?,?,?,?)")) {
            ps.setString(1, a.getProjectId());
            ps.setString(2, a.getCharacterId());
            ps.setString(3, a.getBaseVersion());
            ps.setString(4, a.getScopeJson());
            ps.setString(5, a.getListsJson());
            ps.setString(6, a.getCreatedAt().toString());
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public Optional<CharacterImpactAnalysis> findLatest(String projectId, String characterId) {
        String sql = "SELECT id, project_id, character_id, base_version, scope_json, lists_json, created_at " +
                "FROM character_impact_analyses WHERE project_id=? AND character_id=? " +
                "ORDER BY id DESC LIMIT 1";
        try (Connection conn = cp.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId);
            ps.setString(2, characterId);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return Optional.empty();
            var a = new CharacterImpactAnalysis(rs.getString("project_id"), rs.getString("character_id"),
                    rs.getString("base_version"), java.util.List.of(), java.util.List.of(),
                    java.util.List.of(), java.util.List.of());
            a.setId(rs.getLong("id"));
            return Optional.of(a);
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    private static final class List {
    }
}
