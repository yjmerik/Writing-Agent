package com.writing.novel.persistence.sqlite;

import com.writing.novel.domain.*;
import com.writing.novel.domain.Character;  // 显式导入，避免与 java.lang.Character 歧义
import com.writing.novel.spi.NovelRepository;

import java.sql.*;
import java.time.Instant;
import java.util.*;

public class SqliteNovelRepository implements NovelRepository {

    private final ConnectionProvider cp;

    public SqliteNovelRepository(ConnectionProvider cp) {
        this.cp = cp;
    }

    @Override
    public String saveProject(Project p) {
        String sql = "INSERT INTO projects (id,title,concept,tags,mode,state,current_chapter_index,created_at,updated_at) " +
                "VALUES (?,?,?,?,?,?,?,?,?) " +
                "ON CONFLICT(id) DO UPDATE SET title=excluded.title, concept=excluded.concept, " +
                "tags=excluded.tags, mode=excluded.mode, state=excluded.state, " +
                "current_chapter_index=excluded.current_chapter_index, updated_at=excluded.updated_at";
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, p.getId());
            ps.setString(2, p.getTitle());
            ps.setString(3, p.getConcept());
            ps.setString(4, p.getTags());
            ps.setString(5, p.getMode().name());
            ps.setString(6, p.getState().name());
            ps.setInt(7, p.getCurrentChapterIndex());
            ps.setString(8, p.getCreatedAt().toString());
            ps.setString(9, p.getUpdatedAt().toString());
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
        try (Connection conn = cp.getConnection();
             Statement st = conn.createStatement()) {
            for (String t : new String[]{"projects","knowledge_entries","characters","chapters",
                    "summaries","foreshadowings","style_profiles","continuity_issues","checkpoints"}) {
                st.execute("DELETE FROM " + t + " WHERE project_id='" + id.replace("'","''") + "'");
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
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
                "appearance,relationships,growth_arc,version,updated_at) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET " +
                "name=excluded.name, role=excluded.role, personality=excluded.personality, " +
                "ability=excluded.ability, motivation=excluded.motivation, appearance=excluded.appearance, " +
                "relationships=excluded.relationships, growth_arc=excluded.growth_arc, " +
                "version=excluded.version, updated_at=excluded.updated_at";
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, c.getId()); ps.setString(2, c.getProjectId());
            ps.setString(3, c.getName()); ps.setString(4, c.getRole());
            ps.setString(5, c.getPersonality()); ps.setString(6, c.getAbility());
            ps.setString(7, c.getMotivation()); ps.setString(8, c.getAppearance());
            ps.setString(9, c.getRelationships()); ps.setString(10, c.getGrowthArc());
            ps.setInt(11, c.getVersion()); ps.setString(12, c.getUpdatedAt().toString());
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
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM characters WHERE project_id=?")) {
            ps.setString(1, projectId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(mapCharacter(rs));
        } catch (SQLException e) { throw new RuntimeException(e); }
        return list;
    }

    @Override
    public String saveChapter(Chapter c) {
        String sql = "INSERT INTO chapters (id,project_id,chapter_index,title,content,word_count," +
                "version,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?) " +
                "ON CONFLICT(id) DO UPDATE SET title=excluded.title, content=excluded.content, " +
                "word_count=excluded.word_count, version=excluded.version, updated_at=excluded.updated_at";
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, c.getId()); ps.setString(2, c.getProjectId());
            ps.setInt(3, c.getChapterIndex()); ps.setString(4, c.getTitle());
            ps.setString(5, c.getContent()); ps.setInt(6, c.getWordCount());
            ps.setInt(7, c.getVersion());
            ps.setString(8, c.getCreatedAt().toString());
            ps.setString(9, c.getUpdatedAt().toString());
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
    public List<Chapter> findRecentChapters(String projectId, int count) {
        List<Chapter> all = findChaptersByProject(projectId);
        if (all.size() <= count) return all;
        return all.subList(all.size() - count, all.size());
    }

    // --- Task 10: 大纲/摘要/伏笔/风格/连续性/检查点 ---

