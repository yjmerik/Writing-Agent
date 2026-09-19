package com.writing.novel.persistence;

import com.writing.novel.domain.FactEvidence;
import com.writing.novel.persistence.sqlite.StoryFactRepository;
import java.sql.SQLException;
import org.springframework.stereotype.Component;

import java.util.List;

/** p39-02: 事实证据仓储——按事实/章/时点查询。 */
@Component
public class FactEvidenceRepository {

    private final StoryFactRepository storyFactRepository;

    public FactEvidenceRepository(StoryFactRepository storyFactRepository) {
        this.storyFactRepository = storyFactRepository;
    }

    public List<java.util.Map<String, Object>> findByFact(String projectId, String factId) {
        return storyFactRepository.read(conn -> {
            var list = new java.util.ArrayList<java.util.Map<String, Object>>();
            try (var ps = conn.prepareStatement(
                    "SELECT * FROM fact_evidence WHERE project_id=? AND fact_id=? ORDER BY chapter_index")) {
                ps.setString(1, projectId);
                ps.setString(2, factId);
                var rs = ps.executeQuery();
                while (rs.next()) {
                    var m = new java.util.LinkedHashMap<String, Object>();
                    m.put("id", rs.getString("id"));
                    m.put("factId", rs.getString("fact_id"));
                    m.put("chapterIndex", rs.getInt("chapter_index"));
                    m.put("chapterVersion", rs.getInt("chapter_version"));
                    m.put("contentHash", rs.getString("content_hash"));
                    m.put("quote", rs.getString("quote"));
                    m.put("paragraphIndex", rs.getInt("paragraph_index"));
                    m.put("anchorHash", rs.getString("anchor_hash"));
                    m.put("validFromChapter", rs.getInt("valid_from_chapter"));
                    int to = rs.getInt("valid_to_chapter");
                    m.put("validToChapter", rs.wasNull() ? null : to);
                    list.add(m);
                }
            } catch (SQLException e) { throw new RuntimeException(e); }
            return list;
        });
    }

    public List<java.util.Map<String, Object>> findKnownAt(
            String projectId, String factId, int asOfChapter) {
        return findByFact(projectId, factId).stream()
                .filter(m -> (int) m.get("validFromChapter") <= asOfChapter)
                .filter(m -> m.get("validToChapter") == null
                        || (int) m.get("validToChapter") >= asOfChapter)
                .toList();
    }

    /**
     * NCS-010: 某角色截至 upToChapter 已知的全部引文（含所属事实与来源章）。
     * join fact_evidence 取 quote/章号；evidence stale 时仍返回（信念语义由调用方判定）。
     */
    public List<java.util.Map<String, Object>> findLearnedByCharacter(
            String projectId, String characterId, int upToChapter) {
        return storyFactRepository.read(conn -> {
            var list = new java.util.ArrayList<java.util.Map<String, Object>>();
            try (var ps = conn.prepareStatement(
                    "SELECT o.character_id, o.learned_at_chapter, o.chapter_version AS learned_version, " +
                    "o.quote AS observer_quote, e.fact_id, e.quote, e.chapter_index, e.chapter_version, e.stale " +
                    "FROM fact_evidence_observers o JOIN fact_evidence e ON o.evidence_id=e.id " +
                    "WHERE e.project_id=? AND o.character_id=? AND o.learned_at_chapter<=? " +
                    "ORDER BY o.learned_at_chapter")) {
                ps.setString(1, projectId);
                ps.setString(2, characterId);
                ps.setInt(3, upToChapter);
                var rs = ps.executeQuery();
                while (rs.next()) {
                    var m = new java.util.LinkedHashMap<String, Object>();
                    m.put("characterId", rs.getString("character_id"));
                    m.put("learnedAtChapter", rs.getInt("learned_at_chapter"));
                    m.put("factId", rs.getString("fact_id"));
                    m.put("quote", rs.getString("quote"));
                    m.put("chapterIndex", rs.getInt("chapter_index"));
                    m.put("chapterVersion", rs.getInt("chapter_version"));
                    m.put("evidenceStale", rs.getInt("stale") == 1);
                    list.add(m);
                }
            } catch (SQLException e) { throw new RuntimeException(e); }
            return list;
        });
    }

    public List<java.util.Map<String, Object>> findObservers(
            String projectId, String evidenceId) {
        return storyFactRepository.read(conn -> {
            var list = new java.util.ArrayList<java.util.Map<String, Object>>();
            try (var ps = conn.prepareStatement(
                    "SELECT * FROM fact_evidence_observers WHERE evidence_id=? ORDER BY learned_at_chapter")) {
                ps.setString(1, evidenceId);
                var rs = ps.executeQuery();
                while (rs.next()) {
                    var m = new java.util.LinkedHashMap<String, Object>();
                    m.put("characterId", rs.getString("character_id"));
                    m.put("learnedAtChapter", rs.getInt("learned_at_chapter"));
                    m.put("chapterVersion", rs.getInt("chapter_version"));
                    list.add(m);
                }
            } catch (SQLException e) { throw new RuntimeException(e); }
            return list;
        });
    }
}
