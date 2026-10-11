package com.writing.novel.persistence.sqlite;

import com.writing.novel.domain.AssertionSupport;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** NCS-052: 支持组持久化（assertion_supports 表，追加式——历史不可变） */
public class AssertionSupportRepository {

    private final ConnectionProvider cp;

    public AssertionSupportRepository(ConnectionProvider cp) {
        this.cp = cp;
    }

    public AssertionSupport save(AssertionSupport s) {
        String sql = "INSERT INTO assertion_supports (project_id, fact_id, link, support_fact_id, " +
                "support_snapshot, status, created_at) VALUES (?,?,?,?,?,?,?)";
        try (Connection conn = cp.getConnection(); PreparedStatement ps = conn.prepareStatement(sql,
                java.sql.Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, s);
            ps.executeUpdate();
            ResultSet keys = ps.getGeneratedKeys();
            if (keys.next()) s.setId(keys.getLong(1));
            return s;
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    /** 撤回（追加语义：UPDATE status，不删行——历史不可变） */
    public void withdraw(Long id) {
        try (Connection conn = cp.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "UPDATE assertion_supports SET status='WITHDRAWN' WHERE id=?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public List<AssertionSupport> findByFact(String projectId, String factId) {
        return query("SELECT * FROM assertion_supports WHERE project_id=? AND fact_id=? ORDER BY id",
                ps -> { try { ps.setString(1, projectId); ps.setString(2, factId); }
                        catch (SQLException e) { throw new RuntimeException(e); } });
    }

    /** 指向某支持项的全部支持行（撤回传播用：谁依赖了它） */
    public List<AssertionSupport> findBySupportFactId(String projectId, String supportFactId) {
        return query("SELECT * FROM assertion_supports WHERE project_id=? AND support_fact_id=? ORDER BY id",
                ps -> { try { ps.setString(1, projectId); ps.setString(2, supportFactId); }
                        catch (SQLException e) { throw new RuntimeException(e); } });
    }

    public Optional<AssertionSupport> findById(Long id) {
        String sql = "SELECT * FROM assertion_supports WHERE id=?";
        List<AssertionSupport> rows = query(sql, ps -> {
            try { ps.setLong(1, id); } catch (SQLException e) { throw new RuntimeException(e); }
        });
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private List<AssertionSupport> query(String sql, Binder binder) {
        List<AssertionSupport> out = new ArrayList<>();
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            binder.bind(ps);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                out.add(new AssertionSupport(rs.getLong("id"), rs.getString("project_id"),
                        rs.getString("fact_id"),
                        AssertionSupport.Link.valueOf(rs.getString("link")),
                        rs.getString("support_fact_id"), rs.getString("support_snapshot"),
                        AssertionSupport.Status.valueOf(rs.getString("status")),
                        java.time.Instant.parse(rs.getString("created_at"))));
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return out;
    }

    private void bind(PreparedStatement ps, AssertionSupport s) throws SQLException {
        ps.setString(1, s.getProjectId());
        ps.setString(2, s.getFactId());
        ps.setString(3, s.getLink().name());
        ps.setString(4, s.getSupportFactId());
        ps.setString(5, s.getSupportSnapshot());
        ps.setString(6, s.getStatus().name());
        ps.setString(7, s.getCreatedAt().toString());
    }
}
