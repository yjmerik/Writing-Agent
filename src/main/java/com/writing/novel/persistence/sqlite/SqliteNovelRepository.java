package com.writing.novel.persistence.sqlite;

import com.writing.novel.domain.*;
import com.writing.novel.domain.Character;
import com.writing.novel.domain.ThreadStatus;  // 显式导入，避免与 java.lang.Character 歧义
import com.writing.novel.domain.WritingPreferences;
import com.writing.novel.spi.NovelRepository;
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
        String sql = "INSERT INTO projects (id,title,concept,tags,mode,state,current_chapter_index,genre,created_at,updated_at,book_summary) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?) " +
                "ON CONFLICT(id) DO UPDATE SET title=excluded.title, concept=excluded.concept, " +
                "tags=excluded.tags, mode=excluded.mode, state=excluded.state, " +
                "current_chapter_index=excluded.current_chapter_index, genre=excluded.genre, " +
                "updated_at=excluded.updated_at, book_summary=excluded.book_summary";
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
        String sql = "INSERT INTO knowledge_entries (id,project_id,type,name,content,version,updated_at) " +
                "VALUES (?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET type=excluded.type, name=excluded.name, " +
                "content=excluded.content, version=excluded.version, updated_at=excluded.updated_at";
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, e.getId()); ps.setString(2, e.getProjectId());
            ps.setString(3, e.getType()); ps.setString(4, e.getName());
            ps.setString(5, e.getContent()); ps.setInt(6, e.getVersion());
            ps.setString(7, e.getUpdatedAt().toString());
            ps.executeUpdate();
        } catch (SQLException ex) { throw new RuntimeException(ex); }
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
                "arc_stage,last_seen_chapter," +
                "version,updated_at) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET " +
                "name=excluded.name, role=excluded.role, personality=excluded.personality, " +
                "ability=excluded.ability, motivation=excluded.motivation, appearance=excluded.appearance, " +
                "relationships=excluded.relationships, growth_arc=excluded.growth_arc, " +
                "speaking_style=excluded.speaking_style, knowledge_domains=excluded.knowledge_domains, " +
                "strategy_level=excluded.strategy_level, countermeasures=excluded.countermeasures, " +
                "independent_will=excluded.independent_will, blind_spot=excluded.blind_spot, " +
                "arc_stage=excluded.arc_stage, last_seen_chapter=excluded.last_seen_chapter, " +
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
            ps.setInt(19, c.getVersion()); ps.setString(20, c.getUpdatedAt().toString());
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
                "version,status,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?) " +
                "ON CONFLICT(id) DO UPDATE SET title=excluded.title, content=excluded.content, " +
                "word_count=excluded.word_count, version=excluded.version, status=excluded.status, updated_at=excluded.updated_at";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, c.getId()); ps.setString(2, c.getProjectId());
            ps.setInt(3, c.getChapterIndex()); ps.setString(4, c.getTitle());
            ps.setString(5, c.getContent()); ps.setInt(6, c.getWordCount());
            ps.setInt(7, c.getVersion());
            ps.setString(8, c.getStatus() != null ? c.getStatus().name() : "DONE");
            ps.setString(9, c.getCreatedAt().toString());
            ps.setString(10, c.getUpdatedAt().toString());
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
                     "SELECT id, project_id, chapter_index, title, word_count, version, created_at, updated_at " +
                     "FROM chapters WHERE project_id=? ORDER BY chapter_index")) {
            ps.setString(1, projectId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Chapter c = new Chapter(rs.getString("project_id"),
                        rs.getInt("chapter_index"), rs.getString("title"));
                setField(c, "id", rs.getString("id"));
                setField(c, "wordCount", rs.getInt("word_count"));
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

    // --- Task 10: 大纲/摘要/伏笔/风格/连续性/检查点 ---

    @Override
    public void saveOutline(Outline o) {
        // C3: 单连接（原两连接 latent bug）
        try (Connection conn = cp.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO outlines (project_id, premise) VALUES (?,?) " +
                    "ON CONFLICT(project_id) DO UPDATE SET premise=excluded.premise")) {
                ps.setString(1, o.getProjectId()); ps.setString(2, o.getPremise());
                ps.executeUpdate();
            }
            for (Volume v : o.getVolumes()) {
                saveVolume(conn, v);
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    private void saveVolume(Connection conn, Volume v) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO volumes (id,project_id,volume_index,title,summary) VALUES (?,?,?,?,?) " +
                "ON CONFLICT(id) DO UPDATE SET title=excluded.title, summary=excluded.summary")) {
            ps.setString(1, v.getId()); ps.setString(2, v.getProjectId());
            ps.setInt(3, v.getVolumeIndex()); ps.setString(4, v.getTitle());
            ps.setString(5, v.getSummary());
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
        for (ChapterSynopsis s : v.getSynopses()) {
            saveSynopsis(conn, v.getId(), s);
        }
    }

    private void saveSynopsis(Connection conn, String volumeId, ChapterSynopsis s) {
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
        } catch (SQLException e) { throw new RuntimeException(e); }
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
                     "priority,expected_resolve_chapter,must_resolve) VALUES (?,?,?,?,?,?,?,?,?,?,?) " +
                     "ON CONFLICT(id) DO UPDATE SET description=excluded.description, " +
                     "planted_context=excluded.planted_context, resolved=excluded.resolved, " +
                     "resolved_at_chapter=excluded.resolved_at_chapter, updated_at=excluded.updated_at, " +
                     "priority=excluded.priority, expected_resolve_chapter=excluded.expected_resolve_chapter, " +
                     "must_resolve=excluded.must_resolve")) {
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
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO continuity_issues (id,project_id,detected_at_chapter,type,severity,description,resolved,created_at) " +
                "VALUES (?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET resolved=excluded.resolved")) {
            ps.setString(1, i.getId()); ps.setString(2, i.getProjectId());
            ps.setInt(3, i.getDetectedAtChapter());
            ps.setString(4, i.getType().name()); ps.setString(5, i.getSeverity().name());
            ps.setString(6, i.getDescription());
            ps.setInt(7, i.isResolved() ? 1 : 0);
            ps.setString(8, i.getCreatedAt().toString());
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

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
                     "INSERT INTO checkpoints (id,project_id,type,description,status,created_at) " +
                     "VALUES (?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET status=excluded.status")) {
            ps.setString(1, c.getId()); ps.setString(2, c.getProjectId());
            ps.setString(3, c.getType().name()); ps.setString(4, c.getDescription());
            ps.setString(5, c.getStatus().name());
            ps.setString(6, c.getCreatedAt().toString());
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
    @Override
    public int deleteDuplicateKnowledgeEntries(String projectId) {
        String sql = "DELETE FROM knowledge_entries WHERE project_id=? AND id NOT IN "
                + "(SELECT MIN(id) FROM knowledge_entries WHERE project_id=? GROUP BY name)";
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId);
            ps.setString(2, projectId);
            return ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    /** p11-q0-06: 长期悬置（planted<beforeChapter）非 mustResolve 的 LOW 伏笔标记放弃（resolved=1） */
    @Override
    public int retireStaleForeshadowings(String projectId, int beforeChapter) {
        String sql = "UPDATE foreshadowings SET resolved=1, resolved_at_chapter=? "
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
        String sql = "INSERT INTO chapter_reviews (id,project_id,chapter_index," +
                "overall_score,engagement,coherence,character_score,hook," +
                "summary,issues,improvement,created_at) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET " +
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
        return r;
    }

    // --- p12-K2: WritingTask ---

    @Override
    public void saveWritingTask(WritingTask t) {
        String sql = "INSERT INTO writing_tasks (id,project_id,status,task_type,current_chapter,total_chapters," +
                "chapters_written,total_words,error_message,created_at,updated_at) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET " +
                "status=excluded.status, task_type=excluded.task_type, " +
                "current_chapter=excluded.current_chapter, " +
                "total_chapters=excluded.total_chapters, chapters_written=excluded.chapters_written, " +
                "total_words=excluded.total_words, error_message=excluded.error_message, " +
                "updated_at=excluded.updated_at";
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
        return t;
    }

    // --- WritingPreferences ---

    @Override
    public void saveWritingPreferences(WritingPreferences p) {
        String sql = "INSERT INTO writing_preferences (project_id,dialogue_density,enable_dialogue_enforcement," +
                "enable_climax_rotation,enable_character_voice,enable_knowledge_quotes," +
                "preferred_quote_sources,prevent_transition_overlap," +
                "enable_feedback_injection,feedback_window,dialogue_differentiation," +
                "plot_stall_window,character_upgrade_span,min_chapter_length," +
                "max_beats_per_chapter,max_chapter_length,chapter_length_variation,fast_pace_window," +
                "foreshadow_suspense_threshold,target_chapters,layered_outline,cost_budget_yuan," +
                "rewrite_budget,circuit_breaker_threshold,updated_at) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(project_id) DO UPDATE SET " +
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
                "updated_at=excluded.updated_at";
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
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
            ps.setString(25, p.getUpdatedAt().toString());
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
        return c;
    }

    private Foreshadowing mapForeshadowing(ResultSet rs) throws SQLException {
        Foreshadowing f = new Foreshadowing(rs.getString("project_id"),
                rs.getString("description"), rs.getString("planted_context"),
                rs.getInt("planted_at_chapter"));
        setField(f, "id", rs.getString("id"));
        if (rs.getInt("resolved") == 1) f.resolve(rs.getInt("resolved_at_chapter"));
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
        return i;
    }

    private Checkpoint mapCheckpoint(ResultSet rs) throws SQLException {
        Checkpoint c = new Checkpoint(rs.getString("project_id"),
                Checkpoint.Type.valueOf(rs.getString("type")),
                rs.getString("description"));
        setField(c, "id", rs.getString("id"));
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
