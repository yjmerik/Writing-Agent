package com.writing.novel.persistence.sqlite;

import com.writing.novel.agent.CheckResultEnvelope;
import com.writing.novel.domain.CharacterDecision;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * NCS-012: 决策记录持久化。绑定 (project, chapter, version, character) 幂等；
 * 提取执行状态复用 NCS-007 的 check_results 信封（checker=character-decision）。
 */
public class CharacterDecisionRepository {

    private final ConnectionProvider cp;
    private final CheckResultRepository checkResults;

    public CharacterDecisionRepository(ConnectionProvider cp) {
        this.cp = cp;
        this.checkResults = new CheckResultRepository(cp);
    }

    public void save(CharacterDecision d) {
        String sql = "INSERT INTO character_decisions (id, project_id, character_name, chapter_index, " +
                "chapter_version, trigger_event, knew_then, options_json, chosen_action, cost, " +
                "conflict_with_commitment, evidence_quote, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?) " +
                "ON CONFLICT(id) DO UPDATE SET trigger_event=excluded.trigger_event, knew_then=excluded.knew_then, " +
                "options_json=excluded.options_json, chosen_action=excluded.chosen_action, cost=excluded.cost, " +
                "conflict_with_commitment=excluded.conflict_with_commitment, evidence_quote=excluded.evidence_quote";
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, d.getId());
            ps.setString(2, d.getProjectId());
            ps.setString(3, d.getCharacterName());
            ps.setInt(4, d.getChapterIndex());
            ps.setInt(5, d.getChapterVersion());
            ps.setString(6, d.getTriggerEvent());
            ps.setString(7, d.getKnewThen());
            ps.setString(8, joinOptions(d.getOptions()));
            ps.setString(9, d.getChosenAction());
            ps.setString(10, d.getCost());
            ps.setString(11, d.getConflictWithCommitment());
            ps.setString(12, d.getEvidenceQuote());
            ps.setString(13, d.getCreatedAt() != null ? d.getCreatedAt().toString() : Instant.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** 同章同稿同人物是否已有决策（幂等判断） */
    public boolean existsForVersion(String projectId, int chapterIndex, int chapterVersion, String characterName) {
        String sql = "SELECT COUNT(*) FROM character_decisions WHERE project_id=? AND chapter_index=? " +
                "AND chapter_version=? AND character_name=?";
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId);
            ps.setInt(2, chapterIndex);
            ps.setInt(3, chapterVersion);
            ps.setString(4, characterName);
            ResultSet rs = ps.executeQuery();
            return rs.next() && rs.getInt(1) > 0;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<CharacterDecision> findByProject(String projectId) {
        return query("SELECT * FROM character_decisions WHERE project_id=? ORDER BY chapter_index, created_at",
                ps -> ps.setString(1, projectId));
    }

    public List<CharacterDecision> findByCharacter(String projectId, String characterName) {
        return query("SELECT * FROM character_decisions WHERE project_id=? " +
                        "AND character_name=? ORDER BY chapter_index, created_at",
                ps -> {
                    ps.setString(1, projectId);
                    ps.setString(2, characterName);
                });
    }

    /** 提取执行记录（复用 check_results 信封，checker=character-decision） */
    public List<CheckResultRepository.LatestCheck> findExtractions(String projectId) {
        return checkResults.latestStatuses(projectId).stream()
                .filter(c -> "character-decision".equals(c.checker()))
                .toList();
    }

    public void recordExtraction(String projectId, int chapterIndex, CheckResultEnvelope.Status status, String reason) {
        checkResults.save(projectId, chapterIndex, "character-decision", status, reason);
    }

    private List<CharacterDecision> query(String sql, com.writing.novel.persistence.sqlite.CharacterDecisionRepository.SQLConsumer<PreparedStatement> binder) {
        List<CharacterDecision> list = new ArrayList<>();
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            binder.accept(ps);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(map(rs));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return list;
    }

    @FunctionalInterface
    interface SQLConsumer<T> {
        void accept(T t) throws SQLException;
    }

    private CharacterDecision map(ResultSet rs) throws SQLException {
        CharacterDecision d = new CharacterDecision();
        d.setId(rs.getString("id"));
        d.setProjectId(rs.getString("project_id"));
        d.setCharacterName(rs.getString("character_name"));
        d.setChapterIndex(rs.getInt("chapter_index"));
        d.setChapterVersion(rs.getInt("chapter_version"));
        d.setTriggerEvent(rs.getString("trigger_event"));
        d.setKnewThen(rs.getString("knew_then"));
        d.setOptions(splitOptions(rs.getString("options_json")));
        d.setChosenAction(rs.getString("chosen_action"));
        d.setCost(rs.getString("cost"));
        d.setConflictWithCommitment(rs.getString("conflict_with_commitment"));
        d.setEvidenceQuote(rs.getString("evidence_quote"));
        String created = rs.getString("created_at");
        d.setCreatedAt(created == null ? Instant.now() : Instant.parse(created));
        return d;
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static String joinOptions(List<String> options) {
        try {
            return MAPPER.writeValueAsString(options != null ? options : List.of());
        } catch (Exception e) {
            return "[]";
        }
    }

    private static List<String> splitOptions(String json) {
        if (json == null || json.isBlank() || "[]".equals(json)) return new ArrayList<>();
        try {
            return MAPPER.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }
}
