package com.writing.novel.persistence.sqlite;

import com.writing.novel.domain.AuthorIntent;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * NCS-003: 创作约定（AuthorIntent）的 append-only 版本化持久化。
 * (project_id, revision) 为主键；保存总是追加新修订，历史不可变。
 */
public class AuthorIntentRepository {

    private final ConnectionProvider cp;

    public AuthorIntentRepository(ConnectionProvider cp) {
        this.cp = cp;
    }

    /** 最新修订；无任何修订时返回 empty（调用方以修订 0 的空契约表达） */
    public Optional<AuthorIntent> findLatest(String projectId) {
        String sql = "SELECT * FROM author_intents WHERE project_id=? ORDER BY revision DESC LIMIT 1";
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? Optional.of(map(rs)) : Optional.empty();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** 全部修订，旧→新 */
    public List<AuthorIntent> history(String projectId) {
        String sql = "SELECT * FROM author_intents WHERE project_id=? ORDER BY revision ASC";
        List<AuthorIntent> list = new ArrayList<>();
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(map(rs));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        list.sort(Comparator.comparingInt(AuthorIntent::getRevision));
        return list;
    }

    /** 追加新修订（revision = 当前最大 + 1），返回已保存对象 */
    public AuthorIntent append(String projectId, AuthorIntent intent) {
        int next = findLatest(projectId).map(AuthorIntent::getRevision).orElse(0) + 1;
        intent.setProjectId(projectId);
        intent.setRevision(next);
        intent.setUpdatedAt(Instant.now().toString());
        String sql = "INSERT INTO author_intents (project_id, revision, premise, genre_expectation, " +
                "themes, pov, language_samples, length_range, autonomy, unwanted_patterns, updated_at) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId);
            ps.setInt(2, next);
            ps.setString(3, intent.getPremise());
            ps.setString(4, intent.getGenreExpectation());
            ps.setString(5, intent.getThemes());
            ps.setString(6, intent.getPov());
            ps.setString(7, intent.getLanguageSamples());
            ps.setString(8, intent.getLengthRange());
            ps.setString(9, intent.getAutonomy());
            ps.setString(10, intent.getUnwantedPatterns());
            ps.setString(11, intent.getUpdatedAt());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return intent;
    }

    private AuthorIntent map(ResultSet rs) throws SQLException {
        AuthorIntent intent = new AuthorIntent();
        intent.setProjectId(rs.getString("project_id"));
        intent.setRevision(rs.getInt("revision"));
        intent.setPremise(orEmpty(rs.getString("premise")));
        intent.setGenreExpectation(orEmpty(rs.getString("genre_expectation")));
        intent.setThemes(orEmpty(rs.getString("themes")));
        intent.setPov(orEmpty(rs.getString("pov")));
        intent.setLanguageSamples(orEmpty(rs.getString("language_samples")));
        intent.setLengthRange(orEmpty(rs.getString("length_range")));
        intent.setAutonomy(orEmpty(rs.getString("autonomy")));
        intent.setUnwantedPatterns(orEmpty(rs.getString("unwanted_patterns")));
        intent.setUpdatedAt(orEmpty(rs.getString("updated_at")));
        return intent;
    }

    private static String orEmpty(String v) {
        return v == null ? "" : v;
    }
}
