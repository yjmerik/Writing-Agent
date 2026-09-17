package com.writing.novel.persistence.sqlite;

import com.writing.novel.domain.*;
import com.writing.novel.domain.Character;
import com.writing.novel.domain.ThreadStatus;  // 显式导入，避免与 java.lang.Character 歧义
import com.writing.novel.domain.WritingPreferences;
import com.writing.novel.spi.NovelRepository;
import com.writing.novel.util.ReviewFingerprint;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.*;
import java.time.Instant;
import java.util.*;

import com.fasterxml.jackson.core.JsonProcessingException;

public class SqliteNovelRepository implements NovelRepository {

    private final ConnectionProvider cp;

    public SqliteNovelRepository(ConnectionProvider cp) {
        this.cp = cp;
    }

    /**
     * B1: 在单个事务中执行多个写操作。中途异常自动回滚。
     * 用法: repo.runInTransaction(conn -> { saveChapter(conn, ch); saveContinuityIssue(conn, issue); return null; })
     */
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

    @Override
    public String saveProject(Project p) {
        String sql = "INSERT INTO projects (id,title,concept,tags,mode,state,current_chapter_index,genre,created_at,updated_at,book_summary,book_snapshot) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?) " +
                "ON CONFLICT(id) DO UPDATE SET title=excluded.title, concept=excluded.concept, " +
                "tags=excluded.tags, mode=excluded.mode, state=excluded.state, " +
                "current_chapter_index=excluded.current_chapter_index, genre=excluded.genre, " +
                "updated_at=excluded.updated_at, book_summary=excluded.book_summary, " +
                "book_snapshot=excluded.book_snapshot";
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, p.getId());
            ps.setString(2, p.getTitle());
            ps.setString(3, p.getConcept());
            ps.setString(4, p.getTags());
            ps.setString(5, p.getMode().name());
            ps.setString(6, p.getState().name());
            ps.setInt(7, p.getCurrentChapterIndex());
            ps.setString(8, p.getGenre() != null ? p.getGenre().name() : null);
            ps.setString(9, p.getCreatedAt().toString());
            ps.setString(10, p.getUpdatedAt().toString());
            ps.setString(11, p.getBookSummary());
            ps.setString(12, p.getBookSnapshot());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return p.getId();
    }

    @Override
    public Optional<Project> findProject(String id) {
        String sql = "SELECT * FROM projects WHERE id=?";
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return Optional.empty();
            Project p = new Project(rs.getString("title"),
                    rs.getString("concept"), rs.getString("tags"));
            setField(p, "id", id);
            p.setMode(ProjectMode.valueOf(rs.getString("mode")));
            p.setState(NovelState.valueOf(rs.getString("state")));
            p.setCurrentChapterIndex(rs.getInt("current_chapter_index"));
            try {
                String genreStr = rs.getString("genre");
                if (genreStr != null && !genreStr.isBlank()) {
                    p.setGenre(GenreProfile.valueOf(genreStr));
                }
            } catch (Exception ignored) {}
            try { p.setBookSummary(rs.getString("book_summary")); } catch (Exception ignored) {}
            try { p.setBookSnapshot(rs.getString("book_snapshot")); } catch (Exception ignored) {}
            return Optional.of(p);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<Project> findAllProjects() {
        List<Project> list = new ArrayList<>();
        try (Connection conn = cp.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id FROM projects")) {
            while (rs.next()) {
                findProject(rs.getString("id")).ifPresent(list::add);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return list;
    }

    @Override
    public void deleteProject(String id) {
        try (Connection conn = cp.getConnection()) {
            boolean oldAuto = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                java.util.List<String> tables = new java.util.ArrayList<>();
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")) {
                    ResultSet rs = ps.executeQuery();
                    while (rs.next()) tables.add(rs.getString(1));
                }
                for (String t : tables) {
                    if ("projects".equals(t)) continue;
                    if (!hasColumn(conn, t, "project_id")) continue;
                    try (PreparedStatement del = conn.prepareStatement(
                            "DELETE FROM " + t + " WHERE project_id=?")) {
                        del.setString(1, id);
                        del.executeUpdate();
                    }
                }
                try (PreparedStatement del = conn.prepareStatement("DELETE FROM projects WHERE id=?")) {
                    del.setString(1, id);
                    del.executeUpdate();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(oldAuto);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean hasColumn(Connection conn, String table, String column) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("PRAGMA table_info(" + table + ")")) {
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) return true;
            }
        }
        return false;
    }

    @Override
    public void saveKnowledgeEntry(KnowledgeEntry e) {
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = knowledgeEntryStatement(conn)) {
            bindKnowledgeEntry(ps, e);
            ps.executeUpdate();
        } catch (SQLException ex) { throw new RuntimeException(ex); }
    }

    /** p26-02: explicit setup revision is all-or-nothing; a failed candidate insert restores the old set. */
    @Override
    public void replaceKnowledgeEntries(String projectId, List<KnowledgeEntry> entries) {
        java.util.Objects.requireNonNull(projectId, "projectId");
        java.util.Objects.requireNonNull(entries, "entries");
        for (KnowledgeEntry entry : entries) {
            if (entry == null || !projectId.equals(entry.getProjectId())) {
                throw new IllegalArgumentException("世界观候选不属于项目: " + projectId);
            }
        }
        try (Connection conn = cp.getConnection()) {
            boolean oldAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement delete = conn.prepareStatement(
                        "DELETE FROM knowledge_entries WHERE project_id=?")) {
                    delete.setString(1, projectId);
                    delete.executeUpdate();
                }
                try (PreparedStatement insert = knowledgeEntryStatement(conn)) {
                    for (KnowledgeEntry entry : entries) {
                        bindKnowledgeEntry(insert, entry);
                        insert.executeUpdate();
                    }
                }
                conn.commit();
            } catch (SQLException | RuntimeException e) {
                try { conn.rollback(); } catch (SQLException rollbackFailure) { e.addSuppressed(rollbackFailure); }
                throw e;
            } finally {
                conn.setAutoCommit(oldAutoCommit);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private PreparedStatement knowledgeEntryStatement(Connection conn) throws SQLException {
        String sql = "INSERT INTO knowledge_entries (id,project_id,type,name,content,version,updated_at) " +
                "VALUES (?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET type=excluded.type, name=excluded.name, " +
                "content=excluded.content, version=excluded.version, updated_at=excluded.updated_at";
        return conn.prepareStatement(sql);
    }

    private void bindKnowledgeEntry(PreparedStatement ps, KnowledgeEntry e) throws SQLException {
        ps.setString(1, e.getId()); ps.setString(2, e.getProjectId());
        ps.setString(3, e.getType()); ps.setString(4, e.getName());
        ps.setString(5, e.getContent()); ps.setInt(6, e.getVersion());
        ps.setString(7, e.getUpdatedAt().toString());
    }

    @Override
    public List<KnowledgeEntry> findKnowledgeEntriesByProject(String projectId) {
        List<KnowledgeEntry> list = new ArrayList<>();
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM knowledge_entries WHERE project_id=?")) {
            ps.setString(1, projectId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(mapEntry(rs));
        } catch (SQLException e) { throw new RuntimeException(e); }
        return list;
    }

    @Override
    public Optional<KnowledgeEntry> findKnowledgeEntry(String id) {
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM knowledge_entries WHERE id=?")) {
            ps.setString(1, id);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? Optional.of(mapEntry(rs)) : Optional.empty();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    @Override
    public List<KnowledgeEntry> findKnowledgeEntriesByType(String projectId, String type) {
        List<KnowledgeEntry> list = new ArrayList<>();
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM knowledge_entries WHERE project_id=? AND type=?")) {
            ps.setString(1, projectId); ps.setString(2, type);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(mapEntry(rs));
        } catch (SQLException e) { throw new RuntimeException(e); }
        return list;
    }

    @Override
    public String saveCharacter(Character c) {
        String sql = "INSERT INTO characters (id,project_id,name,role,personality,ability,motivation," +
                "appearance,relationships,growth_arc,speaking_style,knowledge_domains," +
                "strategy_level,countermeasures,independent_will,blind_spot," +
                "arc_stage,last_seen_chapter,status,death_chapter," +
                "version,updated_at) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET " +
                "name=excluded.name, role=excluded.role, personality=excluded.personality, " +
                "ability=excluded.ability, motivation=excluded.motivation, appearance=excluded.appearance, " +
                "relationships=excluded.relationships, growth_arc=excluded.growth_arc, " +
                "speaking_style=excluded.speaking_style, knowledge_domains=excluded.knowledge_domains, " +
                "strategy_level=excluded.strategy_level, countermeasures=excluded.countermeasures, " +
                "independent_will=excluded.independent_will, blind_spot=excluded.blind_spot, " +
                "arc_stage=excluded.arc_stage, last_seen_chapter=excluded.last_seen_chapter, " +
                "status=excluded.status, death_chapter=excluded.death_chapter, " +
                "version=excluded.version, updated_at=excluded.updated_at";
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, c.getId()); ps.setString(2, c.getProjectId());
            ps.setString(3, c.getName()); ps.setString(4, c.getRole());
            ps.setString(5, c.getPersonality()); ps.setString(6, c.getAbility());
            ps.setString(7, c.getMotivation()); ps.setString(8, c.getAppearance());
            ps.setString(9, c.getRelationships()); ps.setString(10, c.getGrowthArc());
            ps.setString(11, c.getSpeakingStyle()); ps.setString(12, c.getKnowledgeDomains());
            if (c.getStrategyLevel() != null) ps.setInt(13, c.getStrategyLevel());
            else ps.setNull(13, Types.INTEGER);
            ps.setString(14, c.getCountermeasures());
            ps.setString(15, c.getIndependentWill());
            ps.setString(16, c.getBlindSpot());
            ps.setString(17, c.getArcStage());
            if (c.getLastSeenChapter() != null) ps.setInt(18, c.getLastSeenChapter());
            else ps.setNull(18, Types.INTEGER);
            ps.setString(19, c.getStatus() != null ? c.getStatus().name() : null);
            if (c.getDeathChapter() != null) ps.setInt(20, c.getDeathChapter());
            else ps.setNull(20, Types.INTEGER);
            ps.setInt(21, c.getVersion()); ps.setString(22, c.getUpdatedAt().toString());
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
        return c.getId();
    }

    @Override
    public Optional<Character> findCharacter(String id) {
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM characters WHERE id=?")) {
            ps.setString(1, id);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? Optional.of(mapCharacter(rs)) : Optional.empty();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    @Override
    public List<Character> findCharactersByProject(String projectId) {
        List<Character> list = new ArrayList<>();
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM characters WHERE project_id=? LIMIT 200")) {
            ps.setString(1, projectId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(mapCharacter(rs));
        } catch (SQLException e) { throw new RuntimeException(e); }
        return list;
    }

    @Override
    public String saveChapter(Chapter c) {
        try (Connection conn = cp.getConnection()) {
            return saveChapter(conn, c);
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    /** B1: 事务内保存章节——使用传入连接 */
    public String saveChapter(Connection conn, Chapter c) {
        String sql = "INSERT INTO chapters (id,project_id,chapter_index,title,content,word_count," +
                "version,status,created_at,updated_at,plan_ref) VALUES (?,?,?,?,?,?,?,?,?,?,?) " +
                "ON CONFLICT(id) DO UPDATE SET title=excluded.title, content=excluded.content, " +
                "word_count=excluded.word_count, version=excluded.version, status=excluded.status, " +
                "updated_at=excluded.updated_at, plan_ref=excluded.plan_ref";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, c.getId()); ps.setString(2, c.getProjectId());
            ps.setInt(3, c.getChapterIndex()); ps.setString(4, c.getTitle());
            ps.setString(5, c.getContent()); ps.setInt(6, c.getWordCount());
            ps.setInt(7, c.getVersion());
            ps.setString(8, c.getStatus() != null ? c.getStatus().name() : "DONE");
            ps.setString(9, c.getCreatedAt().toString());
            ps.setString(10, c.getUpdatedAt().toString());
            ps.setString(11, c.getPlanRef());
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
        return c.getId();
    }

    @Override
    public Optional<Chapter> findChapter(String id) {
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM chapters WHERE id=?")) {
            ps.setString(1, id);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? Optional.of(mapChapter(rs)) : Optional.empty();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    @Override
    public Optional<Chapter> findChapterByIndex(String projectId, int chapterIndex) {
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM chapters WHERE project_id=? AND chapter_index=?")) {
            ps.setString(1, projectId); ps.setInt(2, chapterIndex);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? Optional.of(mapChapter(rs)) : Optional.empty();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    @Override
    public List<Chapter> findChaptersByProject(String projectId) {
        List<Chapter> list = new ArrayList<>();
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM chapters WHERE project_id=? ORDER BY chapter_index")) {
            ps.setString(1, projectId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(mapChapter(rs));
        } catch (SQLException e) { throw new RuntimeException(e); }
        return list;
    }

    @Override
    public List<Chapter> findChapterMetaByProject(String projectId) {
        List<Chapter> list = new ArrayList<>();
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, project_id, chapter_index, title, word_count, version, status, created_at, updated_at " +
                     "FROM chapters WHERE project_id=? ORDER BY chapter_index")) {
            ps.setString(1, projectId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Chapter c = new Chapter(rs.getString("project_id"),
                        rs.getInt("chapter_index"), rs.getString("title"));
                setField(c, "id", rs.getString("id"));
                setField(c, "wordCount", rs.getInt("word_count"));
                setField(c, "version", rs.getInt("version"));
                // p23-04 修复: 元数据必须携带 status——懒加载列表的「待人审」标记依赖它
                try { c.setStatus(Chapter.Status.valueOf(rs.getString("status"))); }
                catch (Exception badStatus) { /* 未知状态保持默认 */ }
                list.add(c);
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return list;
    }

    @Override
    public int getTotalWordCount(String projectId) {
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COALESCE(SUM(word_count), 0) FROM chapters WHERE project_id=?")) {
            ps.setString(1, projectId);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    @Override
    public List<Chapter> findRecentChapters(String projectId, int count) {
        // B2: 直接 SQL 分页，不再全表加载（原 findChaptersByProject + subList）
        List<Chapter> list = new ArrayList<>();
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM chapters WHERE project_id=? ORDER BY chapter_index DESC LIMIT ?")) {
            ps.setString(1, projectId);
            ps.setInt(2, count);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(mapChapter(rs));
            java.util.Collections.reverse(list); // 恢复升序
        } catch (SQLException e) { throw new RuntimeException(e); }
        return list;
    }

    /** B2: 按章号范围加载（仅加载指定范围，不全表） */
    @Override
    public List<Chapter> findChaptersByIndexRange(String projectId, int fromIndex, int toIndex) {
        List<Chapter> list = new ArrayList<>();
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM chapters WHERE project_id=? AND chapter_index>=? AND chapter_index<=? ORDER BY chapter_index")) {
            ps.setString(1, projectId);
            ps.setInt(2, fromIndex);
            ps.setInt(3, toIndex);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(mapChapter(rs));
        } catch (SQLException e) { throw new RuntimeException(e); }
        return list;
    }

    /** B2: 章节计数（不加载正文，替代 findChaptersByProject().size()） */
    @Override
    public int countChaptersByProject(String projectId) {
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM chapters WHERE project_id=?")) {
            ps.setString(1, projectId);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    @Override
    public void saveChapterVersion(ChapterVersion snapshot) {
        String sql = "INSERT INTO chapter_versions " +
                "(id,project_id,chapter_index,version,title,content,source,created_at) " +
                "VALUES (?,?,?,?,?,?,?,?)";
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, snapshot.getId());
            ps.setString(2, snapshot.getProjectId());
            ps.setInt(3, snapshot.getChapterIndex());
            ps.setInt(4, snapshot.getVersion());
            ps.setString(5, snapshot.getTitle());
            ps.setString(6, snapshot.getContent());
            ps.setString(7, snapshot.getSource());
            ps.setString(8, snapshot.getCreatedAt().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<ChapterVersion> findChapterVersions(String projectId, int chapterIndex) {
        List<ChapterVersion> versions = new ArrayList<>();
        String sql = "SELECT * FROM chapter_versions WHERE project_id=? AND chapter_index=? " +
                "ORDER BY version DESC";
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId);
            ps.setInt(2, chapterIndex);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) versions.add(mapChapterVersion(rs));
            return versions;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Optional<ChapterVersion> findChapterVersion(String projectId, int chapterIndex, int version) {
        String sql = "SELECT * FROM chapter_versions " +
                "WHERE project_id=? AND chapter_index=? AND version=?";
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId);
            ps.setInt(2, chapterIndex);
            ps.setInt(3, version);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? Optional.of(mapChapterVersion(rs)) : Optional.empty();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean saveChapterRevision(ChapterVersion snapshot, Chapter revised, int expectedVersion) {
        return runInTransaction(conn ->
                doSaveChapterRevision(conn, snapshot, revised, expectedVersion));
    }

    /** p36-07 语义的连接内实现：正文 CAS + 快照 + 屏障重绑/批准失效，供事务内复用（p37-02 候选应用）。 */
    private boolean doSaveChapterRevision(Connection conn, ChapterVersion snapshot, Chapter revised,
                                          int expectedVersion) {
        // 先读被替换稿的旧身份——其是否已获作者批准决定新稿是否须重新人审
        String oldTitle = null;
        String oldContent = null;
        String selectOld = "SELECT title, content FROM chapters " +
                "WHERE id=? AND project_id=? AND chapter_index=? AND version=?";
        try (PreparedStatement ps = conn.prepareStatement(selectOld)) {
            ps.setString(1, revised.getId());
            ps.setString(2, revised.getProjectId());
            ps.setInt(3, revised.getChapterIndex());
            ps.setInt(4, expectedVersion);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return false;
            oldTitle = rs.getString("title");
            oldContent = rs.getString("content");
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        String oldContentHash = ChapterApproval.contentHash(oldTitle, oldContent);
        boolean hadApproval = approvalExistsForVersion(
                conn, revised.getProjectId(), revised.getChapterIndex(), expectedVersion, oldContentHash);

        String update = "UPDATE chapters SET title=?,content=?,word_count=?,version=?,status=?,updated_at=? " +
                "WHERE id=? AND project_id=? AND chapter_index=? AND version=?";
        try (PreparedStatement ps = conn.prepareStatement(update)) {
            ps.setString(1, revised.getTitle());
            ps.setString(2, revised.getContent());
            ps.setInt(3, revised.getWordCount());
            ps.setInt(4, revised.getVersion());
            ps.setString(5, revised.getStatus().name());
            ps.setString(6, revised.getUpdatedAt().toString());
            ps.setString(7, revised.getId());
            ps.setString(8, revised.getProjectId());
            ps.setInt(9, revised.getChapterIndex());
            ps.setInt(10, expectedVersion);
            if (ps.executeUpdate() != 1) return false;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        String insert = "INSERT INTO chapter_versions " +
                "(id,project_id,chapter_index,version,title,content,source,created_at) " +
                "VALUES (?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(insert)) {
            ps.setString(1, snapshot.getId());
            ps.setString(2, snapshot.getProjectId());
            ps.setInt(3, snapshot.getChapterIndex());
            ps.setInt(4, snapshot.getVersion());
            ps.setString(5, snapshot.getTitle());
            ps.setString(6, snapshot.getContent());
            ps.setString(7, snapshot.getSource());
            ps.setString(8, snapshot.getCreatedAt().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        // 屏障旧身份审计 + 重绑在同一事务内——重绑失败则正文/快照一并回滚
        rebindActiveBarriersInTransaction(conn, revised.getProjectId(), revised.getChapterIndex(),
                expectedVersion, revised.getVersion());

        // 当前批准有效性失效——被替换稿已获作者批准时，新稿必须重新人审
        if (hadApproval) {
            ReviewBarrier reReview = new ReviewBarrier(revised.getProjectId(), revised.getChapterIndex(),
                    revised.getVersion(), "作者已批准的稿件已修订，需重新审查");
            insertReviewBarrier(conn, reReview);
            insertBarrierHistory(conn, reReview.getId(), revised.getProjectId(),
                    revised.getChapterIndex(), expectedVersion, revised.getVersion(), "REVISED");
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE chapters SET status='NEEDS_MANUAL_REVIEW' " +
                            "WHERE id=? AND project_id=? AND chapter_index=?")) {
                ps.setString(1, revised.getId());
                ps.setString(2, revised.getProjectId());
                ps.setInt(3, revised.getChapterIndex());
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }
        return true;
    }

    private boolean approvalExistsForVersion(Connection conn, String projectId, int chapterIndex,
                                             int version, String contentHash) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM chapter_approvals WHERE project_id=? AND chapter_index=? " +
                        "AND chapter_version=? AND content_hash=?")) {
            ps.setString(1, projectId);
            ps.setInt(2, chapterIndex);
            ps.setInt(3, version);
            ps.setString(4, contentHash);
            ResultSet rs = ps.executeQuery();
            rs.next();
            return rs.getInt(1) > 0;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private void rebindActiveBarriersInTransaction(Connection conn, String projectId, int chapterIndex,
                                                   int oldVersion, int newVersion) {
        List<String> barrierIds = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id FROM review_barriers WHERE project_id=? AND chapter_index=? " +
                        "AND status='ACTIVE'")) {
            ps.setString(1, projectId);
            ps.setInt(2, chapterIndex);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) barrierIds.add(rs.getString(1));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        for (String barrierId : barrierIds) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE review_barriers SET chapter_version=?, " +
                            "reason='内容已修订，需重新审查（原：' || reason || '）' WHERE id=?")) {
                ps.setInt(1, newVersion);
                ps.setString(2, barrierId);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
            insertBarrierHistory(conn, barrierId, projectId, chapterIndex, oldVersion, newVersion, "REVISED");
        }
    }

    private void insertReviewBarrier(Connection conn, ReviewBarrier barrier) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO review_barriers (id,project_id,chapter_index,chapter_version," +
                        "reason,status,created_at,resolved_at) VALUES (?,?,?,?,?,?,?,?)")) {
            ps.setString(1, barrier.getId());
            ps.setString(2, barrier.getProjectId());
            ps.setInt(3, barrier.getChapterIndex());
            ps.setInt(4, barrier.getChapterVersion());
            ps.setString(5, barrier.getReason());
            ps.setString(6, barrier.getStatus().name());
            ps.setString(7, barrier.getCreatedAt().toString());
            ps.setNull(8, Types.NULL);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private void insertBarrierHistory(Connection conn, String barrierId, String projectId, int chapterIndex,
                                      int oldVersion, int newVersion, String event) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO review_barrier_history (barrier_id, project_id, chapter_index, " +
                        "old_chapter_version, new_chapter_version, event, created_at) " +
                        "VALUES (?,?,?,?,?,?,?)")) {
            ps.setString(1, barrierId);
            ps.setString(2, projectId);
            ps.setInt(3, chapterIndex);
            ps.setInt(4, oldVersion);
            ps.setInt(5, newVersion);
            ps.setString(6, event);
            ps.setString(7, Instant.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // --- p33-01: 人审屏障 ---

    @Override
    public void saveReviewBarrier(com.writing.novel.domain.ReviewBarrier barrier) {
        String sql = "INSERT INTO review_barriers (id,project_id,chapter_index,chapter_version," +
                "reason,status,created_at,resolved_at) VALUES (?,?,?,?,?,?,?,?) " +
                "ON CONFLICT(id) DO UPDATE SET status=excluded.status, resolved_at=excluded.resolved_at";
        try (Connection conn = cp.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, barrier.getId()); ps.setString(2, barrier.getProjectId());
            ps.setInt(3, barrier.getChapterIndex()); ps.setInt(4, barrier.getChapterVersion());
            ps.setString(5, barrier.getReason()); ps.setString(6, barrier.getStatus().name());
            ps.setString(7, barrier.getCreatedAt().toString());
            if (barrier.getResolvedAt() != null) ps.setString(8, barrier.getResolvedAt().toString());
            else ps.setNull(8, Types.NULL);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    @Override
    public List<com.writing.novel.domain.ReviewBarrier> findActiveReviewBarriers(String projectId) {
        List<com.writing.novel.domain.ReviewBarrier> barriers = new ArrayList<>();
        try (Connection conn = cp.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM review_barriers WHERE project_id=? AND status='ACTIVE' ORDER BY chapter_index")) {
            ps.setString(1, projectId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) barriers.add(mapBarrier(rs));
        } catch (SQLException e) { throw new RuntimeException(e); }
        // 惰性补建：遗留 NEEDS_MANUAL_REVIEW 章节无屏障行时按需补（不动旧章节数据，仅补审计对象）
        Set<Integer> barrierChapters = barriers.stream()
                .map(com.writing.novel.domain.ReviewBarrier::getChapterIndex)
                .collect(java.util.stream.Collectors.toSet());
        for (Chapter ch : findChaptersByProject(projectId)) {
            if (ch.getStatus() == Chapter.Status.NEEDS_MANUAL_REVIEW && !barrierChapters.contains(ch.getChapterIndex())) {
                com.writing.novel.domain.ReviewBarrier backfill = new com.writing.novel.domain.ReviewBarrier(
                        projectId, ch.getChapterIndex(), ch.getVersion(), "章节处于待人审状态（自动补建屏障）");
                saveReviewBarrier(backfill);
                barriers.add(backfill);
            }
        }
        barriers.sort(java.util.Comparator.comparingInt(com.writing.novel.domain.ReviewBarrier::getChapterIndex));
        return barriers;
    }

    @Override
    public int resolveReviewBarriers(String projectId, int chapterIndex) {
        try (Connection conn = cp.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "UPDATE review_barriers SET status='RESOLVED', resolved_at=? " +
                        "WHERE project_id=? AND chapter_index=? AND status='ACTIVE'")) {
            ps.setString(1, java.time.Instant.now().toString());
            ps.setString(2, projectId); ps.setInt(3, chapterIndex);
            return ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    // --- p33-06: 作者意见闭环 ---

    @Override
    public void saveAuthorDirective(com.writing.novel.domain.AuthorDirective d) {
        String sql = "INSERT INTO author_directives (id,project_id,kind,chapter_index,content,status," +
                "system_evidence,system_evidence_version,created_at,confirmed_at,updated_at," +
                "revision,from_chapter,to_chapter,enabled) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) " +
                "ON CONFLICT(id) DO UPDATE SET status=excluded.status, system_evidence=excluded.system_evidence, " +
                "system_evidence_version=excluded.system_evidence_version, confirmed_at=excluded.confirmed_at, " +
                "updated_at=excluded.updated_at, revision=excluded.revision, from_chapter=excluded.from_chapter, " +
                "to_chapter=excluded.to_chapter, enabled=excluded.enabled";
        try (Connection conn = cp.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, d.getId()); ps.setString(2, d.getProjectId());
            ps.setString(3, d.getKind().name());
            if (d.getChapterIndex() != null) ps.setInt(4, d.getChapterIndex());
            else ps.setNull(4, Types.NULL);
            ps.setString(5, d.getContent()); ps.setString(6, d.getStatus().name());
            ps.setString(7, d.getSystemEvidence());
            if (d.getSystemEvidenceVersion() != null) ps.setInt(8, d.getSystemEvidenceVersion());
            else ps.setNull(8, Types.NULL);
            ps.setString(9, d.getCreatedAt().toString());
            if (d.getConfirmedAt() != null) ps.setString(10, d.getConfirmedAt().toString());
            else ps.setNull(10, Types.NULL);
            ps.setString(11, d.getUpdatedAt().toString());
            ps.setInt(12, d.getRevision() == null ? 1 : d.getRevision());
            if (d.getFromChapter() != null) ps.setInt(13, d.getFromChapter());
            else ps.setNull(13, Types.NULL);
            if (d.getToChapter() != null) ps.setInt(14, d.getToChapter());
            else ps.setNull(14, Types.NULL);
            ps.setInt(15, d.isEnabled() ? 1 : 0);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    @Override
    public List<com.writing.novel.domain.AuthorDirective> findAuthorDirectives(String projectId) {
        List<com.writing.novel.domain.AuthorDirective> list = new ArrayList<>();
        try (Connection conn = cp.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM author_directives WHERE project_id=? ORDER BY created_at DESC")) {
            ps.setString(1, projectId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(mapDirective(rs));
        } catch (SQLException e) { throw new RuntimeException(e); }
        return list;
    }

    @Override
    public java.util.Optional<com.writing.novel.domain.AuthorDirective> findAuthorDirective(
            String projectId, String id) {
        try (Connection conn = cp.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM author_directives WHERE project_id=? AND id=?")) {
            ps.setString(1, projectId); ps.setString(2, id);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? java.util.Optional.of(mapDirective(rs)) : java.util.Optional.empty();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    private com.writing.novel.domain.AuthorDirective mapDirective(ResultSet rs) throws SQLException {
        Integer chapterIndex = rs.getObject("chapter_index") == null ? null : rs.getInt("chapter_index");
        Integer evidenceVersion = rs.getObject("system_evidence_version") == null ? null
                : rs.getInt("system_evidence_version");
        String confirmedAt = rs.getString("confirmed_at");
        com.writing.novel.domain.AuthorDirective directive = new com.writing.novel.domain.AuthorDirective(
                rs.getString("id"), rs.getString("project_id"),
                com.writing.novel.domain.AuthorDirective.Kind.valueOf(rs.getString("kind")),
                chapterIndex, rs.getString("content"),
                com.writing.novel.domain.AuthorDirective.Status.valueOf(rs.getString("status")),
                rs.getString("system_evidence"), evidenceVersion,
                java.time.Instant.parse(rs.getString("created_at")),
                confirmedAt == null ? null : java.time.Instant.parse(confirmedAt),
                java.time.Instant.parse(rs.getString("updated_at")));
        try {
            directive.setRevision(rs.getObject("revision") == null ? 1 : rs.getInt("revision"));
            directive.setScope(rs.getObject("from_chapter") == null ? null : rs.getInt("from_chapter"),
                    rs.getObject("to_chapter") == null ? null : rs.getInt("to_chapter"));
            directive.setEnabled(rs.getObject("enabled") == null || rs.getInt("enabled") == 1);
        } catch (Exception ignored) {
            // 旧 schema 列缺失时保持缺省
        }
        return directive;
    }

    // --- p33-07: 候选线索层 ---

    @Override
    public void saveForeshadowCandidate(com.writing.novel.domain.ForeshadowCandidate c) {
        String sql = "INSERT INTO foreshadow_candidates (id,project_id,description,planted_context," +
                "planted_at_chapter,status,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?) " +
                "ON CONFLICT(id) DO UPDATE SET status=excluded.status, updated_at=excluded.updated_at";
        try (Connection conn = cp.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, c.getId()); ps.setString(2, c.getProjectId());
            ps.setString(3, c.getDescription()); ps.setString(4, c.getPlantedContext());
            ps.setInt(5, c.getPlantedAtChapter()); ps.setString(6, c.getStatus().name());
            ps.setString(7, c.getCreatedAt().toString()); ps.setString(8, c.getUpdatedAt().toString());
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    @Override
    public List<com.writing.novel.domain.ForeshadowCandidate> findForeshadowCandidates(String projectId) {
        List<com.writing.novel.domain.ForeshadowCandidate> list = new ArrayList<>();
        try (Connection conn = cp.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM foreshadow_candidates WHERE project_id=? ORDER BY created_at DESC")) {
            ps.setString(1, projectId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                list.add(new com.writing.novel.domain.ForeshadowCandidate(
                        rs.getString("id"), rs.getString("project_id"),
                        rs.getString("description"), rs.getString("planted_context"),
                        rs.getInt("planted_at_chapter"),
                        com.writing.novel.domain.ForeshadowCandidate.Status.valueOf(rs.getString("status")),
                        java.time.Instant.parse(rs.getString("created_at")),
                        java.time.Instant.parse(rs.getString("updated_at"))));
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return list;
    }

    @Override
    public void promoteForeshadowCandidate(String projectId, String candidateId) {
        var candidate = findForeshadowCandidates(projectId).stream()
                .filter(c -> c.getId().equals(candidateId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("候选线索不存在: " + candidateId));
        if (candidate.getStatus() == com.writing.novel.domain.ForeshadowCandidate.Status.PROMOTED) return;
        Foreshadowing f = new Foreshadowing(projectId, candidate.getDescription(),
                candidate.getPlantedContext(), candidate.getPlantedAtChapter());
        f.setPriority(Foreshadowing.Priority.HIGH);
        saveForeshadowing(f);
        candidate.promote();
        saveForeshadowCandidate(candidate);
    }

    @Override
    public void rejectForeshadowCandidate(String projectId, String candidateId) {
        var candidate = findForeshadowCandidates(projectId).stream()
                .filter(c -> c.getId().equals(candidateId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("候选线索不存在: " + candidateId));
        candidate.reject();
        saveForeshadowCandidate(candidate);
    }

    @Override
    public int rebindReviewBarriers(String projectId, int chapterIndex, int newVersion) {
        try (Connection conn = cp.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "UPDATE review_barriers SET chapter_version=?, " +
                        "reason='内容已修订，需重新审查（原：' || reason || '）' " +
                        "WHERE project_id=? AND chapter_index=? AND status='ACTIVE'")) {
            ps.setInt(1, newVersion);
            ps.setString(2, projectId); ps.setInt(3, chapterIndex);
            return ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    private com.writing.novel.domain.ReviewBarrier mapBarrier(ResultSet rs) throws SQLException {
        java.time.Instant resolvedAt = rs.getString("resolved_at") == null
                ? null : java.time.Instant.parse(rs.getString("resolved_at"));
        return new com.writing.novel.domain.ReviewBarrier(
                rs.getString("id"), rs.getString("project_id"), rs.getInt("chapter_index"),
                rs.getInt("chapter_version"), rs.getString("reason"),
                com.writing.novel.domain.ReviewBarrier.Status.valueOf(rs.getString("status")),
                java.time.Instant.parse(rs.getString("created_at")), resolvedAt);
    }

    // --- p36-03: 版本化审阅上下文与原子比较批准（单连接事务，禁止混用新开连接的公共方法） ---

    @Override
    public ChapterApproval.ReviewContext readReviewContext(String projectId, int chapterIndex) {
        return runInTransaction(conn -> {
            Chapter chapter = selectChapterByIndex(conn, projectId, chapterIndex);
            if (chapter == null) throw new IllegalArgumentException("章节不存在: " + chapterIndex);
            ChapterApproval.Identity identity = ChapterApproval.Identity.of(chapter);
            String reviewSetHash = computeReviewSetHash(conn, projectId, chapterIndex);
            List<String> barrierIds = selectActiveBarrierIds(conn, projectId);
            List<String> checkpointIds = selectPendingChapterClassCheckpointIds(conn, projectId);
            String reportCurrent = selectReportCurrent(conn, projectId, chapterIndex);
            ChapterApproval approval = findApprovalForIdentity(conn, projectId, chapterIndex,
                    identity.version(), identity.contentHash());
            // issues/dispositions/directives 为 p37/p38 预留：默认空，绝不默认已通过
            return new ChapterApproval.ReviewContext(identity, reviewSetHash, checkpointIds, barrierIds,
                    reportCurrent, approval, List.of(), List.of(), List.of());
        });
    }

    @Override
    public ChapterApproval.Result approveChapter(ChapterApproval.Request request) {
        if (request == null || request.identity() == null || request.identity().projectId() == null
                || request.reviewSetHash() == null || request.reviewSetHash().isBlank()
                || request.idempotencyKey() == null || request.idempotencyKey().isBlank()) {
            throw new IllegalArgumentException("批准请求缺少 identity/reviewSetHash/idempotencyKey，服务端不补填");
        }
        ChapterApproval.Identity requested = request.identity();
        return runInTransaction(conn -> {
            Chapter chapter = selectChapterByIndex(conn, requested.projectId(), requested.chapterIndex());
            if (chapter == null) {
                throw new IllegalArgumentException(
                        "章节不存在: " + requested.projectId() + "#" + requested.chapterIndex());
            }
            ChapterApproval.Identity currentIdentity = ChapterApproval.Identity.of(chapter);
            String currentReviewSetHash = computeReviewSetHash(conn, requested.projectId(), requested.chapterIndex());

            // 1. 幂等：同 key 同语义恢复原结果；同 key 不同语义为冲突（不借机批准新稿）
            ChapterApproval existing = selectApprovalByIdempotencyKey(
                    conn, requested.projectId(), request.idempotencyKey());
            if (existing != null) {
                boolean sameSemantics = existing.identity().equals(requested)
                        && existing.reviewSetHash().equals(request.reviewSetHash());
                if (sameSemantics) {
                    return new ChapterApproval.Result(existing.approvalId(), existing.identity(), true);
                }
                throw new ReviewConflictException(ReviewConflictException.Reason.IDEMPOTENCY_CONFLICT,
                        "幂等键已绑定不同的批准语义", currentIdentity, currentReviewSetHash);
            }

            // 2. 双重批准守卫：不同 key 双击不算两次有效批准
            if (findApprovalForIdentity(conn, requested.projectId(), requested.chapterIndex(),
                    currentIdentity.version(), currentIdentity.contentHash()) != null) {
                throw new ReviewConflictException(ReviewConflictException.Reason.ALREADY_APPROVED,
                        "该稿件身份已被批准", currentIdentity, currentReviewSetHash);
            }

            // 3. 身份比较：请求必须精确匹配当前稿（version + contentHash）
            if (!currentIdentity.equals(requested)) {
                throw new ReviewConflictException(ReviewConflictException.Reason.IDENTITY_STALE,
                        "章节身份已变化，请刷新后重新审阅", currentIdentity, currentReviewSetHash);
            }
            // 4. 待审集合比较：报告/屏障/章节类检查点任一变化即失效
            if (!currentReviewSetHash.equals(request.reviewSetHash())) {
                throw new ReviewConflictException(ReviewConflictException.Reason.REVIEW_SET_STALE,
                        "待审集合已变化，请刷新后重新审阅", currentIdentity, currentReviewSetHash);
            }

            // 4.5 审计 P1-2: 满足性检查——不仅要"没变化"，还要"问题已解决"
            // (a) 适用 HARD/PREFERENCE 意见必须有当前稿的 APPLIED_SYSTEM/CONFIRMED 证据
            List<String> unsatisfiedDirectives = selectUnsatisfiedDirectives(
                    conn, requested.projectId(), requested.chapterIndex(), currentIdentity.version(), currentIdentity.contentHash());
            if (!unsatisfiedDirectives.isEmpty()) {
                throw new IllegalStateException("作者要求未落实，不能批准: "
                        + String.join(", ", unsatisfiedDirectives));
            }
            // (b) 未解决 HIGH 问题且无有效裁决 → 阻塞
            List<String> blockingIssues = selectBlockingHighIssues(
                    conn, currentIdentity);
            if (!blockingIssues.isEmpty()) {
                throw new IllegalStateException("存在未解决的 HIGH 级问题（无有效裁决）: "
                        + String.join(", ", blockingIssues));
            }

            // 5. 写批准审计（不可变行）
            String approvalId = UUID.randomUUID().toString();
            Instant now = Instant.now();
            insertChapterApproval(conn, approvalId, request, currentReviewSetHash, now);

            // 6. 标记精确版本 DONE——版本不匹配（并发已变）即整体回滚
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE chapters SET status='DONE', updated_at=? " +
                            "WHERE project_id=? AND chapter_index=? AND version=?")) {
                ps.setString(1, now.toString());
                ps.setString(2, requested.projectId());
                ps.setInt(3, requested.chapterIndex());
                ps.setInt(4, currentIdentity.version());
                if (ps.executeUpdate() != 1) {
                    throw new ReviewConflictException(ReviewConflictException.Reason.IDENTITY_STALE,
                            "章节版本并发变化，批准已回滚", currentIdentity, currentReviewSetHash);
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }

            // 7. 只解决绑定该稿号（章号+版本）的 ACTIVE 屏障，并追加放行历史
            List<String> barrierIds = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id FROM review_barriers WHERE project_id=? AND chapter_index=? " +
                            "AND chapter_version=? AND status='ACTIVE'")) {
                ps.setString(1, requested.projectId());
                ps.setInt(2, requested.chapterIndex());
                ps.setInt(3, currentIdentity.version());
                ResultSet rs = ps.executeQuery();
                while (rs.next()) barrierIds.add(rs.getString(1));
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
            for (String barrierId : barrierIds) {
                try (PreparedStatement resolve = conn.prepareStatement(
                        "UPDATE review_barriers SET status='RESOLVED', resolved_at=? WHERE id=? AND status='ACTIVE'");
                     PreparedStatement history = conn.prepareStatement(
                        "INSERT INTO review_barrier_history (barrier_id, project_id, chapter_index, " +
                                "old_chapter_version, new_chapter_version, event, created_at) " +
                                "VALUES (?,?,?,?,?, 'RESOLVED', ?)")) {
                    resolve.setString(1, now.toString());
                    resolve.setString(2, barrierId);
                    resolve.executeUpdate();
                    history.setString(1, barrierId);
                    history.setString(2, requested.projectId());
                    history.setInt(3, requested.chapterIndex());
                    history.setInt(4, currentIdentity.version());
                    history.setInt(5, currentIdentity.version());
                    history.setString(6, now.toString());
                    history.executeUpdate();
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            }

            // 8. 处理同章章节类检查点（payload/description 定位章号）
            for (Checkpoint cp : selectPendingChapterClassCheckpoints(conn, requested.projectId())) {
                Integer cpChapter = checkpointChapterIndex(cp);
                if (cpChapter != null && cpChapter == requested.chapterIndex()) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "UPDATE checkpoints SET status='APPROVED' WHERE id=? AND status='PENDING'")) {
                        ps.setString(1, cp.getId());
                        ps.executeUpdate();
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                }
            }

            // 9. 按剩余待审项决定项目是否退出停点：任何类型待审 checkpoint 或 ACTIVE 屏障存在则保持
            int remaining = countPendingCheckpoints(conn, requested.projectId())
                    + countActiveBarriers(conn, requested.projectId());
            if (remaining == 0 && isProjectPausedAtCheckpoint(conn, requested.projectId())) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE projects SET state='WRITING', updated_at=? WHERE id=?")) {
                    ps.setString(1, now.toString());
                    ps.setString(2, requested.projectId());
                    ps.executeUpdate();
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            }
            return new ChapterApproval.Result(approvalId, currentIdentity, false);
        });
    }

    /** p36-11: 只读查询——该稿件身份是否有作者批准记录（成书覆盖清单诚实状态用）。 */
    @Override
    public boolean isChapterApproved(String projectId, int chapterIndex, int version, String contentHash) {
        try (Connection conn = cp.getConnection()) {
            return findApprovalForIdentity(conn, projectId, chapterIndex, version, contentHash) != null;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // --- p37-02: 持久修订候选存取与原子应用 ---

    @Override
    public void saveProposal(RevisionProposal proposal) {
        if (proposal == null || proposal.getProjectId() == null
                || proposal.getRequestKey() == null || proposal.getRequestKey().isBlank()) {
            throw new IllegalArgumentException("候选缺少 projectId/requestKey");
        }
        String sql = "INSERT INTO revision_proposals (id, project_id, target_type, target_id, " +
                "chapter_index, passage_index, base_version, source_hash, instructions, " +
                "before_json, after_json, summary, status, request_key, result_json, " +
                "created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection conn = cp.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            fillProposal(ps, proposal);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private void fillProposal(PreparedStatement ps, RevisionProposal p) throws SQLException {
        ps.setString(1, p.getId());
        ps.setString(2, p.getProjectId());
        ps.setString(3, p.getTargetType().name());
        ps.setString(4, p.getTargetId());
        if (p.getChapterIndex() != null) ps.setInt(5, p.getChapterIndex());
        else ps.setNull(5, Types.NULL);
        if (p.getPassageIndex() != null) ps.setInt(6, p.getPassageIndex());
        else ps.setNull(6, Types.NULL);
        ps.setInt(7, p.getBaseVersion());
        ps.setString(8, p.getSourceHash());
        ps.setString(9, p.getInstructions());
        ps.setString(10, p.getBeforeJson());
        ps.setString(11, p.getAfterJson());
        ps.setString(12, p.getSummary());
        ps.setString(13, p.getStatus().name());
        ps.setString(14, p.getRequestKey());
        ps.setString(15, p.getResultJson());
        ps.setString(16, p.getCreatedAt().toString());
        ps.setString(17, p.getUpdatedAt().toString());
    }

    @Override
    public java.util.Optional<RevisionProposal> findProposal(String projectId, String proposalId) {
        try (Connection conn = cp.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM revision_proposals WHERE project_id=? AND id=?")) {
            ps.setString(1, projectId);
            ps.setString(2, proposalId);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? java.util.Optional.of(mapProposal(rs)) : java.util.Optional.empty();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public java.util.List<RevisionProposal> listProposals(String projectId) {
        List<RevisionProposal> list = new ArrayList<>();
        try (Connection conn = cp.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM revision_proposals WHERE project_id=? ORDER BY created_at DESC")) {
            ps.setString(1, projectId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(mapProposal(rs));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return list;
    }

    private RevisionProposal mapProposal(ResultSet rs) throws SQLException {
        return new RevisionProposal(rs.getString("id"), rs.getString("project_id"),
                RevisionProposal.TargetType.valueOf(rs.getString("target_type")),
                rs.getString("target_id"),
                rs.getObject("chapter_index") == null ? null : rs.getInt("chapter_index"),
                rs.getObject("passage_index") == null ? null : rs.getInt("passage_index"),
                rs.getInt("base_version"), rs.getString("source_hash"),
                rs.getString("instructions"), rs.getString("before_json"),
                rs.getString("after_json"), rs.getString("summary"),
                RevisionProposal.Status.valueOf(rs.getString("status")),
                rs.getString("request_key"), rs.getString("result_json"),
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at")));
    }

    @Override
    public boolean cancelProposal(String projectId, String proposalId) {
        // CAS：仅 READY 可取消；取消留记录不删除
        try (Connection conn = cp.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "UPDATE revision_proposals SET status='CANCELLED', updated_at=? " +
                        "WHERE project_id=? AND id=? AND status='READY'")) {
            ps.setString(1, Instant.now().toString());
            ps.setString(2, projectId);
            ps.setString(3, proposalId);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public java.util.List<java.util.Map<String, Object>> findDirectiveEvidence(
            String projectId, String directiveId) {
        List<java.util.Map<String, Object>> list = new ArrayList<>();
        try (Connection conn = cp.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT id, directive_revision, chapter_index, chapter_version, content_hash, " +
                        "proposal_id, quote, anchor, status, reason FROM directive_evidence " +
                        "WHERE project_id=? AND directive_id=? ORDER BY created_at")) {
            ps.setString(1, projectId);
            ps.setString(2, directiveId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getString("id"));
                m.put("directiveRevision", rs.getInt("directive_revision"));
                m.put("chapterIndex", rs.getInt("chapter_index"));
                m.put("chapterVersion", rs.getInt("chapter_version"));
                m.put("contentHash", rs.getString("content_hash"));
                m.put("proposalId", rs.getString("proposal_id"));
                m.put("quote", rs.getString("quote"));
                m.put("anchor", rs.getString("anchor"));
                m.put("status", rs.getString("status"));
                m.put("reason", rs.getString("reason"));
                list.add(m);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return list;
    }

    /** 冲突后的独立短事务标 STALE——绝不触碰正文。 */
    @Override
    public boolean markProposalStale(String projectId, String proposalId) {
        try (Connection conn = cp.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "UPDATE revision_proposals SET status='STALE', updated_at=? " +
                        "WHERE project_id=? AND id=? AND status='READY'")) {
            ps.setString(1, Instant.now().toString());
            ps.setString(2, projectId);
            ps.setString(3, proposalId);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // --- p38-06: 成书版本存取 ---

    @Override
    public void saveBookEdition(com.writing.novel.domain.BookEdition edition,
            List<com.writing.novel.domain.BookEdition.EditionChapter> chapters) {
        runInTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO book_editions (edition_id, project_id, manifest_hash, state, created_at) " +
                            "VALUES (?,?,?,?,?)")) {
                ps.setString(1, edition.getEditionId()); ps.setString(2, edition.getProjectId());
                ps.setString(3, edition.getManifestHash()); ps.setString(4, edition.getState().name());
                ps.setString(5, edition.getCreatedAt().toString());
                ps.executeUpdate();
            } catch (SQLException e) { throw new RuntimeException(e); }
            for (var ch : chapters) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO book_edition_chapters (edition_id, chapter_index, version, title, " +
                                "content, content_hash, approval_id, plan_ref) VALUES (?,?,?,?,?,?,?,?)")) {
                    ps.setString(1, edition.getEditionId()); ps.setInt(2, ch.chapterIndex());
                    ps.setInt(3, ch.version()); ps.setString(4, ch.title());
                    ps.setString(5, ch.content()); ps.setString(6, ch.contentHash());
                    ps.setString(7, ch.approvalId()); ps.setString(8, ch.planRef());
                    ps.executeUpdate();
                } catch (SQLException e) { throw new RuntimeException(e); }
            }
            return null;
        });
    }

    @Override
    public java.util.Optional<com.writing.novel.domain.BookEdition> findBookEdition(
            String projectId, String editionId) {
        try (Connection conn = cp.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM book_editions WHERE project_id=? AND edition_id=?")) {
            ps.setString(1, projectId); ps.setString(2, editionId);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return java.util.Optional.empty();
            return java.util.Optional.of(new com.writing.novel.domain.BookEdition(
                    rs.getString("edition_id"), rs.getString("project_id"),
                    rs.getString("manifest_hash"),
                    com.writing.novel.domain.BookEdition.State.valueOf(rs.getString("state")),
                    Instant.parse(rs.getString("created_at")),
                    rs.getString("approved_at") == null ? null : Instant.parse(rs.getString("approved_at"))));
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    @Override
    public List<com.writing.novel.domain.BookEdition> listBookEditions(String projectId) {
        List<com.writing.novel.domain.BookEdition> list = new ArrayList<>();
        try (Connection conn = cp.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM book_editions WHERE project_id=? ORDER BY created_at DESC")) {
            ps.setString(1, projectId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                list.add(new com.writing.novel.domain.BookEdition(
                        rs.getString("edition_id"), rs.getString("project_id"),
                        rs.getString("manifest_hash"),
                        com.writing.novel.domain.BookEdition.State.valueOf(rs.getString("state")),
                        Instant.parse(rs.getString("created_at")),
                        rs.getString("approved_at") == null ? null : Instant.parse(rs.getString("approved_at"))));
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return list;
    }

    @Override
    public List<com.writing.novel.domain.BookEdition.EditionChapter> findBookEditionChapters(
            String editionId) {
        List<com.writing.novel.domain.BookEdition.EditionChapter> list = new ArrayList<>();
        try (Connection conn = cp.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM book_edition_chapters WHERE edition_id=? ORDER BY chapter_index")) {
            ps.setString(1, editionId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                list.add(new com.writing.novel.domain.BookEdition.EditionChapter(
                        rs.getInt("chapter_index"), rs.getInt("version"),
                        rs.getString("title"), rs.getString("content"),
                        rs.getString("content_hash"), rs.getString("approval_id"),
                        rs.getString("plan_ref")));
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return list;
    }

    @Override
    public void approveBookEdition(String projectId, String editionId, String manifestHash) {
        try (Connection conn = cp.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "UPDATE book_editions SET state='APPROVED', approved_at=? " +
                        "WHERE project_id=? AND edition_id=? AND manifest_hash=? AND state='DRAFT'")) {
            ps.setString(1, Instant.now().toString()); ps.setString(2, projectId);
            ps.setString(3, editionId); ps.setString(4, manifestHash);
            if (ps.executeUpdate() != 1) {
                throw new IllegalStateException("成书版本不存在、已批准或 manifestHash 不匹配");
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    // --- p38-10: 问题裁决存取 ---

    @Override
    public void saveDisposition(com.writing.novel.domain.ReviewDisposition d) {
        try (Connection conn = cp.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO review_dispositions (id, project_id, issue_id, issue_fingerprint, " +
                        "chapter_index, chapter_version, content_hash, action, reason, " +
                        "defer_until_chapter, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, d.getId()); ps.setString(2, d.getProjectId());
            ps.setString(3, d.getIssueId()); ps.setString(4, d.getIssueFingerprint());
            ps.setInt(5, d.getIdentity().chapterIndex()); ps.setInt(6, d.getIdentity().version());
            ps.setString(7, d.getIdentity().contentHash());
            ps.setString(8, d.getAction().name()); ps.setString(9, d.getReason());
            if (d.getDeferUntilChapter() != null) ps.setInt(10, d.getDeferUntilChapter());
            else ps.setNull(10, Types.NULL);
            ps.setString(11, d.getCreatedAt().toString());
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    @Override
    public List<com.writing.novel.domain.ReviewDisposition> findDispositions(
            String projectId, int chapterIndex, int chapterVersion, String contentHash) {
        List<com.writing.novel.domain.ReviewDisposition> list = new ArrayList<>();
        String sql = "SELECT * FROM review_dispositions WHERE project_id=? " +
                "AND chapter_index=? AND chapter_version=? AND content_hash=?";
        try (Connection conn = cp.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId); ps.setInt(2, chapterIndex);
            ps.setInt(3, chapterVersion); ps.setString(4, contentHash);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                list.add(new com.writing.novel.domain.ReviewDisposition(
                        rs.getString("id"), rs.getString("project_id"),
                        rs.getString("issue_id"), rs.getString("issue_fingerprint"),
                        new ChapterApproval.Identity(projectId, rs.getInt("chapter_index"),
                                rs.getInt("chapter_version"), rs.getString("content_hash")),
                        com.writing.novel.domain.ReviewDisposition.Action.valueOf(rs.getString("action")),
                        rs.getString("reason"),
                        rs.getObject("defer_until_chapter") == null ? null : rs.getInt("defer_until_chapter"),
                        Instant.parse(rs.getString("created_at"))));
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return list;
    }

    @Override
    public boolean hasValidDisposition(String projectId, String issueId,
                                       ChapterApproval.Identity identity) {
        try (Connection conn = cp.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM review_dispositions WHERE project_id=? AND issue_id=? " +
                        "AND chapter_index=? AND chapter_version=? AND content_hash=? " +
                        "AND action IN ('FALSE_POSITIVE','EXCEPTION')")) {
            ps.setString(1, projectId); ps.setString(2, issueId);
            ps.setInt(3, identity.chapterIndex()); ps.setInt(4, identity.version());
            ps.setString(5, identity.contentHash());
            ResultSet rs = ps.executeQuery();
            rs.next();
            return rs.getInt(1) > 0;
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    // --- p38-02: 计划变更持久存取与原子确认 ---

    @Override
    public void savePlanChange(com.writing.novel.domain.PlanChange planChange) {
        try (Connection conn = cp.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO plan_changes (preview_id, project_id, source_plan_hash, target_chapters, " +
                        "written_boundary, affected_promises_json, pruned_chapters_json, status, created_at) " +
                        "VALUES (?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, planChange.getPreviewId()); ps.setString(2, planChange.getProjectId());
            ps.setString(3, planChange.getSourcePlanHash()); ps.setInt(4, planChange.getTargetChapters());
            ps.setInt(5, planChange.getWrittenBoundary());
            ps.setString(6, planChange.getAffectedPromises().toString());
            ps.setString(7, planChange.getPrunedChapters().toString());
            ps.setString(8, planChange.getStatus().name());
            ps.setString(9, planChange.getCreatedAt().toString());
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    @Override
    public java.util.Optional<com.writing.novel.domain.PlanChange> findPlanChange(
            String projectId, String previewId) {
        try (Connection conn = cp.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM plan_changes WHERE project_id=? AND preview_id=?")) {
            ps.setString(1, projectId); ps.setString(2, previewId);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return java.util.Optional.empty();
            String promisesJson = rs.getString("affected_promises_json");
            String prunedJson = rs.getString("pruned_chapters_json");
            com.writing.novel.domain.PlanChange pc = new com.writing.novel.domain.PlanChange(
                    rs.getString("preview_id"), rs.getString("project_id"),
                    rs.getString("source_plan_hash"), rs.getInt("target_chapters"),
                    rs.getInt("written_boundary"),
                    List.of(), // simplified: parse from JSON in production
                    List.of(), // pruned chapters parsed from JSON
                    com.writing.novel.domain.PlanChange.Status.valueOf(rs.getString("status")),
                    Instant.parse(rs.getString("created_at")));
            return java.util.Optional.of(pc);
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    @Override
    public PlanChange commitPlanChange(String projectId, String previewId, String sourcePlanHash,
            List<PlanChange.PromiseDecision> decisions, String key) {
        return runInTransaction(conn -> commitPlanChange(conn, projectId, previewId, sourcePlanHash, decisions));
    }

    private PlanChange commitPlanChange(Connection conn, String projectId, String previewId,
            String sourcePlanHash, List<PlanChange.PromiseDecision> decisions) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM plan_changes WHERE project_id=? AND preview_id=?")) {
            ps.setString(1, projectId); ps.setString(2, previewId);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) throw new IllegalArgumentException("计划变更不存在: " + previewId);
            String storedHash = rs.getString("source_plan_hash");
            if (!storedHash.equals(sourcePlanHash)) {
                throw new IllegalStateException("计划已变化，旧预览失效（planHash 不匹配）");
            }
            String status = rs.getString("status");
            if ("CONFIRMED".equals(status)) {
                throw new IllegalStateException("计划变更已确认");
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
        // Save decisions
        for (var decision : decisions) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO promise_decisions (id, plan_change_id, promise_id, action, " +
                            "target_chapter, reason, created_at) VALUES (?,?,?,?,?,?,?)")) {
                ps.setString(1, decision.id());
                ps.setString(2, previewId);
                ps.setString(3, decision.promiseId());
                ps.setString(4, decision.action());
                if (decision.targetChapter() != null) ps.setInt(5, decision.targetChapter());
                else ps.setNull(5, Types.NULL);
                ps.setString(6, decision.reason());
                ps.setString(7, Instant.now().toString());
                ps.executeUpdate();
            } catch (SQLException e) { throw new RuntimeException(e); }
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE plan_changes SET status='CONFIRMED' WHERE project_id=? AND preview_id=?")) {
            ps.setString(1, projectId); ps.setString(2, previewId);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
        // Same-transaction read: findPlanChange would open a new connection
        // and not see the uncommitted status change
        try (PreparedStatement ps2 = conn.prepareStatement(
                "SELECT * FROM plan_changes WHERE project_id=? AND preview_id=?")) {
            ps2.setString(1, projectId); ps2.setString(2, previewId);
            ResultSet rs2 = ps2.executeQuery();
            rs2.next();
            return new com.writing.novel.domain.PlanChange(
                    rs2.getString("preview_id"), rs2.getString("project_id"),
                    rs2.getString("source_plan_hash"), rs2.getInt("target_chapters"),
                    rs2.getInt("written_boundary"), List.of(), List.of(),
                    com.writing.novel.domain.PlanChange.Status.CONFIRMED,
                    Instant.parse(rs2.getString("created_at")));
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    @Override
    public PlanChange commitPlanEdition(String projectId, String previewId, String sourcePlanHash,
            WritingPreferences preferences, Outline outline, List<PlanChange.PromiseDecision> decisions,
            String requestKey) {
        if (!projectId.equals(preferences.getProjectId()) || !projectId.equals(outline.getProjectId())) {
            throw new IllegalArgumentException("计划对象不属于该项目");
        }
        return runInTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT source_plan_hash, target_chapters, written_boundary, status FROM plan_changes WHERE project_id=? AND preview_id=?")) {
                ps.setString(1, projectId); ps.setString(2, previewId);
                ResultSet rs = ps.executeQuery();
                if (!rs.next()) throw new IllegalArgumentException("计划变更凭证不存在");
                if (!"PENDING".equals(rs.getString("status"))
                        || preferences.getTargetChapters() != rs.getInt("target_chapters")
                        || preferences.getTargetChapters() < rs.getInt("written_boundary")
                        || !sourcePlanHash.equals(rs.getString("source_plan_hash"))) {
                    throw new IllegalStateException("计划预览状态或目标已变化，请重新预览");
                }
            } catch (SQLException e) { throw new RuntimeException(e); }
            if (!sourcePlanHash.equals(currentPlanSourceHash(conn, projectId, preferences.getTargetChapters()))) {
                throw new IllegalStateException("计划来源已变化，旧预览凭证失效，请重新预览");
            }
            saveWritingPreferences(conn, preferences);
            saveOutline(conn, outline);
            // Decisions and CONFIRMED are part of this same transaction, never a nested connection.
            return commitPlanChange(conn, projectId, previewId, sourcePlanHash, decisions);
        });
    }

    /** Same canonical source as PlanEditionService.computeSourcePlanHash, read under the write transaction. */
    private String currentPlanSourceHash(Connection conn, String projectId, int newTarget) {
        int oldTarget = new WritingPreferences(projectId).getTargetChapters();
        int written;
        try {
            try (PreparedStatement ps = conn.prepareStatement("SELECT current_chapter_index FROM projects WHERE id=?")) {
                ps.setString(1, projectId); ResultSet rs = ps.executeQuery();
                if (!rs.next()) throw new IllegalArgumentException("项目不存在");
                written = rs.getInt(1);
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT target_chapters FROM writing_preferences WHERE project_id=?")) {
                ps.setString(1, projectId); ResultSet rs = ps.executeQuery();
                if (rs.next()) oldTarget = rs.getInt(1);
            }
            List<String> entries = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT v.volume_index, cs.chapter_index, cs.title, cs.synopsis FROM volumes v JOIN chapter_synopses cs ON cs.volume_id=v.id WHERE v.project_id=?")) {
                ps.setString(1, projectId); ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    String synopsis = rs.getString("synopsis");
                    entries.add(rs.getInt("volume_index") + ":" + rs.getInt("chapter_index") + ":"
                            + rs.getString("title") + ":" + (synopsis == null ? "" : ReviewFingerprint.sha256Hex12(synopsis)));
                }
            }
            Collections.sort(entries);
            List<String> promises = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, priority, must_resolve, expected_resolve_chapter FROM foreshadowings WHERE project_id=? AND resolved=0")) {
                ps.setString(1, projectId); ResultSet rs = ps.executeQuery();
                while (rs.next()) promises.add(rs.getString("id") + ":" + rs.getString("priority")
                        + ":" + (rs.getInt("must_resolve") != 0) + ":" + rs.getObject("expected_resolve_chapter"));
            }
            Collections.sort(promises);
            return ReviewFingerprint.sha256Hex12("target=" + oldTarget + ";reqTarget=" + newTarget
                    + ";written=" + written + ";outline=" + String.join(",", entries)
                    + ";promises=" + String.join(",", promises));
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    /** 审计 P1-2: 适用 HARD/PREFERENCE 意见中，当前稿没有 APPLIED_SYSTEM/CONFIRMED 证据的列表。 */
    private List<String> selectUnsatisfiedDirectives(Connection conn, String projectId,
                                                     int chapterIndex, int chapterVersion, String contentHash) {
        List<String> unsatisfied = new ArrayList<>();
        String sql = "SELECT d.id, d.content FROM author_directives d " +
                "WHERE d.project_id=? AND d.enabled=1 AND d.status != 'DISMISSED' " +
                "AND d.kind IN ('HARD','PREFERENCE') " +
                "AND (d.from_chapter IS NULL OR d.from_chapter <= ?) " +
                "AND (d.to_chapter IS NULL OR d.to_chapter >= ?) " +
                "AND NOT EXISTS (SELECT 1 FROM directive_evidence e " +
                "  WHERE e.directive_id = d.id AND e.project_id = d.project_id AND e.directive_revision = d.revision AND e.chapter_index = ? " +
                "  AND e.chapter_version = ? AND e.content_hash = ? AND e.status IN ('APPLIED_SYSTEM','CONFIRMED'))";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId);
            ps.setInt(2, chapterIndex);
            ps.setInt(3, chapterIndex);
            ps.setInt(4, chapterIndex);
            ps.setInt(5, chapterVersion);
            ps.setString(6, contentHash);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                unsatisfied.add(rs.getString("content"));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return unsatisfied;
    }

    /** 审计 P1-2: 该章未解决的 HIGH 问题（无有效 FALSE_POSITIVE/EXCEPTION 裁决）。 */
    private List<String> selectBlockingHighIssues(Connection conn, ChapterApproval.Identity identity) {
        List<String> blocking = new ArrayList<>();
        String sql = "SELECT i.id, i.description FROM continuity_issues i " +
                "WHERE i.project_id=? AND i.detected_at_chapter=? AND i.resolved=0 " +
                "AND i.severity='HIGH' " +
                "AND NOT EXISTS (SELECT 1 FROM review_dispositions rd " +
                "  WHERE rd.issue_id = i.id AND rd.project_id = i.project_id AND rd.chapter_index = ? AND rd.chapter_version = ? AND rd.content_hash = ? AND rd.action IN ('FALSE_POSITIVE','EXCEPTION'))";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, identity.projectId());
            ps.setInt(2, identity.chapterIndex());
            ps.setInt(3, identity.chapterIndex());
            ps.setInt(4, identity.version());
            ps.setString(5, identity.contentHash());
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                blocking.add(rs.getString("description"));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return blocking;
    }

    /** 审计 P1-1: 补偿回退——CONFIRMED 回退为 PENDING 并删除已写入的 decisions。 */
    @Override
    public void reopenPlanChange(String projectId, String previewId) {
        runInTransaction(conn -> {
            try (PreparedStatement del = conn.prepareStatement(
                    "DELETE FROM promise_decisions WHERE plan_change_id=?")) {
                del.setString(1, previewId);
                del.executeUpdate();
            } catch (SQLException e) { throw new RuntimeException(e); }
            try (PreparedStatement upd = conn.prepareStatement(
                    "UPDATE plan_changes SET status='PENDING' WHERE project_id=? AND preview_id=?")) {
                upd.setString(1, projectId); upd.setString(2, previewId);
                upd.executeUpdate();
            } catch (SQLException e) { throw new RuntimeException(e); }
            return null;
        });
    }

    // --- p37-07: 意见与证据版本化仓储 ---

    @Override
    public void updateDirective(com.writing.novel.domain.AuthorDirective directive, int expectedRevision) {
        try (Connection conn = cp.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "UPDATE author_directives SET content=?, status=?, updated_at=?, revision=revision+1, " +
                        "from_chapter=?, to_chapter=?, enabled=? " +
                        "WHERE project_id=? AND id=? AND revision=?")) {
            ps.setString(1, directive.getContent());
            ps.setString(2, directive.getStatus().name());
            ps.setString(3, Instant.now().toString());
            if (directive.getFromChapter() != null) ps.setInt(4, directive.getFromChapter());
            else ps.setNull(4, Types.NULL);
            if (directive.getToChapter() != null) ps.setInt(5, directive.getToChapter());
            else ps.setNull(5, Types.NULL);
            ps.setInt(6, directive.isEnabled() ? 1 : 0);
            ps.setString(7, directive.getProjectId());
            ps.setString(8, directive.getId());
            ps.setInt(9, expectedRevision);
            if (ps.executeUpdate() != 1) {
                throw new IllegalStateException("意见已变化，请刷新后重试（revision 过期）");
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<com.writing.novel.domain.AuthorDirective> findApplicableDirectives(
            String projectId, int chapterIndex) {
        List<com.writing.novel.domain.AuthorDirective> list = new ArrayList<>();
        String sql = "SELECT * FROM author_directives WHERE project_id=? AND enabled=1 " +
                "AND status != 'DISMISSED' " +
                "AND (from_chapter IS NULL OR from_chapter <= ?) " +
                "AND (to_chapter IS NULL OR to_chapter >= ?) " +
                "AND NOT (kind = 'ONCE' AND EXISTS (SELECT 1 FROM directive_evidence e " +
                "WHERE e.directive_id = author_directives.id AND e.status = 'CONFIRMED')) " +
                "ORDER BY created_at";
        try (Connection conn = cp.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId);
            ps.setInt(2, chapterIndex);
            ps.setInt(3, chapterIndex);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(mapDirective(rs));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return list;
    }

    @Override
    public com.writing.novel.domain.AuthorDirective.Evidence saveDirectiveEvidence(
            String projectId, com.writing.novel.domain.AuthorDirective.Evidence evidence) {
        // 幂等：相同（意见×revision×章×稿×指纹×引用×状态）返回原行
        String find = "SELECT * FROM directive_evidence WHERE directive_id=? AND directive_revision=? " +
                "AND chapter_index=? AND chapter_version=? AND COALESCE(content_hash,'')=? " +
                "AND COALESCE(quote,'')=? AND status=?";
        try (Connection conn = cp.getConnection();
             PreparedStatement findPs = conn.prepareStatement(find)) {
            findPs.setString(1, evidence.directiveId());
            findPs.setInt(2, evidence.directiveRevision());
            findPs.setInt(3, evidence.chapterIndex());
            findPs.setInt(4, evidence.chapterVersion());
            findPs.setString(5, evidence.contentHash() == null ? "" : evidence.contentHash());
            findPs.setString(6, evidence.quote() == null ? "" : evidence.quote());
            findPs.setString(7, evidence.status().name());
            ResultSet rs = findPs.executeQuery();
            if (rs.next()) return mapEvidence(rs);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        String insert = "INSERT INTO directive_evidence (id, directive_id, project_id, directive_revision, " +
                "chapter_index, chapter_version, content_hash, proposal_id, quote, anchor, status, reason, " +
                "created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection conn = cp.getConnection(); PreparedStatement ps = conn.prepareStatement(insert)) {
            ps.setString(1, evidence.id());
            ps.setString(2, evidence.directiveId());
            ps.setString(3, projectId);
            ps.setInt(4, evidence.directiveRevision());
            ps.setInt(5, evidence.chapterIndex());
            ps.setInt(6, evidence.chapterVersion());
            ps.setString(7, evidence.contentHash());
            ps.setString(8, evidence.proposalId());
            ps.setString(9, evidence.quote());
            ps.setString(10, evidence.anchor());
            ps.setString(11, evidence.status().name());
            ps.setString(12, evidence.reason());
            ps.setString(13, Instant.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return evidence;
    }

    private com.writing.novel.domain.AuthorDirective.Evidence mapEvidence(ResultSet rs) throws SQLException {
        return new com.writing.novel.domain.AuthorDirective.Evidence(
                rs.getString("id"), rs.getString("directive_id"), rs.getInt("directive_revision"),
                rs.getInt("chapter_index"), rs.getInt("chapter_version"), rs.getString("content_hash"),
                rs.getString("proposal_id"), rs.getString("quote"), rs.getString("anchor"),
                com.writing.novel.domain.AuthorDirective.EvidenceStatus.valueOf(rs.getString("status")),
                rs.getString("reason"));
    }

    @Override
    public com.writing.novel.domain.AuthorDirective.Evidence confirmDirectiveEvidence(
            String projectId, String evidenceId,
            ChapterApproval.Identity expectedIdentity, int directiveRevision) {
        return runInTransaction(conn -> {
            com.writing.novel.domain.AuthorDirective.Evidence evidence;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM directive_evidence WHERE project_id=? AND id=?")) {
                ps.setString(1, projectId);
                ps.setString(2, evidenceId);
                ResultSet rs = ps.executeQuery();
                if (!rs.next()) throw new IllegalArgumentException("证据不存在: " + evidenceId);
                evidence = mapEvidence(rs);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
            com.writing.novel.domain.AuthorDirective directive;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM author_directives WHERE project_id=? AND id=?")) {
                ps.setString(1, projectId);
                ps.setString(2, evidence.directiveId());
                ResultSet rs = ps.executeQuery();
                if (!rs.next()) throw new IllegalArgumentException("意见不存在");
                directive = mapDirective(rs);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
            // 事务内重读当前章身份：作者必须确认的是当前稿
            Chapter current = selectChapterByIndex(conn, projectId, evidence.chapterIndex());
            if (current == null) throw new IllegalStateException("证据指向的章节不存在");
            ChapterApproval.Identity currentIdentity = ChapterApproval.Identity.of(current);
            if (!currentIdentity.equals(expectedIdentity)) {
                throw new IllegalStateException("请刷新后确认当前稿（章节身份已变化）");
            }
            if (evidence.chapterVersion() != currentIdentity.version()
                    || !currentIdentity.contentHash().equals(evidence.contentHash())) {
                throw new IllegalStateException("旧稿证据不能确认");
            }
            if (directive.getRevision() == null || directive.getRevision() != directiveRevision) {
                throw new IllegalStateException("意见已修订，旧证据不能确认");
            }
            if (evidence.status() != com.writing.novel.domain.AuthorDirective.EvidenceStatus.APPLIED_SYSTEM) {
                throw new IllegalStateException("仅系统已落实（APPLIED_SYSTEM）的证据可确认: "
                        + evidence.status());
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE directive_evidence SET status='CONFIRMED' WHERE id=? AND status='APPLIED_SYSTEM'")) {
                ps.setString(1, evidenceId);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
            return new com.writing.novel.domain.AuthorDirective.Evidence(
                    evidence.id(), evidence.directiveId(), evidence.directiveRevision(),
                    evidence.chapterIndex(), evidence.chapterVersion(), evidence.contentHash(),
                    evidence.proposalId(), evidence.quote(), evidence.anchor(),
                    com.writing.novel.domain.AuthorDirective.EvidenceStatus.CONFIRMED, evidence.reason());
        });
    }

    @Override
    public void revokeDirective(String projectId, String directiveId, String reason, int expectedRevision) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("撤销必须说明理由");
        }
        try (Connection conn = cp.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "UPDATE author_directives SET status='DISMISSED', enabled=0, updated_at=?, " +
                        "revision=revision+1 WHERE project_id=? AND id=? AND revision=?")) {
            ps.setString(1, Instant.now().toString());
            ps.setString(2, projectId);
            ps.setString(3, directiveId);
            ps.setInt(4, expectedRevision);
            if (ps.executeUpdate() != 1) {
                throw new IllegalStateException("意见已变化，撤销未生效（revision 过期）");
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private RevisionProposal selectProposal(Connection conn, String projectId,
                                            String proposalId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM revision_proposals WHERE project_id=? AND id=?")) {
            ps.setString(1, projectId);
            ps.setString(2, proposalId);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? mapProposal(rs) : null;
        }
    }

    @Override
    public ProposalCommitResult commitProposalChapter(String projectId, String proposalId,
                                                      ChapterVersion snapshot, Chapter revised,
                                                      ChapterApproval.Identity expectedIdentity) {
        if (projectId == null || proposalId == null || expectedIdentity == null
                || revised == null || snapshot == null
                || !projectId.equals(expectedIdentity.projectId())) {
            throw new IllegalArgumentException("候选应用请求缺少项目身份或与路径不符");
        }
        try {
            ProposalCommitResult result = runInTransaction(conn -> {
                try {
                    return doCommitProposalChapter(conn, projectId, proposalId, snapshot, revised, expectedIdentity);
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            });
            return result;
        } catch (ProposalSourceConflictException conflict) {
            // 冲突：正文事务已回滚；独立短事务标 STALE，绝不改正文
            markProposalStale(projectId, proposalId);
            throw conflict;
        }
    }

    private ProposalCommitResult doCommitProposalChapter(Connection conn, String projectId,
                                                         String proposalId, ChapterVersion snapshot,
                                                         Chapter revised,
                                                         ChapterApproval.Identity expectedIdentity) throws SQLException {
        RevisionProposal proposal = selectProposal(conn, projectId, proposalId);
        if (proposal == null) throw new IllegalArgumentException("候选不存在: " + proposalId);
        // 双重应用守卫：APPLIED 返回已持久 result，不另增版本
        if (proposal.getStatus() == RevisionProposal.Status.APPLIED) {
            Chapter current = selectChapterByIndex(conn, projectId, proposal.getChapterIndex());
            int version = current != null ? current.getVersion() : proposal.getBaseVersion() + 1;
            return new ProposalCommitResult(true, proposalId, proposal.getResultJson(), version);
        }
        if (proposal.getStatus() != RevisionProposal.Status.READY) {
            throw new IllegalStateException("候选不可应用: " + proposal.getStatus());
        }
        // 应用前先比 source：候选来源指纹与当前稿身份必须一致
        Chapter current = selectChapterByIndex(conn, projectId, proposal.getChapterIndex());
        if (current == null) throw new IllegalArgumentException("章节不存在: " + proposal.getChapterIndex());
        ChapterApproval.Identity currentIdentity = ChapterApproval.Identity.of(current);
        boolean sourceMatches = currentIdentity.version() == proposal.getBaseVersion()
                && currentIdentity.contentHash().equals(proposal.getSourceHash());
        if (!sourceMatches || !currentIdentity.equals(expectedIdentity)) {
            throw new ProposalSourceConflictException(proposalId, currentIdentity);
        }
        // 正文 CAS + 快照 + 屏障重绑/批准失效（p36-07 语义），任一失败整体回滚（候选保持 READY）
        if (!doSaveChapterRevision(conn, snapshot, revised, current.getVersion())) {
            throw new ProposalSourceConflictException(proposalId, currentIdentity);
        }
        String resultJson = "{\"chapterIndex\":" + revised.getChapterIndex()
                + ",\"version\":" + revised.getVersion()
                + ",\"contentHash\":\"" + ChapterApproval.contentHash(revised.getTitle(), revised.getContent())
                + "\"}";
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE revision_proposals SET status='APPLIED', result_json=?, updated_at=? " +
                        "WHERE project_id=? AND id=? AND status='READY'")) {
            ps.setString(1, resultJson);
            ps.setString(2, Instant.now().toString());
            ps.setString(3, projectId);
            ps.setString(4, proposalId);
            if (ps.executeUpdate() != 1) {
                throw new IllegalStateException("候选并发变化，应用已回滚");
            }
        }
        return new ProposalCommitResult(false, proposalId, resultJson, revised.getVersion());
    }

    private Chapter selectChapterByIndex(Connection conn, String projectId, int chapterIndex) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM chapters WHERE project_id=? AND chapter_index=?")) {
            ps.setString(1, projectId);
            ps.setInt(2, chapterIndex);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? mapChapter(rs) : null;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** C1：reviewSetHash=同事务读取的有序待审集合（报告/屏障/章节类检查点/适用意见/有效裁决），JSON 键排序后 SHA-256。 */
    private String computeReviewSetHash(Connection conn, String projectId, int chapterIndex) {
        try {
            String report = selectReportCurrent(conn, projectId, chapterIndex);
            List<String> barriers = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, chapter_index, chapter_version FROM review_barriers " +
                            "WHERE project_id=? AND status='ACTIVE'")) {
                ps.setString(1, projectId);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    barriers.add("barrier:" + rs.getString("id") + "@ch" + rs.getInt("chapter_index")
                            + "v" + rs.getInt("chapter_version"));
                }
            }
            List<String> checkpoints = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, type FROM checkpoints WHERE project_id=? AND status='PENDING' " +
                            "AND type IN ('AFTER_CHAPTER','ON_CONTINUITY_FIX')")) {
                ps.setString(1, projectId);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) checkpoints.add("checkpoint:" + rs.getString("id") + "@" + rs.getString("type"));
            }
            // p38-10: 适用意见（enabled + 范围含该章 + 未撤销）纳入指纹——新增/撤销意见使旧批准失效
            List<String> directives = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, revision FROM author_directives WHERE project_id=? AND enabled=1 " +
                            "AND status != 'DISMISSED' " +
                            "AND (from_chapter IS NULL OR from_chapter <= ?) " +
                            "AND (to_chapter IS NULL OR to_chapter >= ?)")) {
                ps.setString(1, projectId);
                ps.setInt(2, chapterIndex);
                ps.setInt(3, chapterIndex);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    directives.add("directive:" + rs.getString("id") + "@rev"
                            + (rs.getObject("revision") == null ? 1 : rs.getInt("revision")));
                }
            }
            // p38-10: 该章有效裁决纳入指纹——裁决变化使旧批准失效
            List<String> dispositions = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, issue_id, action FROM review_dispositions WHERE project_id=? " +
                            "AND chapter_index=?")) {
                ps.setString(1, projectId);
                ps.setInt(2, chapterIndex);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    dispositions.add("disposition:" + rs.getString("id") + ":" + rs.getString("issue_id")
                            + "@" + rs.getString("action"));
                }
            }
            // p38: 该章有效问题（未解决）纳入指纹
            List<String> issues = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, severity FROM continuity_issues WHERE project_id=? " +
                            "AND detected_at_chapter<=? AND resolved=0")) {
                ps.setString(1, projectId);
                ps.setInt(2, chapterIndex);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    issues.add("issue:" + rs.getString("id") + "@" + rs.getString("severity"));
                }
            }
            Collections.sort(barriers);
            Collections.sort(checkpoints);
            Collections.sort(directives);
            Collections.sort(dispositions);
            Collections.sort(issues);
            StringBuilder canonical = new StringBuilder();
            canonical.append("{\"barriers\":").append(jsonStringArray(barriers))
                    .append(",\"checkpoints\":").append(jsonStringArray(checkpoints))
                    .append(",\"directives\":").append(jsonStringArray(directives))
                    .append(",\"dispositions\":").append(jsonStringArray(dispositions))
                    .append(",\"issues\":").append(jsonStringArray(issues))
                    .append(",\"report\":")
                    .append(report == null ? "null" : "\"" + report + "\"")
                    .append("}");
            return sha256Hex(canonical.toString());
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private String jsonStringArray(List<String> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(values.get(i)).append('"');
        }
        return sb.append(']').toString();
    }

    private String selectReportCurrent(Connection conn, String projectId, int chapterIndex) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, chapter_version, content_hash FROM chapter_reviews " +
                        "WHERE project_id=? AND chapter_index=? ORDER BY created_at DESC, rowid DESC LIMIT 1")) {
            ps.setString(1, projectId);
            ps.setInt(2, chapterIndex);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return null;
            Integer version = rs.getObject("chapter_version") == null ? null : rs.getInt("chapter_version");
            String hash = rs.getString("content_hash");
            return "report:" + rs.getString("id") + "@v" + (version == null ? 0 : version)
                    + ":" + (hash == null ? "-" : hash);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private List<String> selectActiveBarrierIds(Connection conn, String projectId) {
        List<String> ids = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id FROM review_barriers WHERE project_id=? AND status='ACTIVE' ORDER BY chapter_index")) {
            ps.setString(1, projectId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) ids.add(rs.getString(1));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return ids;
    }

    private List<String> selectPendingChapterClassCheckpointIds(Connection conn, String projectId) {
        List<String> ids = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id FROM checkpoints WHERE project_id=? AND status='PENDING' " +
                        "AND type IN ('AFTER_CHAPTER','ON_CONTINUITY_FIX') ORDER BY created_at")) {
            ps.setString(1, projectId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) ids.add(rs.getString(1));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return ids;
    }

    private List<Checkpoint> selectPendingChapterClassCheckpoints(Connection conn, String projectId) {
        List<Checkpoint> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM checkpoints WHERE project_id=? AND status='PENDING' " +
                        "AND type IN ('AFTER_CHAPTER','ON_CONTINUITY_FIX') ORDER BY created_at")) {
            ps.setString(1, projectId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Checkpoint cp = new Checkpoint(rs.getString("project_id"),
                        Checkpoint.Type.valueOf(rs.getString("type")), rs.getString("description"));
                setField(cp, "id", rs.getString("id"));
                setField(cp, "status", Checkpoint.Status.valueOf(rs.getString("status")));
                cp.setPayload(rs.getString("payload"));
                list.add(cp);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return list;
    }

    /** 从 checkpoint payload 的 chapterIndex 定位所属章，无 payload 时回退"第N章"描述前缀。 */
    private Integer checkpointChapterIndex(Checkpoint cp) {
        try {
            if (cp.getPayload() != null && !cp.getPayload().isBlank()) {
                var node = JSON_MAPPER.readTree(cp.getPayload());
                if (node.hasNonNull("chapterIndex")) return node.get("chapterIndex").asInt();
            }
        } catch (Exception ignored) {
            // payload 非法时回退描述解析
        }
        if (cp.getDescription() != null) {
            var matcher = java.util.regex.Pattern.compile("^第(\\d+)章").matcher(cp.getDescription());
            if (matcher.find()) return Integer.parseInt(matcher.group(1));
        }
        return null;
    }

    private ChapterApproval selectApprovalByIdempotencyKey(Connection conn, String projectId,
                                                           String idempotencyKey) {        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM chapter_approvals WHERE project_id=? AND idempotency_key=?")) {
            ps.setString(1, projectId);
            ps.setString(2, idempotencyKey);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? mapApproval(rs) : null;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private ChapterApproval findApprovalForIdentity(Connection conn, String projectId, int chapterIndex,
                                                    int version, String contentHash) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM chapter_approvals WHERE project_id=? AND chapter_index=? " +
                        "AND chapter_version=? AND content_hash=? ORDER BY created_at DESC LIMIT 1")) {
            ps.setString(1, projectId);
            ps.setInt(2, chapterIndex);
            ps.setInt(3, version);
            ps.setString(4, contentHash);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? mapApproval(rs) : null;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private void insertChapterApproval(Connection conn, String approvalId, ChapterApproval.Request request,
                                       String reviewSetHash, Instant createdAt) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO chapter_approvals (id, project_id, chapter_index, chapter_version, " +
                        "content_hash, review_set_hash, idempotency_key, reason, created_at) " +
                        "VALUES (?,?,?,?,?,?,?,?,?)")) {
            ChapterApproval.Identity id = request.identity();
            ps.setString(1, approvalId);
            ps.setString(2, id.projectId());
            ps.setInt(3, id.chapterIndex());
            ps.setInt(4, id.version());
            ps.setString(5, id.contentHash());
            ps.setString(6, reviewSetHash);
            ps.setString(7, request.idempotencyKey());
            ps.setString(8, request.reason());
            ps.setString(9, createdAt.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private ChapterApproval mapApproval(ResultSet rs) throws SQLException {
        ChapterApproval.Identity identity = new ChapterApproval.Identity(
                rs.getString("project_id"), rs.getInt("chapter_index"),
                rs.getInt("chapter_version"), rs.getString("content_hash"));
        return new ChapterApproval(rs.getString("id"), identity, rs.getString("review_set_hash"),
                rs.getString("idempotency_key"), rs.getString("reason"),
                Instant.parse(rs.getString("created_at")));
    }

    private int countPendingCheckpoints(Connection conn, String projectId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM checkpoints WHERE project_id=? AND status='PENDING'")) {
            ps.setString(1, projectId);
            ResultSet rs = ps.executeQuery();
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private int countActiveBarriers(Connection conn, String projectId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM review_barriers WHERE project_id=? AND status='ACTIVE'")) {
            ps.setString(1, projectId);
            ResultSet rs = ps.executeQuery();
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private boolean isProjectPausedAtCheckpoint(Connection conn, String projectId) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT state FROM projects WHERE id=?")) {
            ps.setString(1, projectId);
            ResultSet rs = ps.executeQuery();
            return rs.next() && "PAUSED_AT_CHECKPOINT".equals(rs.getString("state"));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static String sha256Hex(String input) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            final String hexDigits = "0123456789abcdef";
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(hexDigits.charAt((b >> 4) & 0xF));
                hex.append(hexDigits.charAt(b & 0xF));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public boolean updateChapterStatusIfVersion(String projectId, int chapterIndex,
                                                Chapter.Status status, int expectedVersion) {
        // p32-02: 只更新 status/updated_at——版本不匹配（作者已保存）时零写入
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE chapters SET status=?, updated_at=? " +
                             "WHERE project_id=? AND chapter_index=? AND version=?")) {
            ps.setString(1, status.name());
            ps.setString(2, java.time.Instant.now().toString());
            ps.setString(3, projectId);
            ps.setInt(4, chapterIndex);
            ps.setInt(5, expectedVersion);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // --- Task 10: 大纲/摘要/伏笔/风格/连续性/检查点 ---

    @Override
    public void saveOutline(Outline o) {
        // C3/p26-02: 单连接 + 单事务，候选大纲不得留下部分 premise/volume/synopsis。
        runInTransaction(conn -> {
            saveOutline(conn, o);
            return null;
        });
    }

    @Override
    public Optional<StoryFact> replaceOutlineAtomically(Outline outline, StoryFact plotCommitment) {
        return runInTransaction(conn -> {
            saveOutline(conn, outline);
            if (plotCommitment != null) replaceActiveStoryFact(conn, plotCommitment);
            return Optional.ofNullable(plotCommitment);
        });
    }

    private void saveOutline(Connection conn, Outline o) {
        try {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO outlines (project_id, premise) VALUES (?,?) " +
                    "ON CONFLICT(project_id) DO UPDATE SET premise=excluded.premise")) {
                ps.setString(1, o.getProjectId()); ps.setString(2, o.getPremise());
                ps.executeUpdate();
            }
            java.util.Set<String> keptVolumeIds = new java.util.HashSet<>();
            for (Volume v : o.getVolumes()) {
                saveVolume(conn, v);
                keptVolumeIds.add(v.getId());
            }
            if (!keptVolumeIds.isEmpty()) {
                pruneMissingVolumes(conn, o.getProjectId(), keptVolumeIds);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private void replaceActiveStoryFact(Connection conn, StoryFact newer) {
        try {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE story_facts SET status='SUPERSEDED', superseded_by=?, updated_at=? " +
                    "WHERE project_id=? AND fact_type=? AND subject=? AND status='ACTIVE'")) {
                ps.setString(1, newer.getId());
                ps.setString(2, newer.getUpdatedAt().toString());
                ps.setString(3, newer.getProjectId());
                ps.setString(4, newer.getFactType().name());
                ps.setString(5, newer.getSubject());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO story_facts (id,project_id,fact_type,subject,assertion," +
                    "chapter_established,source_chapter_version,importance,status,superseded_by," +
                    "last_validated_chapter,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                ps.setString(1, newer.getId());
                ps.setString(2, newer.getProjectId());
                ps.setString(3, newer.getFactType().name());
                ps.setString(4, newer.getSubject());
                ps.setString(5, newer.getAssertion());
                ps.setInt(6, newer.getChapterEstablished());
                ps.setInt(7, newer.getSourceChapterVersion());
                ps.setInt(8, newer.getImportance());
                ps.setString(9, newer.getStatus().name());
                ps.setString(10, newer.getSupersededBy());
                ps.setInt(11, newer.getLastValidatedChapter());
                ps.setString(12, newer.getCreatedAt().toString());
                ps.setString(13, newer.getUpdatedAt().toString());
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private void saveVolume(Connection conn, Volume v) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO volumes (id,project_id,volume_index,title,summary) VALUES (?,?,?,?,?) " +
                "ON CONFLICT(id) DO UPDATE SET title=excluded.title, summary=excluded.summary")) {
            ps.setString(1, v.getId()); ps.setString(2, v.getProjectId());
            ps.setInt(3, v.getVolumeIndex()); ps.setString(4, v.getTitle());
            ps.setString(5, v.getSummary());
            ps.executeUpdate();
        }
        java.util.Set<String> keptSynopsisIds = new java.util.HashSet<>();
        for (ChapterSynopsis s : v.getSynopses()) {
            saveSynopsis(conn, v.getId(), s);
            keptSynopsisIds.add(s.getId());
        }
        pruneMissingSynopses(conn, v.getId(), keptSynopsisIds);
    }

    private void pruneMissingSynopses(Connection conn, String volumeId, java.util.Set<String> keptIds) throws SQLException {
        if (keptIds.isEmpty()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM chapter_synopses WHERE volume_id=?")) {
                ps.setString(1, volumeId);
                ps.executeUpdate();
            }
            return;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(keptIds.size(), "?"));
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM chapter_synopses WHERE volume_id=? AND id NOT IN (" + placeholders + ")")) {
            ps.setString(1, volumeId);
            int i = 2;
            for (String id : keptIds) {
                ps.setString(i++, id);
            }
            ps.executeUpdate();
        }
    }

    private void pruneMissingVolumes(Connection conn, String projectId, java.util.Set<String> keptIds) throws SQLException {
        String placeholders = String.join(",", java.util.Collections.nCopies(keptIds.size(), "?"));
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM chapter_synopses WHERE volume_id IN (SELECT id FROM volumes WHERE project_id=? AND id NOT IN (" + placeholders + "))")) {
            ps.setString(1, projectId);
            int i = 2;
            for (String id : keptIds) {
                ps.setString(i++, id);
            }
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM volumes WHERE project_id=? AND id NOT IN (" + placeholders + ")")) {
            ps.setString(1, projectId);
            int i = 2;
            for (String id : keptIds) {
                ps.setString(i++, id);
            }
            ps.executeUpdate();
        }
    }

    private void saveSynopsis(Connection conn, String volumeId, ChapterSynopsis s) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO chapter_synopses (id,project_id,volume_id,chapter_index,title,synopsis,writing_notes," +
                "plot_purpose,tension_level,volume_arc_position,cast,beats,climax) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET title=excluded.title, " +
                "synopsis=excluded.synopsis, writing_notes=excluded.writing_notes, " +
                "plot_purpose=excluded.plot_purpose, tension_level=excluded.tension_level, " +
                "volume_arc_position=excluded.volume_arc_position, cast=excluded.cast, " +
                "beats=excluded.beats, climax=excluded.climax")) {
            ps.setString(1, s.getId()); ps.setString(2, s.getProjectId());
            ps.setString(3, volumeId); ps.setInt(4, s.getChapterIndex());
            ps.setString(5, s.getTitle()); ps.setString(6, s.getSynopsis());
            ps.setString(7, s.getWritingNotes());
            ps.setString(8, s.getPlotPurpose());
            ps.setInt(9, s.getTensionLevel());
            ps.setString(10, s.getVolumeArcPosition());
            ps.setString(11, s.getCast());
            ps.setString(12, s.getBeats());
            ps.setString(13, s.getClimax());
            ps.executeUpdate();
        }
    }

    @Override
    public Optional<Outline> findOutlineByProject(String projectId) {
        try (Connection conn = cp.getConnection()) {
            // C3: 3 次查询替代 2+N 次（N=卷数），消除 N+1
            // Query 1: outline premise
            String premise = null;
            try (PreparedStatement ps = conn.prepareStatement("SELECT premise FROM outlines WHERE project_id=?")) {
                ps.setString(1, projectId);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) premise = rs.getString("premise");
            }
            if (premise == null) return Optional.empty(); // 兼容 premise 为 null 的旧库
            Outline o = new Outline(projectId, premise);

            // Query 2: volumes
            java.util.Map<String, Volume> volumeById = new java.util.LinkedHashMap<>();
            try (PreparedStatement vps = conn.prepareStatement(
                    "SELECT * FROM volumes WHERE project_id=? ORDER BY volume_index")) {
                vps.setString(1, projectId);
                ResultSet vrs = vps.executeQuery();
                while (vrs.next()) {
                    Volume v = new Volume(projectId, vrs.getInt("volume_index"),
                            vrs.getString("title"), vrs.getString("summary"));
                    setField(v, "id", vrs.getString("id"));
                    volumeById.put(vrs.getString("id"), v);
                    o.addVolume(v);
                }
            }

            // Query 3: ALL synopses in one JOIN（替代 N 次 per-volume 查询）
            if (!volumeById.isEmpty()) {
                try (PreparedStatement sps = conn.prepareStatement(
                        "SELECT cs.* FROM chapter_synopses cs " +
                        "JOIN volumes v ON cs.volume_id = v.id " +
                        "WHERE v.project_id=? ORDER BY v.volume_index, cs.chapter_index")) {
                    sps.setString(1, projectId);
                    ResultSet srs = sps.executeQuery();
                    while (srs.next()) {
                        String volId = srs.getString("volume_id");
                        Volume v = volumeById.get(volId);
                        if (v == null) continue;
                        ChapterSynopsis s = new ChapterSynopsis(projectId,
                                srs.getInt("chapter_index"), srs.getString("title"),
                                srs.getString("synopsis"));
                        setField(s, "id", srs.getString("id"));
                        s.setWritingNotes(srs.getString("writing_notes"));
                        try { s.setPlotPurpose(srs.getString("plot_purpose")); } catch (Exception ignored) {}
                        try { s.setTensionLevel(srs.getInt("tension_level")); } catch (Exception ignored) {}
                        try { s.setVolumeArcPosition(srs.getString("volume_arc_position")); } catch (Exception ignored) {}
                        try { s.setCast(srs.getString("cast")); } catch (Exception ignored) {}
                        try { s.setBeats(srs.getString("beats")); } catch (Exception ignored) {}
                        try { s.setClimax(srs.getString("climax")); } catch (Exception ignored) {}
                        v.addSynopsis(s);
                    }
                }
            }
            return Optional.of(o);
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    @Override
    public void saveSummary(Summary s) {
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO summaries (id,project_id,from_chapter,to_chapter,content,updated_at," +
                     "source_chapter_version,dirty) VALUES (?,?,?,?,?,?,?,?) " +
                     "ON CONFLICT(id) DO UPDATE SET from_chapter=excluded.from_chapter, " +
                     "to_chapter=excluded.to_chapter, content=excluded.content, updated_at=excluded.updated_at, " +
                     "source_chapter_version=excluded.source_chapter_version, dirty=excluded.dirty")) {
            ps.setString(1, s.getId()); ps.setString(2, s.getProjectId());
            ps.setInt(3, s.getFromChapter()); ps.setInt(4, s.getToChapter());
            ps.setString(5, s.getContent()); ps.setString(6, s.getUpdatedAt().toString());
            ps.setInt(7, s.getSourceChapterVersion());
            ps.setInt(8, s.isDirty() ? 1 : 0);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    @Override
    public List<Summary> findSummariesByProject(String projectId) {
        List<Summary> list = new ArrayList<>();
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM summaries WHERE project_id=? ORDER BY from_chapter")) {
            ps.setString(1, projectId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Summary s = new Summary(projectId, rs.getInt("from_chapter"),
                        rs.getInt("to_chapter"), rs.getString("content"));
                setField(s, "id", rs.getString("id"));
                setField(s, "sourceChapterVersion", rs.getInt("source_chapter_version"));
                setField(s, "dirty", rs.getInt("dirty") != 0);
                list.add(s);
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return list;
    }

    @Override
    public void deleteSummary(String summaryId) {
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM summaries WHERE id=?")) {
            ps.setString(1, summaryId);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    @Override
    public void saveForeshadowing(Foreshadowing f) {
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO foreshadowings (id,project_id,description,planted_context," +
                     "planted_at_chapter,resolved,resolved_at_chapter,updated_at," +
                     "priority,expected_resolve_chapter,must_resolve,resolve_evidence) VALUES (?,?,?,?,?,?,?,?,?,?,?,?) " +
                     "ON CONFLICT(id) DO UPDATE SET description=excluded.description, " +
                     "planted_context=excluded.planted_context, resolved=excluded.resolved, " +
                     "resolved_at_chapter=excluded.resolved_at_chapter, updated_at=excluded.updated_at, " +
                     "priority=excluded.priority, expected_resolve_chapter=excluded.expected_resolve_chapter, " +
                     "must_resolve=excluded.must_resolve, resolve_evidence=excluded.resolve_evidence")) {
            ps.setString(1, f.getId()); ps.setString(2, f.getProjectId());
            ps.setString(3, f.getDescription()); ps.setString(4, f.getPlantedContext());
            ps.setInt(5, f.getPlantedAtChapter());
            ps.setInt(6, f.isResolved() ? 1 : 0);
            if (f.getResolvedAtChapter() != null) ps.setInt(7, f.getResolvedAtChapter());
            else ps.setNull(7, Types.INTEGER);
            ps.setString(8, f.getUpdatedAt().toString());
            ps.setString(9, f.getPriority().name());
            if (f.getExpectedResolveChapter() != null) ps.setInt(10, f.getExpectedResolveChapter());
            else ps.setNull(10, Types.INTEGER);
            ps.setInt(11, f.isMustResolve() ? 1 : 0);
            ps.setString(12, f.getResolveEvidence()); // p32-08
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    @Override
    public List<Foreshadowing> findForeshadowingsByProject(String projectId) {
        List<Foreshadowing> list = new ArrayList<>();
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM foreshadowings WHERE project_id=?")) {
            ps.setString(1, projectId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(mapForeshadowing(rs));
        } catch (SQLException e) { throw new RuntimeException(e); }
        return list;
    }

    @Override
    public List<Foreshadowing> findUnresolvedForeshadowings(String projectId) {
        List<Foreshadowing> list = new ArrayList<>();
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM foreshadowings WHERE project_id=? AND resolved=0 " +
                     "ORDER BY must_resolve DESC, " +
                     "CASE priority WHEN 'HIGH' THEN 0 WHEN 'MEDIUM' THEN 1 ELSE 2 END, " +
                     "planted_at_chapter ASC")) {
            ps.setString(1, projectId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(mapForeshadowing(rs));
        } catch (SQLException e) { throw new RuntimeException(e); }
        return list;
    }

    @Override
    public void saveStyleProfile(StyleProfile p) {
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO style_profiles (project_id,tone,voice,dos_and_donts,sample_chapter_indices,updated_at) " +
                     "VALUES (?,?,?,?,?,?) ON CONFLICT(project_id) DO UPDATE SET tone=excluded.tone, " +
                     "voice=excluded.voice, dos_and_donts=excluded.dos_and_donts, " +
                     "sample_chapter_indices=excluded.sample_chapter_indices, updated_at=excluded.updated_at")) {
            ps.setString(1, p.getProjectId()); ps.setString(2, p.getTone());
            ps.setString(3, p.getVoice()); ps.setString(4, p.getDosAndDonts());
            ps.setString(5, String.join(",", p.getSampleChapterIndices().stream()
                    .map(String::valueOf).toList()));
            ps.setString(6, p.getUpdatedAt().toString());
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    @Override
    public Optional<StyleProfile> findStyleProfileByProject(String projectId) {
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM style_profiles WHERE project_id=?")) {
            ps.setString(1, projectId);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return Optional.empty();
            StyleProfile p = new StyleProfile(projectId);
            p.setTone(rs.getString("tone"));
            p.setVoice(rs.getString("voice"));
            p.setDosAndDonts(rs.getString("dos_and_donts"));
            String indices = rs.getString("sample_chapter_indices");
            if (indices != null && !indices.isEmpty()) {
                for (String s : indices.split(",")) {
                    if (!s.isEmpty()) p.addSampleChapter(Integer.parseInt(s.trim()));
                }
            }
            return Optional.of(p);
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    @Override
    public void saveContinuityIssue(ContinuityIssue i) {
        try (Connection conn = cp.getConnection()) {
            saveContinuityIssue(conn, i);
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    /** B1: 事务内保存连续性问题 */
    public void saveContinuityIssue(Connection conn, ContinuityIssue i) {
        // p32-05: 持久化边界盖章——记录问题针对的章节版本与正文指纹（无章节行为 NULL）
        if (i.getChapterVersion() == null) {
            ChapterBinding binding = currentChapterBinding(conn, i.getProjectId(), i.getDetectedAtChapter());
            if (binding != null) {
                i.setChapterVersion(binding.version());
                i.setContentHash(binding.hash());
            }
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO continuity_issues (id,project_id,detected_at_chapter,type,severity,description,resolved,created_at,chapter_version,content_hash) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET resolved=excluded.resolved")) {
            ps.setString(1, i.getId()); ps.setString(2, i.getProjectId());
            ps.setInt(3, i.getDetectedAtChapter());
            ps.setString(4, i.getType().name()); ps.setString(5, i.getSeverity().name());
            ps.setString(6, i.getDescription());
            ps.setInt(7, i.isResolved() ? 1 : 0);
            ps.setString(8, i.getCreatedAt().toString());
            if (i.getChapterVersion() != null) ps.setInt(9, i.getChapterVersion()); else ps.setNull(9, java.sql.Types.INTEGER);
            ps.setString(10, i.getContentHash());
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    /** p32-05: 审核产物绑定用的章节当前版本与正文指纹。 */
    private static ChapterBinding currentChapterBinding(Connection conn, String projectId, int chapterIndex) {
        try (PreparedStatement q = conn.prepareStatement(
                "SELECT version, content FROM chapters WHERE project_id=? AND chapter_index=?")) {
            q.setString(1, projectId);
            q.setInt(2, chapterIndex);
            try (ResultSet rs = q.executeQuery()) {
                if (rs.next()) {
                    return new ChapterBinding(rs.getInt("version"),
                            ReviewFingerprint.sha256Hex12(rs.getString("content")));
                }
            }
        } catch (SQLException ignored) {}
        return null;
    }

    private record ChapterBinding(int version, String hash) {}

    @Override
    public List<ContinuityIssue> findContinuityIssuesByProject(String projectId) {
        List<ContinuityIssue> list = new ArrayList<>();
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM continuity_issues WHERE project_id=? ORDER BY created_at DESC LIMIT 200")) {
            ps.setString(1, projectId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(mapIssue(rs));
        } catch (SQLException e) { throw new RuntimeException(e); }
        return list;
    }

    @Override
    public List<ContinuityIssue> findUnresolvedIssues(String projectId) {
        List<ContinuityIssue> list = new ArrayList<>();
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM continuity_issues WHERE project_id=? AND resolved=0")) {
            ps.setString(1, projectId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(mapIssue(rs));
        } catch (SQLException e) { throw new RuntimeException(e); }
        return list;
    }

    @Override
    public String saveCheckpoint(Checkpoint c) {
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO checkpoints (id,project_id,type,description,status,created_at,payload) " +
                     "VALUES (?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET status=excluded.status, payload=excluded.payload")) {
            ps.setString(1, c.getId()); ps.setString(2, c.getProjectId());
            ps.setString(3, c.getType().name()); ps.setString(4, c.getDescription());
            ps.setString(5, c.getStatus().name());
            ps.setString(6, c.getCreatedAt().toString());
            ps.setString(7, c.getPayload());
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
        return c.getId();
    }

    @Override
    public Optional<Checkpoint> findCheckpoint(String id) {
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM checkpoints WHERE id=?")) {
            ps.setString(1, id);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? Optional.of(mapCheckpoint(rs)) : Optional.empty();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    @Override
    public List<Checkpoint> findPendingCheckpoints(String projectId) {
        List<Checkpoint> list = new ArrayList<>();
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM checkpoints WHERE project_id=? AND status='PENDING' ORDER BY created_at")) {
            ps.setString(1, projectId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(mapCheckpoint(rs));
        } catch (SQLException e) { throw new RuntimeException(e); }
        return list;
    }

    @Override
    public Optional<Checkpoint> findLatestPendingCheckpoint(String projectId) {
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM checkpoints WHERE project_id=? AND status='PENDING' ORDER BY created_at DESC LIMIT 1")) {
            ps.setString(1, projectId);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? Optional.of(mapCheckpoint(rs)) : Optional.empty();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    // --- 图数据库：实体 CRUD ---

    @Override
    public void saveEntity(Entity e) {
        String sql = "INSERT INTO entities (id,project_id,name,type,aliases,first_seen_chapter,last_seen_chapter,metadata,created_at,updated_at) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET name=excluded.name, type=excluded.type, " +
                "aliases=excluded.aliases, first_seen_chapter=excluded.first_seen_chapter, " +
                "last_seen_chapter=excluded.last_seen_chapter, metadata=excluded.metadata, updated_at=excluded.updated_at";
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, e.getId()); ps.setString(2, e.getProjectId());
            ps.setString(3, e.getName()); ps.setString(4, e.getType().name());
            ps.setString(5, toJsonList(e.getAliases()));
            ps.setInt(6, e.getFirstSeenChapter()); ps.setInt(7, e.getLastSeenChapter());
            ps.setString(8, e.getMetadata());
            ps.setString(9, e.getCreatedAt().toString());
            ps.setString(10, e.getUpdatedAt().toString());
            ps.executeUpdate();
        } catch (SQLException ex) { throw new RuntimeException(ex); }
    }

    @Override
    public Optional<Entity> findEntity(String id) {
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM entities WHERE id=?")) {
            ps.setString(1, id);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? Optional.of(mapEntity(rs)) : Optional.empty();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    @Override
    public Optional<Entity> findEntityByName(String projectId, String name) {
        // 先精确匹配 name，再匹配 aliases JSON（模糊匹配）
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM entities WHERE project_id=? AND (name=? OR aliases LIKE ?)")) {
            ps.setString(1, projectId);
            ps.setString(2, name);
            ps.setString(3, "%\"" + name.replace("\"", "") + "\"%");
            ResultSet rs = ps.executeQuery();
            return rs.next() ? Optional.of(mapEntity(rs)) : Optional.empty();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    @Override
    public List<Entity> findEntitiesByProject(String projectId) {
        List<Entity> list = new ArrayList<>();
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM entities WHERE project_id=? ORDER BY last_seen_chapter DESC")) {
            ps.setString(1, projectId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(mapEntity(rs));
        } catch (SQLException e) { throw new RuntimeException(e); }
        return list;
    }

    @Override
    public List<Entity> findEntitiesInChapterRange(String projectId, int fromChapter, int toChapter) {
        List<Entity> list = new ArrayList<>();
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM entities WHERE project_id=? AND last_seen_chapter>=? AND last_seen_chapter<=? " +
                     "ORDER BY last_seen_chapter DESC")) {
            ps.setString(1, projectId);
            ps.setInt(2, fromChapter);
            ps.setInt(3, toChapter);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(mapEntity(rs));
        } catch (SQLException e) { throw new RuntimeException(e); }
        return list;
    }

    // --- 图数据库：关系边 CRUD ---

    @Override
    public void saveEntityEdge(EntityEdge edge) {
        String sql = "INSERT INTO entity_edges (id,project_id,source_id,target_id,relationship,weight,metadata,created_at," +
                "valid_from_chapter,valid_to_chapter) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET " +
                "relationship=excluded.relationship, weight=excluded.weight, metadata=excluded.metadata, " +
                "valid_from_chapter=excluded.valid_from_chapter, valid_to_chapter=excluded.valid_to_chapter";
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, edge.getId()); ps.setString(2, edge.getProjectId());
            ps.setString(3, edge.getSourceId()); ps.setString(4, edge.getTargetId());
            ps.setString(5, edge.getRelationship().name());
            ps.setDouble(6, edge.getWeight());
            ps.setString(7, edge.getMetadata());
            ps.setString(8, edge.getCreatedAt().toString());
            ps.setInt(9, edge.getValidFromChapter());
            ps.setInt(10, edge.getValidToChapter());
            ps.executeUpdate();
        } catch (SQLException ex) { throw new RuntimeException(ex); }
    }

    @Override
    public List<EntityEdge> findEdgesBySource(String entityId) {
        List<EntityEdge> list = new ArrayList<>();
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM entity_edges WHERE source_id=?")) {
            ps.setString(1, entityId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(mapEdge(rs));
        } catch (SQLException e) { throw new RuntimeException(e); }
        return list;
    }

    @Override
    public List<EntityEdge> findEdgesByTarget(String entityId) {
        List<EntityEdge> list = new ArrayList<>();
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM entity_edges WHERE target_id=?")) {
            ps.setString(1, entityId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(mapEdge(rs));
        } catch (SQLException e) { throw new RuntimeException(e); }
        return list;
    }

    @Override
    public List<EntityEdge> findEdgesByProject(String projectId) {
        List<EntityEdge> list = new ArrayList<>();
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM entity_edges WHERE project_id=? LIMIT 500")) {
            ps.setString(1, projectId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(mapEdge(rs));
        } catch (SQLException e) { throw new RuntimeException(e); }
        return list;
    }

    /** p10-w3-03: 物理删除过期实体边（已失效 validTo<beforeChapter），返回删除数 */
    @Override
    public int deleteExpiredEdges(String projectId, int beforeChapter) {
        String sql = "DELETE FROM entity_edges WHERE project_id=? AND valid_to_chapter>0 AND valid_to_chapter<?";
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId);
            ps.setInt(2, beforeChapter);
            return ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    /** p10-w3-03: 归档旧的非 HIGH 连续性问题（resolved=1），返回归档数 */
    @Override
    public int archiveOldLowSeverityIssues(String projectId, int beforeChapter) {
        String sql = "UPDATE continuity_issues SET resolved=1 WHERE project_id=? AND resolved=0 "
                + "AND severity!='HIGH' AND detected_at_chapter<?";
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId);
            ps.setInt(2, beforeChapter);
            return ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    /** p16-01: 老化归档更早的未复发 HIGH（resolved=1），防止 400 章尺度下历史 HIGH 无界累积锁死 FINAL 门禁 */
    @Override
    public int archiveAgedHighIssues(String projectId, int beforeChapter) {
        String sql = "UPDATE continuity_issues SET resolved=1 WHERE project_id=? AND resolved=0 "
                + "AND severity='HIGH' AND detected_at_chapter<?";
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId);
            ps.setInt(2, beforeChapter);
            return ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    /** p11-q0-06: 物理删除已回收（RESOLVED）叙事线索，避免 narrative_threads 表无界增长 */
    @Override
    public int deleteResolvedNarrativeThreads(String projectId) {
        String sql = "DELETE FROM narrative_threads WHERE project_id=? AND status='RESOLVED'";
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId);
            return ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    /** p11-q0-06: 同项目重名知识条目去重（按 name 分组保留 MIN(id)，删除其余） */
    @Override    public int deleteDuplicateKnowledgeEntries(String projectId) {
        String sql = "DELETE FROM knowledge_entries WHERE project_id=? AND id NOT IN "
                + "(SELECT MIN(id) FROM knowledge_entries WHERE project_id=? GROUP BY name)";
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId);
            ps.setString(2, projectId);
            return ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    /** p17-05: 自定义预设持久化——upsert by name */
    @Override
    public void saveCustomPreset(String name, String contentJson) {
        String sql = "INSERT INTO custom_presets (name, content, updated_at) VALUES (?,?,?) "
                + "ON CONFLICT(name) DO UPDATE SET content=excluded.content, updated_at=excluded.updated_at";
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setString(2, contentJson);
            ps.setString(3, java.time.Instant.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    /** p17-05: 读取全部自定义预设 */
    @Override
    public java.util.Map<String, String> findCustomPresets() {
        java.util.Map<String, String> result = new java.util.LinkedHashMap<>();
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT name, content FROM custom_presets ORDER BY updated_at")) {
            ResultSet rs = ps.executeQuery();
            while (rs.next()) result.put(rs.getString("name"), rs.getString("content"));
        } catch (SQLException e) { throw new RuntimeException(e); }
        return result;
    }

    /** p11-q0-06: 长期悬置（planted<beforeChapter）非 mustResolve 的 LOW 伏笔标记放弃（resolved=1） */
    @Override
    public int retireStaleForeshadowings(String projectId, int beforeChapter) {        String sql = "UPDATE foreshadowings SET resolved=1, resolved_at_chapter=? "
                + "WHERE project_id=? AND resolved=0 AND must_resolve=0 AND priority='LOW' AND planted_at_chapter<?";
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, beforeChapter);
            ps.setString(2, projectId);
            ps.setInt(3, beforeChapter);
            return ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    /** p11-q1-01: 删除项目全部知识条目（ConceiveGate 重生成前清旧，避免累积） */
    @Override
    public int deleteKnowledgeEntriesByProject(String projectId) {
        String sql = "DELETE FROM knowledge_entries WHERE project_id=?";
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId);
            return ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    // --- EvaluationRecord ---

    @Override
    public void saveEvaluationRecord(EvaluationRecord r) {
        String sql = "INSERT INTO evaluation_records (id,project_id,volume_index,scores_json,overall_score,summary,improvement_notes,created_at) " +
                "VALUES (?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET " +
                "scores_json=excluded.scores_json, overall_score=excluded.overall_score, " +
                "summary=excluded.summary, improvement_notes=excluded.improvement_notes";
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, r.getId()); ps.setString(2, r.getProjectId());
            ps.setInt(3, r.getVolumeIndex());
            ps.setString(4, mapToJson(r.getScores()));
            ps.setInt(5, r.getOverallScore());
            ps.setString(6, r.getSummary());
            ps.setString(7, r.getImprovementNotes());
            ps.setString(8, r.getCreatedAt().toString());
            ps.executeUpdate();
        } catch (SQLException ex) { throw new RuntimeException(ex); }
    }

    @Override
    public Optional<EvaluationRecord> findLatestEvaluation(String projectId) {
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM evaluation_records WHERE project_id=? ORDER BY created_at DESC LIMIT 1")) {
            ps.setString(1, projectId);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? Optional.of(mapEvaluationRecord(rs)) : Optional.empty();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    // --- p11-I1: ChapterReview ---

    @Override
    public void saveChapterReview(ChapterReview r) {
        // p32-05: 持久化边界盖章——评语绑定其针对的章节版本与正文指纹
        if (r.getChapterVersion() == null) {
            try (Connection probe = cp.getConnection()) {
                ChapterBinding binding = currentChapterBinding(probe, r.getProjectId(), r.getChapterIndex());
                if (binding != null) {
                    r.setChapterVersion(binding.version());
                    r.setContentHash(binding.hash());
                }
            } catch (SQLException ignored) {}
        }
        String sql = "INSERT INTO chapter_reviews (id,project_id,chapter_index," +
                "overall_score,engagement,coherence,character_score,hook," +
                "summary,issues,improvement,created_at,chapter_version,content_hash) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET " +
                "overall_score=excluded.overall_score, engagement=excluded.engagement, " +
                "coherence=excluded.coherence, character_score=excluded.character_score, " +
                "hook=excluded.hook, summary=excluded.summary, issues=excluded.issues, " +
                "improvement=excluded.improvement";
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, r.getId()); ps.setString(2, r.getProjectId());
            ps.setInt(3, r.getChapterIndex()); ps.setInt(4, r.getOverallScore());
            ps.setInt(5, r.getEngagement()); ps.setInt(6, r.getCoherence());
            ps.setInt(7, r.getCharacter()); ps.setInt(8, r.getHook());
            ps.setString(9, r.getSummary()); ps.setString(10, r.getIssues());
            ps.setString(11, r.getImprovement()); ps.setString(12, r.getCreatedAt().toString());
            if (r.getChapterVersion() != null) ps.setInt(13, r.getChapterVersion()); else ps.setNull(13, java.sql.Types.INTEGER);
            ps.setString(14, r.getContentHash());
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    @Override
    public Optional<ChapterReview> findChapterReview(String projectId, int chapterIndex) {
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM chapter_reviews WHERE project_id=? AND chapter_index=? " +
                     "ORDER BY created_at DESC LIMIT 1")) {
            ps.setString(1, projectId); ps.setInt(2, chapterIndex);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? Optional.of(mapChapterReview(rs)) : Optional.empty();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    @Override
    public List<ChapterReview> findChapterReviewsByProject(String projectId) {
        List<ChapterReview> list = new ArrayList<>();
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM chapter_reviews WHERE project_id=? ORDER BY chapter_index")) {
            ps.setString(1, projectId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(mapChapterReview(rs));
        } catch (SQLException e) { throw new RuntimeException(e); }
        return list;
    }

    private ChapterReview mapChapterReview(ResultSet rs) throws SQLException {
        ChapterReview r = new ChapterReview(rs.getString("project_id"), rs.getInt("chapter_index"));
        setField(r, "id", rs.getString("id"));
        r.setOverallScore(rs.getInt("overall_score"));
        r.setEngagement(rs.getInt("engagement"));
        r.setCoherence(rs.getInt("coherence"));
        r.setCharacter(rs.getInt("character_score"));
        r.setHook(rs.getInt("hook"));
        r.setSummary(rs.getString("summary"));
        r.setIssues(rs.getString("issues"));
        r.setImprovement(rs.getString("improvement"));
        // p32-05: 绑定版本与指纹（迁移前旧行 NULL）
        try {
            int boundVersion = rs.getInt("chapter_version");
            if (!rs.wasNull()) r.setChapterVersion(boundVersion);
            r.setContentHash(rs.getString("content_hash"));
        } catch (SQLException ignored) {}
        return r;
    }

    // --- p12-K2: WritingTask ---

    @Override
    public void saveWritingTask(WritingTask t) {
        String sql = "INSERT INTO writing_tasks (id,project_id,status,task_type,current_chapter,total_chapters," +
                "chapters_written,total_words,error_message,created_at,updated_at,instruction,stop_requested) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET " +
                "status=excluded.status, task_type=excluded.task_type, " +
                "current_chapter=excluded.current_chapter, " +
                "total_chapters=excluded.total_chapters, chapters_written=excluded.chapters_written, " +
                "total_words=excluded.total_words, error_message=excluded.error_message, " +
                "updated_at=excluded.updated_at, instruction=excluded.instruction, stop_requested=excluded.stop_requested";
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, t.getId()); ps.setString(2, t.getProjectId());
            ps.setString(3, t.getStatus().name()); ps.setString(4, t.getType().name());
            ps.setInt(5, t.getCurrentChapter());
            ps.setInt(6, t.getTotalChapters()); ps.setInt(7, t.getChaptersWritten());
            ps.setInt(8, t.getTotalWords());
            ps.setString(9, t.getErrorMessage());
            ps.setString(10, t.getCreatedAt().toString());
            ps.setString(11, t.getUpdatedAt().toString());
            ps.setString(12, t.getInstruction());
            ps.setInt(13, t.isStopRequested() ? 1 : 0);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    @Override
    public Optional<WritingTask> findWritingTask(String taskId) {
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM writing_tasks WHERE id=?")) {
            ps.setString(1, taskId);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? Optional.of(mapWritingTask(rs)) : Optional.empty();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    @Override
    public Optional<WritingTask> findActiveWritingTask(String projectId) {
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM writing_tasks WHERE project_id=? AND status='RUNNING' " +
                     "ORDER BY updated_at DESC LIMIT 1")) {
            ps.setString(1, projectId);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? Optional.of(mapWritingTask(rs)) : Optional.empty();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    @Override
    public List<WritingTask> findWritingTasksByProject(String projectId) {
        List<WritingTask> list = new ArrayList<>();
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM writing_tasks WHERE project_id=? ORDER BY created_at DESC")) {
            ps.setString(1, projectId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(mapWritingTask(rs));
        } catch (SQLException e) { throw new RuntimeException(e); }
        return list;
    }

    private WritingTask mapWritingTask(ResultSet rs) throws SQLException {
        WritingTask t = new WritingTask(rs.getString("project_id"), rs.getInt("total_chapters"),
                WritingTask.Type.valueOf(rs.getString("task_type")));
        setField(t, "id", rs.getString("id"));
        t.setStatus(WritingTask.Status.valueOf(rs.getString("status")));
        t.setCurrentChapter(rs.getInt("current_chapter"));
        t.setChaptersWritten(rs.getInt("chapters_written"));
        t.setTotalWords(rs.getInt("total_words"));
        String err = rs.getString("error_message");
        t.setErrorMessage(err != null ? err : "");
        t.setInstruction(rs.getString("instruction"));
        t.setStopRequested(rs.getInt("stop_requested") != 0);
        setField(t, "createdAt", java.time.Instant.parse(rs.getString("created_at")));
        setField(t, "updatedAt", java.time.Instant.parse(rs.getString("updated_at")));
        return t;
    }

    // --- WritingPreferences ---

    @Override
    public void saveWritingPreferences(WritingPreferences p) {
        try (Connection conn = cp.getConnection()) {
            saveWritingPreferences(conn, p);
        } catch (SQLException ex) { throw new RuntimeException(ex); }
    }

    private void saveWritingPreferences(Connection conn, WritingPreferences p) {
        String sql = "INSERT INTO writing_preferences (project_id,dialogue_density,enable_dialogue_enforcement," +
                "enable_climax_rotation,enable_character_voice,enable_knowledge_quotes," +
                "preferred_quote_sources,prevent_transition_overlap," +
                "enable_feedback_injection,feedback_window,dialogue_differentiation," +
                "plot_stall_window,character_upgrade_span,min_chapter_length," +
                "max_beats_per_chapter,max_chapter_length,chapter_length_variation,fast_pace_window," +
                "foreshadow_suspense_threshold,target_chapters,layered_outline,cost_budget_yuan," +
                "rewrite_budget,circuit_breaker_threshold,checkpoint_span,updated_at) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(project_id) DO UPDATE SET " +
                "dialogue_density=excluded.dialogue_density, " +
                "enable_dialogue_enforcement=excluded.enable_dialogue_enforcement, " +
                "enable_climax_rotation=excluded.enable_climax_rotation, " +
                "enable_character_voice=excluded.enable_character_voice, " +
                "enable_knowledge_quotes=excluded.enable_knowledge_quotes, " +
                "preferred_quote_sources=excluded.preferred_quote_sources, " +
                "prevent_transition_overlap=excluded.prevent_transition_overlap, " +
                "enable_feedback_injection=excluded.enable_feedback_injection, " +
                "feedback_window=excluded.feedback_window, " +
                "dialogue_differentiation=excluded.dialogue_differentiation, " +
                "plot_stall_window=excluded.plot_stall_window, " +
                "character_upgrade_span=excluded.character_upgrade_span, " +
                "min_chapter_length=excluded.min_chapter_length, " +
                "max_beats_per_chapter=excluded.max_beats_per_chapter, " +
                "max_chapter_length=excluded.max_chapter_length, " +
                "chapter_length_variation=excluded.chapter_length_variation, " +
                "fast_pace_window=excluded.fast_pace_window, " +
                "foreshadow_suspense_threshold=excluded.foreshadow_suspense_threshold, " +
                "target_chapters=excluded.target_chapters, " +
                "layered_outline=excluded.layered_outline, " +
                "cost_budget_yuan=excluded.cost_budget_yuan, " +
                "rewrite_budget=excluded.rewrite_budget, " +
                "circuit_breaker_threshold=excluded.circuit_breaker_threshold, " +
                "checkpoint_span=excluded.checkpoint_span, " +
                "updated_at=excluded.updated_at";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, p.getProjectId());
            ps.setDouble(2, p.getDialogueDensity());
            ps.setInt(3, p.isEnableDialogueEnforcement() ? 1 : 0);
            ps.setInt(4, p.isEnableClimaxRotation() ? 1 : 0);
            ps.setInt(5, p.isEnableCharacterVoice() ? 1 : 0);
            ps.setInt(6, p.isEnableKnowledgeQuotes() ? 1 : 0);
            ps.setString(7, p.getPreferredQuoteSources());
            ps.setInt(8, p.isPreventTransitionOverlap() ? 1 : 0);
            ps.setInt(9, p.isEnableFeedbackInjection() ? 1 : 0);
            ps.setInt(10, p.getFeedbackWindow());
            ps.setString(11, p.getDialogueDifferentiation() != null ? p.getDialogueDifferentiation() : "contrast");
            ps.setInt(12, p.getPlotStallWindow());
            ps.setInt(13, p.getCharacterUpgradeSpan());
            ps.setInt(14, p.getMinChapterLength());
            ps.setInt(15, p.getMaxBeatsPerChapter());
            ps.setInt(16, p.getMaxChapterLength());
            ps.setString(17, p.getChapterLengthVariation() != null ? p.getChapterLengthVariation() : "VARIED");
            ps.setInt(18, p.getFastPaceWindow());
            ps.setInt(19, p.getForeshadowSuspenseThreshold());
            ps.setInt(20, p.getTargetChapters());
            ps.setInt(21, p.isLayeredOutline() ? 1 : 0);
            ps.setDouble(22, p.getCostBudgetYuan());
            ps.setInt(23, p.getRewriteBudget());
            ps.setInt(24, p.getCircuitBreakerThreshold());
            ps.setInt(25, p.getCheckpointSpan());
            ps.setString(26, p.getUpdatedAt().toString());
            ps.executeUpdate();
        } catch (SQLException ex) { throw new RuntimeException(ex); }
    }

    @Override
    public Optional<WritingPreferences> findWritingPreferences(String projectId) {
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM writing_preferences WHERE project_id=?")) {
            ps.setString(1, projectId);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? Optional.of(mapWritingPreferences(rs)) : Optional.empty();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    // --- GenerationLog ---

    @Override
    public void saveGenerationLog(GenerationLog log) {
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO generation_logs (id,project_id,agent_type,summary,key_elements,volume_index,created_at) " +
                     "VALUES (?,?,?,?,?,?,?)")) {
            ps.setString(1, log.getId());
            ps.setString(2, log.getProjectId());
            ps.setString(3, log.getAgentType());
            ps.setString(4, log.getSummary());
            ps.setString(5, log.getKeyElements());
            ps.setInt(6, log.getVolumeIndex());
            ps.setString(7, log.getCreatedAt().toString());
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    @Override
    public List<GenerationLog> findRecentGenerationLogs(String projectId, String agentType, int limit) {
        List<GenerationLog> list = new ArrayList<>();
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM generation_logs WHERE project_id=? AND agent_type=? ORDER BY created_at DESC LIMIT ?")) {
            ps.setString(1, projectId);
            ps.setString(2, agentType);
            ps.setInt(3, limit);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(mapGenerationLog(rs));
        } catch (SQLException e) { throw new RuntimeException(e); }
        return list;
    }

    @Override
    public List<GenerationLog> findAllGenerationLogs(String projectId) {
        List<GenerationLog> list = new ArrayList<>();
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM generation_logs WHERE project_id=? ORDER BY created_at")) {
            ps.setString(1, projectId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(mapGenerationLog(rs));
        } catch (SQLException e) { throw new RuntimeException(e); }
        return list;
    }

    @Override
    public List<GenerationLog> findGenerationLogsByProject(String projectId) {
        return findAllGenerationLogs(projectId);
    }

    private GenerationLog mapGenerationLog(ResultSet rs) throws SQLException {
        return new GenerationLog(
                rs.getString("project_id"),
                rs.getString("agent_type"),
                rs.getString("summary"),
                rs.getString("key_elements"),
                rs.getInt("volume_index"));
    }

    // --- NarrativeThread ---

    @Override
    public void saveNarrativeThread(NarrativeThread thread) {
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO narrative_threads (id,project_id,name,status,first_seen_chapter," +
                     "last_seen_chapter,suspense_chapters,description,created_at,updated_at) " +
                     "VALUES (?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET " +
                     "status=excluded.status, last_seen_chapter=excluded.last_seen_chapter, " +
                     "suspense_chapters=excluded.suspense_chapters, updated_at=excluded.updated_at")) {
            ps.setString(1, thread.getId());
            ps.setString(2, thread.getProjectId());
            ps.setString(3, thread.getName());
            ps.setString(4, thread.getStatus().name());
            ps.setInt(5, thread.getFirstSeenChapter());
            ps.setInt(6, thread.getLastSeenChapter());
            ps.setInt(7, thread.getSuspenseChapters());
            ps.setString(8, thread.getDescription());
            ps.setString(9, thread.getCreatedAt().toString());
            ps.setString(10, thread.getUpdatedAt().toString());
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    @Override
    public List<NarrativeThread> findAllNarrativeThreads(String projectId) {
        List<NarrativeThread> list = new ArrayList<>();
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM narrative_threads WHERE project_id=? ORDER BY first_seen_chapter LIMIT 100")) {
            ps.setString(1, projectId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(mapNarrativeThread(rs));
        } catch (SQLException e) { throw new RuntimeException(e); }
        return list;
    }

    private NarrativeThread mapNarrativeThread(ResultSet rs) throws SQLException {
        NarrativeThread t = new NarrativeThread(
                rs.getString("project_id"),
                rs.getString("name"),
                rs.getInt("first_seen_chapter"),
                rs.getString("description"));
        setField(t, "id", rs.getString("id"));
        t.setStatus(ThreadStatus.valueOf(rs.getString("status")));
        t.setLastSeenChapter(rs.getInt("last_seen_chapter"));
        t.setSuspenseChapters(rs.getInt("suspense_chapters"));
        return t;
    }

    // --- 新的映射辅助 ---

    private Entity mapEntity(ResultSet rs) throws SQLException {
        Entity e = new Entity(rs.getString("project_id"), rs.getString("name"),
                EntityType.valueOf(rs.getString("type")));
        setField(e, "id", rs.getString("id"));
        String aliasesJson = rs.getString("aliases");
        if (aliasesJson != null && !aliasesJson.isBlank() && !aliasesJson.equals("[]")) {
            e.setAliases(parseJsonList(aliasesJson));
        }
        e.setFirstSeenChapter(rs.getInt("first_seen_chapter"));
        e.setLastSeenChapter(rs.getInt("last_seen_chapter"));
        e.setMetadata(rs.getString("metadata"));
        return e;
    }

    private EntityEdge mapEdge(ResultSet rs) throws SQLException {
        EntityEdge edge = new EntityEdge(rs.getString("project_id"),
                rs.getString("source_id"), rs.getString("target_id"),
                RelationshipType.valueOf(rs.getString("relationship")));
        setField(edge, "id", rs.getString("id"));
        edge.setWeight(rs.getDouble("weight"));
        edge.setMetadata(rs.getString("metadata"));
        try { edge.setValidFromChapter(rs.getInt("valid_from_chapter")); } catch (Exception ignored) {}
        try { edge.setValidToChapter(rs.getInt("valid_to_chapter")); } catch (Exception ignored) {}
        return edge;
    }

    private EvaluationRecord mapEvaluationRecord(ResultSet rs) throws SQLException {
        Map<String, Integer> scores = parseScoreJson(rs.getString("scores_json"));
        return new EvaluationRecord(rs.getString("project_id"), rs.getInt("volume_index"),
                scores, rs.getInt("overall_score"),
                rs.getString("summary"), rs.getString("improvement_notes"));
    }

    private WritingPreferences mapWritingPreferences(ResultSet rs) throws SQLException {
        WritingPreferences p = new WritingPreferences(rs.getString("project_id"));
        p.setDialogueDensity(rs.getDouble("dialogue_density"));
        p.setEnableDialogueEnforcement(rs.getInt("enable_dialogue_enforcement") == 1);
        p.setEnableClimaxRotation(rs.getInt("enable_climax_rotation") == 1);
        p.setEnableCharacterVoice(rs.getInt("enable_character_voice") == 1);
        p.setEnableKnowledgeQuotes(rs.getInt("enable_knowledge_quotes") == 1);
        String qs = rs.getString("preferred_quote_sources");
        p.setPreferredQuoteSources(qs != null ? qs : "[]");
        p.setPreventTransitionOverlap(rs.getInt("prevent_transition_overlap") == 1);
        try { p.setEnableFeedbackInjection(rs.getInt("enable_feedback_injection") == 1); } catch (Exception ignored) { p.setEnableFeedbackInjection(true); }
        try { p.setFeedbackWindow(rs.getInt("feedback_window")); } catch (Exception ignored) { p.setFeedbackWindow(10); }
        try { p.setDialogueDifferentiation(rs.getString("dialogue_differentiation")); } catch (Exception ignored) { p.setDialogueDifferentiation("contrast"); }
        if (p.getDialogueDifferentiation() == null) p.setDialogueDifferentiation("contrast");
        try { p.setPlotStallWindow(rs.getInt("plot_stall_window")); } catch (Exception ignored) { p.setPlotStallWindow(3); }
        try { p.setCharacterUpgradeSpan(rs.getInt("character_upgrade_span")); } catch (Exception ignored) { p.setCharacterUpgradeSpan(2); }
        try { p.setMinChapterLength(rs.getInt("min_chapter_length")); } catch (Exception ignored) { p.setMinChapterLength(2000); }
        try { p.setMaxBeatsPerChapter(rs.getInt("max_beats_per_chapter")); } catch (Exception ignored) { p.setMaxBeatsPerChapter(4); }
        try { p.setMaxChapterLength(rs.getInt("max_chapter_length")); } catch (Exception ignored) { p.setMaxChapterLength(6000); }
        try { p.setChapterLengthVariation(rs.getString("chapter_length_variation")); } catch (Exception ignored) { p.setChapterLengthVariation("VARIED"); }
        if (p.getChapterLengthVariation() == null) p.setChapterLengthVariation("VARIED");
        try { p.setFastPaceWindow(rs.getInt("fast_pace_window")); } catch (Exception ignored) { p.setFastPaceWindow(4); }
        try { p.setForeshadowSuspenseThreshold(rs.getInt("foreshadow_suspense_threshold")); } catch (Exception ignored) { p.setForeshadowSuspenseThreshold(8); }
        try { p.setTargetChapters(rs.getInt("target_chapters")); } catch (Exception ignored) { p.setTargetChapters(24); }
        try { p.setLayeredOutline(rs.getInt("layered_outline") == 1); } catch (Exception ignored) { p.setLayeredOutline(false); }
        try { p.setCostBudgetYuan(rs.getDouble("cost_budget_yuan")); } catch (Exception ignored) { p.setCostBudgetYuan(0); }
        try { p.setRewriteBudget(rs.getInt("rewrite_budget")); } catch (Exception ignored) { p.setRewriteBudget(1); }
        try { p.setCircuitBreakerThreshold(rs.getInt("circuit_breaker_threshold")); } catch (Exception ignored) { p.setCircuitBreakerThreshold(10); }
        try { p.setCheckpointSpan(rs.getInt("checkpoint_span")); } catch (Exception ignored) { p.setCheckpointSpan(1); }
        return p;
    }
    private KnowledgeEntry mapEntry(ResultSet rs) throws SQLException {
        KnowledgeEntry e = new KnowledgeEntry(rs.getString("project_id"),
                rs.getString("type"), rs.getString("name"), rs.getString("content"));
        setField(e, "id", rs.getString("id"));
        setField(e, "version", rs.getInt("version"));
        return e;
    }

    private Character mapCharacter(ResultSet rs) throws SQLException {
        Character c = new Character(rs.getString("project_id"),
                rs.getString("name"), rs.getString("role"));
        setField(c, "id", rs.getString("id"));
        c.setPersonality(rs.getString("personality"));
        c.setAbility(rs.getString("ability"));
        c.setMotivation(rs.getString("motivation"));
        c.setAppearance(rs.getString("appearance"));
        c.setRelationships(rs.getString("relationships"));
        c.setGrowthArc(rs.getString("growth_arc"));
        c.setSpeakingStyle(rs.getString("speaking_style"));
        c.setKnowledgeDomains(rs.getString("knowledge_domains"));
        // p19-01: 存续状态（旧库无列时 null=ACTIVE）
        try {
            String st = rs.getString("status");
            if (st != null && !st.isBlank()) c.setStatus(Character.Status.valueOf(st));
            int dc = rs.getInt("death_chapter");
            if (!rs.wasNull()) c.setDeathChapter(dc);
        } catch (Exception ignored) { }
        // p10-a1: 读取智谋建模字段（兼容旧库）
        try {
            int sl = rs.getInt("strategy_level");
            if (!rs.wasNull()) setField(c, "strategyLevel", sl);
            setField(c, "countermeasures", rs.getString("countermeasures"));
            setField(c, "independentWill", rs.getString("independent_will"));
            setField(c, "blindSpot", rs.getString("blind_spot"));
            setField(c, "arcStage", rs.getString("arc_stage"));
            int lsc = rs.getInt("last_seen_chapter");
            if (!rs.wasNull()) setField(c, "lastSeenChapter", lsc);
        } catch (SQLException ignored) { /* 旧库无此列 */ }
        return c;
    }

    private Chapter mapChapter(ResultSet rs) throws SQLException {
        Chapter c = new Chapter(rs.getString("project_id"),
                rs.getInt("chapter_index"), rs.getString("title"));
        setField(c, "id", rs.getString("id"));
        String content = rs.getString("content");
        if (content != null) c.setContent(content);
        // mc-p1-01: 用 DB 中的真实 version 覆盖（setContent 会 bump version，需修正）
        setField(c, "version", rs.getInt("version"));
        // p10-w1-06: 读取章节状态（兼容旧库无列/无效值，默认 DONE）
        try {
            String st = rs.getString("status");
            if (st != null && !st.isEmpty()) c.setStatus(Chapter.Status.valueOf(st));
        } catch (Exception ignored) { /* 旧库无 status 列 */ }
        // p32-04: 计划出处（迁移前旧库无列时保持 NULL）
        try { c.setPlanRef(rs.getString("plan_ref")); } catch (Exception ignored) {}
        return c;
    }

    private ChapterVersion mapChapterVersion(ResultSet rs) throws SQLException {
        return new ChapterVersion(
                rs.getString("id"),
                rs.getString("project_id"),
                rs.getInt("chapter_index"),
                rs.getInt("version"),
                rs.getString("title"),
                rs.getString("content"),
                rs.getString("source"),
                Instant.parse(rs.getString("created_at")));
    }

    private Foreshadowing mapForeshadowing(ResultSet rs) throws SQLException {
        Foreshadowing f = new Foreshadowing(rs.getString("project_id"),
                rs.getString("description"), rs.getString("planted_context"),
                rs.getInt("planted_at_chapter"));
        setField(f, "id", rs.getString("id"));
        if (rs.getInt("resolved") == 1) f.resolveRaw(rs.getInt("resolved_at_chapter"));
        // p32-08: 回收证据（迁移前旧行 NULL）
        try { f.setResolveEvidence(rs.getString("resolve_evidence")); } catch (SQLException ignored) {}
        // p10-c1: 读取新字段（兼容旧库无列的情况）
        try {
            String pri = rs.getString("priority");
            if (pri != null && !pri.isEmpty()) f.setPriority(Foreshadowing.Priority.valueOf(pri));
            int exp = rs.getInt("expected_resolve_chapter");
            if (!rs.wasNull()) f.setExpectedResolveChapter(exp);
            f.setMustResolve(rs.getInt("must_resolve") == 1);
        } catch (SQLException ignored) { /* 旧库无此列 */ }
        return f;
    }

    private ContinuityIssue mapIssue(ResultSet rs) throws SQLException {
        ContinuityIssue i = new ContinuityIssue(rs.getString("project_id"),
                rs.getInt("detected_at_chapter"),
                ContinuityIssue.Type.valueOf(rs.getString("type")),
                ContinuityIssue.Severity.valueOf(rs.getString("severity")),
                rs.getString("description"));
        setField(i, "id", rs.getString("id"));
        if (rs.getInt("resolved") == 1) i.resolve();
        // p32-05: 绑定版本与指纹（迁移前旧行 NULL）
        try {
            int boundVersion = rs.getInt("chapter_version");
            if (!rs.wasNull()) i.setChapterVersion(boundVersion);
            i.setContentHash(rs.getString("content_hash"));
        } catch (SQLException ignored) {}
        return i;
    }

    private Checkpoint mapCheckpoint(ResultSet rs) throws SQLException {
        Checkpoint c = new Checkpoint(rs.getString("project_id"),
                Checkpoint.Type.valueOf(rs.getString("type")),
                rs.getString("description"));
        setField(c, "id", rs.getString("id"));
        c.setPayload(rs.getString("payload"));
        switch (Checkpoint.Status.valueOf(rs.getString("status"))) {
            case APPROVED -> c.approve();
            case MODIFIED -> c.modify();
            case REJECTED -> c.reject();
        }
        return c;
    }

    /** 用反射设置 id（领域对象构造时生成，从库读出需覆盖） */
    @SuppressWarnings("unchecked")
    private static void setField(Object target, String fieldName, Object value) {
        try {
            java.lang.reflect.Field f = target.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("无法设置字段 " + fieldName, e);
        }
    }

    // --- JSON 工具方法 ---

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private static String toJsonList(List<String> list) {
        if (list == null || list.isEmpty()) return "[]";
        try {
            return JSON_MAPPER.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private static List<String> parseJsonList(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return JSON_MAPPER.readValue(json, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            return new ArrayList<>();
        }
    }

    private static String mapToJson(Map<String, Integer> map) {
        if (map == null || map.isEmpty()) return "{}";
        try {
            return JSON_MAPPER.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private static Map<String, Integer> parseScoreJson(String json) {
        if (json == null || json.isBlank()) return new HashMap<>();
        try {
            return JSON_MAPPER.readValue(json, new TypeReference<Map<String, Integer>>() {});
        } catch (JsonProcessingException e) {
            return new HashMap<>();
        }
    }
}
