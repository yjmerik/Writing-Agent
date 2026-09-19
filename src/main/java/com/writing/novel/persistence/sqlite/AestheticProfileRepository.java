package com.writing.novel.persistence.sqlite;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.domain.AestheticProfile;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;

/** NCS-022: 审美档案持久化——项目单行，append-only 修订（历史可查）。 */
public class AestheticProfileRepository {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ConnectionProvider cp;

    public AestheticProfileRepository(ConnectionProvider cp) {
        this.cp = cp;
    }

    public AestheticProfile findLatest(String projectId) {
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM aesthetic_profiles WHERE project_id=? ORDER BY revision DESC LIMIT 1")) {
            ps.setString(1, projectId);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? map(rs) : empty(projectId);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public AestheticProfile append(AestheticProfile profile) {
        AestheticProfile latest = findLatest(profile.getProjectId());
        profile.setRevision(latest.getRevision() + 1);
        profile.setUpdatedAt(Instant.now());
        String sql = "INSERT INTO aesthetic_profiles (id, project_id, revision, liked_snippets, " +
                "disliked_patterns, narrative_distance, language_density, humor_style, " +
                "imagery_preference, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)";
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, profile.getId());
            ps.setString(2, profile.getProjectId());
            ps.setInt(3, profile.getRevision());
            ps.setString(4, MAPPER.writeValueAsString(profile.getLikedSnippets()));
            ps.setString(5, profile.getDislikedPatterns());
            ps.setString(6, profile.getNarrativeDistance());
            ps.setString(7, profile.getLanguageDensity());
            ps.setString(8, profile.getHumorStyle());
            ps.setString(9, profile.getImageryPreference());
            ps.setString(10, profile.getUpdatedAt().toString());
            ps.executeUpdate();
            return profile;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private AestheticProfile empty(String projectId) {
        AestheticProfile p = new AestheticProfile();
        p.setProjectId(projectId);
        p.setRevision(0);
        return p;
    }

    private AestheticProfile map(ResultSet rs) throws Exception {
        AestheticProfile p = new AestheticProfile();
        p.setId(rs.getString("id"));
        p.setProjectId(rs.getString("project_id"));
        p.setRevision(rs.getInt("revision"));
        String snippets = rs.getString("liked_snippets");
        if (snippets != null && !snippets.isBlank()) {
            p.setLikedSnippets(MAPPER.readValue(snippets,
                    new TypeReference<java.util.List<AestheticProfile.LikedSnippet>>() {}));
        }
        p.setDislikedPatterns(rs.getString("disliked_patterns"));
        p.setNarrativeDistance(rs.getString("narrative_distance"));
        p.setLanguageDensity(rs.getString("language_density"));
        p.setHumorStyle(rs.getString("humor_style"));
        p.setImageryPreference(rs.getString("imagery_preference"));
        String updated = rs.getString("updated_at");
        p.setUpdatedAt(updated == null ? Instant.now() : Instant.parse(updated));
        return p;
    }
}