    @Override
    public void saveOutline(Outline o) {
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO outlines (project_id, premise) VALUES (?,?) " +
                     "ON CONFLICT(project_id) DO UPDATE SET premise=excluded.premise")) {
            ps.setString(1, o.getProjectId()); ps.setString(2, o.getPremise());
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
        try (Connection conn = cp.getConnection()) {
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
                "INSERT INTO chapter_synopses (id,project_id,volume_id,chapter_index,title,synopsis,writing_notes) " +
                "VALUES (?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET title=excluded.title, " +
                "synopsis=excluded.synopsis, writing_notes=excluded.writing_notes")) {
            ps.setString(1, s.getId()); ps.setString(2, s.getProjectId());
            ps.setString(3, volumeId); ps.setInt(4, s.getChapterIndex());
            ps.setString(5, s.getTitle()); ps.setString(6, s.getSynopsis());
            ps.setString(7, s.getWritingNotes());
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    @Override
    public Optional<Outline> findOutlineByProject(String projectId) {
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT premise FROM outlines WHERE project_id=?")) {
            ps.setString(1, projectId);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return Optional.empty();
            Outline o = new Outline(projectId, rs.getString("premise"));
            try (PreparedStatement vps = conn.prepareStatement(
                    "SELECT * FROM volumes WHERE project_id=? ORDER BY volume_index")) {
                vps.setString(1, projectId);
                ResultSet vrs = vps.executeQuery();
                while (vrs.next()) {
                    Volume v = new Volume(projectId, vrs.getInt("volume_index"),
                            vrs.getString("title"), vrs.getString("summary"));
                    setField(v, "id", vrs.getString("id"));
                    try (PreparedStatement sps = conn.prepareStatement(
                            "SELECT * FROM chapter_synopses WHERE volume_id=? ORDER BY chapter_index")) {
                        sps.setString(1, v.getId());
                        ResultSet srs = sps.executeQuery();
                        while (srs.next()) {
                            ChapterSynopsis s = new ChapterSynopsis(projectId,
                                    srs.getInt("chapter_index"), srs.getString("title"),
                                    srs.getString("synopsis"));
                            setField(s, "id", srs.getString("id"));
                            s.setWritingNotes(srs.getString("writing_notes"));
                            v.addSynopsis(s);
                        }
                    }
                    o.addVolume(v);
                }
            }
            return Optional.of(o);
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    @Override
    public void saveSummary(Summary s) {
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO summaries (id,project_id,from_chapter,to_chapter,content,updated_at) " +
                     "VALUES (?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET from_chapter=excluded.from_chapter, " +
                     "to_chapter=excluded.to_chapter, content=excluded.content, updated_at=excluded.updated_at")) {
            ps.setString(1, s.getId()); ps.setString(2, s.getProjectId());
            ps.setInt(3, s.getFromChapter()); ps.setInt(4, s.getToChapter());
            ps.setString(5, s.getContent()); ps.setString(6, s.getUpdatedAt().toString());
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
                list.add(s);
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return list;
    }

    @Override
    public void saveForeshadowing(Foreshadowing f) {
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO foreshadowings (id,project_id,description,planted_context," +
                     "planted_at_chapter,resolved,resolved_at_chapter,updated_at) VALUES (?,?,?,?,?,?,?,?) " +
                     "ON CONFLICT(id) DO UPDATE SET description=excluded.description, " +
                     "planted_context=excluded.planted_context, resolved=excluded.resolved, " +
                     "resolved_at_chapter=excluded.resolved_at_chapter, updated_at=excluded.updated_at")) {
            ps.setString(1, f.getId()); ps.setString(2, f.getProjectId());
            ps.setString(3, f.getDescription()); ps.setString(4, f.getPlantedContext());
            ps.setInt(5, f.getPlantedAtChapter());
            ps.setInt(6, f.isResolved() ? 1 : 0);
            if (f.getResolvedAtChapter() != null) ps.setInt(7, f.getResolvedAtChapter());
            else ps.setNull(7, Types.INTEGER);
            ps.setString(8, f.getUpdatedAt().toString());
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
                     "SELECT * FROM foreshadowings WHERE project_id=? AND resolved=0")) {
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
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
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
                     "SELECT * FROM continuity_issues WHERE project_id=? ORDER BY created_at DESC")) {
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

    // --- 映射辅助 ---
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
        return c;
    }

    private Chapter mapChapter(ResultSet rs) throws SQLException {
        Chapter c = new Chapter(rs.getString("project_id"),
                rs.getInt("chapter_index"), rs.getString("title"));
        setField(c, "id", rs.getString("id"));
        String content = rs.getString("content");
        if (content != null) c.setContent(content);
        return c;
    }

    private Foreshadowing mapForeshadowing(ResultSet rs) throws SQLException {
        Foreshadowing f = new Foreshadowing(rs.getString("project_id"),
                rs.getString("description"), rs.getString("planted_context"),
                rs.getInt("planted_at_chapter"));
        setField(f, "id", rs.getString("id"));
        if (rs.getInt("resolved") == 1) f.resolve(rs.getInt("resolved_at_chapter"));
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
}
