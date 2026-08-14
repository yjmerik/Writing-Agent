package com.writing.novel.persistence.sqlite;

import com.writing.novel.domain.StoryFact;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * p10-w2-01: 故事真典持久化——独立于 NovelRepository SPI（单一职责，不污染既有接口）。
 * 提供 CRUD + 按状态/主体/类型查询 + supercession 审计链。
 * schema 由 SqliteSchemaInitializer 建表（story_facts），本类用同一 ConnectionProvider。
 */
@Component
public class StoryFactRepository {

    private final ConnectionProvider cp;

    public StoryFactRepository(ConnectionProvider cp) {
        this.cp = cp;
    }

    public void save(StoryFact f) {
        String sql = "INSERT INTO story_facts (id,project_id,fact_type,subject,assertion," +
                "chapter_established,source_chapter_version,importance,status,superseded_by," +
                "last_validated_chapter,created_at,updated_at) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?) " +
                "ON CONFLICT(id) DO UPDATE SET subject=excluded.subject, assertion=excluded.assertion, " +
                "importance=excluded.importance, status=excluded.status, superseded_by=excluded.superseded_by, " +
                "last_validated_chapter=excluded.last_validated_chapter, updated_at=excluded.updated_at";
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, f.getId()); ps.setString(2, f.getProjectId());
            ps.setString(3, f.getFactType().name()); ps.setString(4, f.getSubject());
            ps.setString(5, f.getAssertion()); ps.setInt(6, f.getChapterEstablished());
            ps.setInt(7, f.getSourceChapterVersion()); ps.setInt(8, f.getImportance());
            ps.setString(9, f.getStatus().name());
            ps.setString(10, f.getSupersededBy()); ps.setInt(11, f.getLastValidatedChapter());
            ps.setString(12, f.getCreatedAt().toString()); ps.setString(13, f.getUpdatedAt().toString());
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public Optional<StoryFact> findById(String id) {
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM story_facts WHERE id=?")) {
            ps.setString(1, id);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? Optional.of(map(rs)) : Optional.empty();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    /** 全部 ACTIVE 事实（按 importance 降序）——ContextAssembler/ContinuityChecker 检索消费 */
    public List<StoryFact> findActive(String projectId) {
        return queryList(projectId, "AND status='ACTIVE' ORDER BY importance DESC, chapter_established ASC");
    }

    /** 按主体（角色名/势力）查 ACTIVE 事实 */
    public List<StoryFact> findActiveBySubject(String projectId, String subject) {
        String sql = "SELECT * FROM story_facts WHERE project_id=? AND subject=? AND status='ACTIVE' ORDER BY importance DESC";
        List<StoryFact> out = new ArrayList<>();
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId); ps.setString(2, subject);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) out.add(map(rs));
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    /** 按类型查 ACTIVE 事实 */
    public List<StoryFact> findActiveByType(String projectId, StoryFact.FactType type) {
        String sql = "SELECT * FROM story_facts WHERE project_id=? AND fact_type=? AND status='ACTIVE' ORDER BY importance DESC";
        List<StoryFact> out = new ArrayList<>();
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId); ps.setString(2, type.name());
            ResultSet rs = ps.executeQuery();
            while (rs.next()) out.add(map(rs));
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    /** 全部事实（含 SUPERSEDED/RETIRED，审计/诊断用） */
    public List<StoryFact> findAll(String projectId) {
        return queryList(projectId, "ORDER BY chapter_established ASC, updated_at ASC");
    }

    /**
     * p10-w2-01: 用新事实取代旧事实——旧 SUPERSEDED + supersededBy 指向新，新 ACTIVE 落库。
     * 永不删除旧事实，保留审计链。
     */
    public void supersede(StoryFact oldFact, StoryFact newFact) {
        oldFact.supersedeBy(newFact);
        save(oldFact);
        save(newFact);
    }

    private List<StoryFact> queryList(String projectId, String suffix) {
        String sql = "SELECT * FROM story_facts WHERE project_id=? " + suffix;
        List<StoryFact> out = new ArrayList<>();
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) out.add(map(rs));
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    private StoryFact map(ResultSet rs) throws SQLException {
        StoryFact f = new StoryFact(rs.getString("project_id"),
                StoryFact.FactType.valueOf(rs.getString("fact_type")),
                rs.getString("subject"), rs.getString("assertion"),
                rs.getInt("chapter_established"));
        f.setId(rs.getString("id"));
        f.setSourceChapterVersion(rs.getInt("source_chapter_version"));
        f.setImportance(rs.getInt("importance"));
        f.setStatus(StoryFact.Status.valueOf(rs.getString("status")));
        String sb = rs.getString("superseded_by");
        if (sb != null) f.setSupersededBy(sb);
        f.setLastValidatedChapter(rs.getInt("last_validated_chapter"));
        return f;
    }
}
