package com.writing.novel.persistence.sqlite;

import com.writing.novel.domain.StoryFact;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
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
                "last_validated_chapter,created_at,updated_at,evidence_grade," +
                "predicate,object_value,polarity,valid_from,valid_to) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) " +
                "ON CONFLICT(id) DO UPDATE SET subject=excluded.subject, assertion=excluded.assertion, " +
                "importance=excluded.importance, status=excluded.status, superseded_by=excluded.superseded_by, " +
                "last_validated_chapter=excluded.last_validated_chapter, updated_at=excluded.updated_at, " +
                "evidence_grade=excluded.evidence_grade, predicate=excluded.predicate, " +
                "object_value=excluded.object_value, polarity=excluded.polarity, " +
                "valid_from=excluded.valid_from, valid_to=excluded.valid_to";
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, f.getId()); ps.setString(2, f.getProjectId());
            ps.setString(3, f.getFactType().name()); ps.setString(4, f.getSubject());
            ps.setString(5, f.getAssertion()); ps.setInt(6, f.getChapterEstablished());
            ps.setInt(7, f.getSourceChapterVersion()); ps.setInt(8, f.getImportance());
            ps.setString(9, f.getStatus().name());
            ps.setString(10, f.getSupersededBy()); ps.setInt(11, f.getLastValidatedChapter());
            ps.setString(12, f.getCreatedAt().toString()); ps.setString(13, f.getUpdatedAt().toString());
            ps.setString(14, f.getEvidenceGrade().name());
            ps.setString(15, f.getPredicate());
            ps.setString(16, f.getObjectValue());
            ps.setString(17, f.getPolarity());
            if (f.getValidFromChapter() > 0) ps.setInt(18, f.getValidFromChapter()); else ps.setInt(18, f.getChapterEstablished());
            if (f.getValidToChapter() != null) ps.setInt(19, f.getValidToChapter()); else ps.setNull(19, Types.NULL);
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

    // --- p39-10: 同连接事务辅助方法（包内使用，不向业务层暴露 SQL 或 ConnectionProvider） ---

    /** 同连接只读回调。 */
    public <T> T read(java.util.function.Function<Connection, T> work) {
        try (Connection conn = cp.getConnection()) {
            return work.apply(conn);
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    /** 同连接事务：save/supersede 组合操作，异常全回滚。 */
    public <T> T runInTransaction(java.util.function.Function<Connection, T> work) {
        Connection conn = null;
        try {
            conn = cp.getConnection();
            conn.setAutoCommit(false);
            T result = work.apply(conn);
            conn.commit();
            return result;
        } catch (Exception e) {
            if (conn != null) try { conn.rollback(); } catch (SQLException ignored) {}
            throw e instanceof RuntimeException re ? re : new RuntimeException(e);
        } finally {
            if (conn != null) try { conn.setAutoCommit(true); conn.close(); } catch (SQLException ignored) {}
        }
    }

    /** 连接内 save（事务回调使用）。 */
    public void save(Connection conn, StoryFact f) {
        String sql = "INSERT INTO story_facts (id,project_id,fact_type,subject,assertion," +
                "chapter_established,source_chapter_version,importance,status,superseded_by," +
                "last_validated_chapter,created_at,updated_at,evidence_grade," +
                "predicate,object_value,polarity,valid_from,valid_to) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) " +
                "ON CONFLICT(id) DO UPDATE SET subject=excluded.subject, assertion=excluded.assertion, " +
                "importance=excluded.importance, status=excluded.status, superseded_by=excluded.superseded_by, " +
                "last_validated_chapter=excluded.last_validated_chapter, updated_at=excluded.updated_at, " +
                "evidence_grade=excluded.evidence_grade, predicate=excluded.predicate, " +
                "object_value=excluded.object_value, polarity=excluded.polarity, " +
                "valid_from=excluded.valid_from, valid_to=excluded.valid_to";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, f.getId()); ps.setString(2, f.getProjectId());
            ps.setString(3, f.getFactType().name()); ps.setString(4, f.getSubject());
            ps.setString(5, f.getAssertion()); ps.setInt(6, f.getChapterEstablished());
            ps.setInt(7, f.getSourceChapterVersion()); ps.setInt(8, f.getImportance());
            ps.setString(9, f.getStatus().name());
            ps.setString(10, f.getSupersededBy()); ps.setInt(11, f.getLastValidatedChapter());
            ps.setString(12, f.getCreatedAt().toString()); ps.setString(13, f.getUpdatedAt().toString());
            ps.setString(14, f.getEvidenceGrade().name());
            ps.setString(15, f.getPredicate());
            ps.setString(16, f.getObjectValue());
            ps.setString(17, f.getPolarity());
            if (f.getValidFromChapter() > 0) ps.setInt(18, f.getValidFromChapter()); else ps.setInt(18, f.getChapterEstablished());
            if (f.getValidToChapter() != null) ps.setInt(19, f.getValidToChapter()); else ps.setNull(19, Types.NULL);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    /** 连接内 supersede（事务回调使用）。 */
    public void supersede(Connection conn, StoryFact oldFact, StoryFact newFact) {
        oldFact.supersedeBy(newFact);
        save(conn, oldFact);
        save(conn, newFact);
    }

    /** p39-10: 事实证据写入（连接内，供事务组合）。 */
    public void saveFactEvidence(Connection conn, String id, String factId, String projectId,
                                 int chapterIndex, int chapterVersion, String contentHash,
                                 String quote, int paragraphIndex, String anchorHash,
                                 int validFrom, Integer validTo) {
        String sql = "INSERT INTO fact_evidence (id, fact_id, project_id, chapter_index, chapter_version, " +
                "content_hash, quote, paragraph_index, anchor_hash, valid_from_chapter, " +
                "valid_to_chapter, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id); ps.setString(2, factId); ps.setString(3, projectId);
            ps.setInt(4, chapterIndex); ps.setInt(5, chapterVersion); ps.setString(6, contentHash);
            ps.setString(7, quote); ps.setInt(8, paragraphIndex); ps.setString(9, anchorHash);
            ps.setInt(10, validFrom);
            if (validTo != null) ps.setInt(11, validTo); else ps.setNull(11, Types.NULL);
            ps.setString(12, java.time.Instant.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
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
        try {
            f.setPredicate(rs.getString("predicate"));
            f.setObjectValue(rs.getString("object_value"));
            f.setPolarity(rs.getString("polarity"));
            int vf = rs.getInt("valid_from");
            if (!rs.wasNull() && vf > 0) f.setValidFromChapter(vf);
            int vt = rs.getInt("valid_to");
            f.setValidToChapter(rs.wasNull() ? null : vt);
        } catch (SQLException ignored) {}
        f.setSourceChapterVersion(rs.getInt("source_chapter_version"));
        f.setImportance(rs.getInt("importance"));
        f.setStatus(StoryFact.Status.valueOf(rs.getString("status")));
        String sb = rs.getString("superseded_by");
        if (sb != null) f.setSupersededBy(sb);
        f.setLastValidatedChapter(rs.getInt("last_validated_chapter"));
        // p32-06: 证据分级（迁移前旧行 NULL 视为已确认）
        String grade = rs.getString("evidence_grade");
        if (grade != null && !grade.isEmpty()) f.setEvidenceGrade(StoryFact.EvidenceGrade.valueOf(grade));
        return f;
    }
}
