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

    @Override
    public String saveProject(Project p) {
        String sql = "INSERT INTO projects (id,title,concept,tags,mode,state,current_chapter_index,genre,created_at,updated_at) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?) " +
                "ON CONFLICT(id) DO UPDATE SET title=excluded.title, concept=excluded.concept, " +
                "tags=excluded.tags, mode=excluded.mode, state=excluded.state, " +
                "current_chapter_index=excluded.current_chapter_index, genre=excluded.genre, " +
                "updated_at=excluded.updated_at";
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
                    "summaries","foreshadowings","style_profiles","continuity_issues","checkpoints",
                    "entities","entity_edges","evaluation_records"}) {
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
                "appearance,relationships,growth_arc,speaking_style,knowledge_domains,version,updated_at) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET " +
                "name=excluded.name, role=excluded.role, personality=excluded.personality, " +
                "ability=excluded.ability, motivation=excluded.motivation, appearance=excluded.appearance, " +
                "relationships=excluded.relationships, growth_arc=excluded.growth_arc, " +
                "speaking_style=excluded.speaking_style, knowledge_domains=excluded.knowledge_domains, " +
                "version=excluded.version, updated_at=excluded.updated_at";
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, c.getId()); ps.setString(2, c.getProjectId());
            ps.setString(3, c.getName()); ps.setString(4, c.getRole());
            ps.setString(5, c.getPersonality()); ps.setString(6, c.getAbility());
            ps.setString(7, c.getMotivation()); ps.setString(8, c.getAppearance());
            ps.setString(9, c.getRelationships()); ps.setString(10, c.getGrowthArc());
            ps.setString(11, c.getSpeakingStyle()); ps.setString(12, c.getKnowledgeDomains());
            ps.setInt(13, c.getVersion()); ps.setString(14, c.getUpdatedAt().toString());
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
                "INSERT INTO chapter_synopses (id,project_id,volume_id,chapter_index,title,synopsis,writing_notes," +
                "plot_purpose,tension_level,volume_arc_position) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET title=excluded.title, " +
                "synopsis=excluded.synopsis, writing_notes=excluded.writing_notes, " +
                "plot_purpose=excluded.plot_purpose, tension_level=excluded.tension_level, " +
                "volume_arc_position=excluded.volume_arc_position")) {
            ps.setString(1, s.getId()); ps.setString(2, s.getProjectId());
            ps.setString(3, volumeId); ps.setInt(4, s.getChapterIndex());
            ps.setString(5, s.getTitle()); ps.setString(6, s.getSynopsis());
            ps.setString(7, s.getWritingNotes());
            ps.setString(8, s.getPlotPurpose());
            ps.setInt(9, s.getTensionLevel());
            ps.setString(10, s.getVolumeArcPosition());
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
                            try { s.setPlotPurpose(srs.getString("plot_purpose")); } catch (Exception ignored) {}
                            try { s.setTensionLevel(srs.getInt("tension_level")); } catch (Exception ignored) {}
                            try { s.setVolumeArcPosition(srs.getString("volume_arc_position")); } catch (Exception ignored) {}
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
                     "SELECT * FROM entity_edges WHERE project_id=?")) {
            ps.setString(1, projectId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(mapEdge(rs));
        } catch (SQLException e) { throw new RuntimeException(e); }
        return list;
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

    // --- WritingPreferences ---

    @Override
    public void saveWritingPreferences(WritingPreferences p) {
        String sql = "INSERT INTO writing_preferences (project_id,dialogue_density,enable_dialogue_enforcement," +
                "enable_climax_rotation,enable_character_voice,enable_knowledge_quotes," +
                "preferred_quote_sources,prevent_transition_overlap," +
                "enable_feedback_injection,feedback_window,dialogue_differentiation," +
                "plot_stall_window,character_upgrade_span,updated_at) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(project_id) DO UPDATE SET " +
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
            ps.setString(14, p.getUpdatedAt().toString());
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
                     "SELECT * FROM narrative_threads WHERE project_id=? ORDER BY first_seen_chapter")) {
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
