package com.writing.novel.persistence.sqlite;

import com.writing.novel.agent.CheckResultEnvelope;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * NCS-007: 检查结果信封的持久化。
 * 每次（章 × 检查器）执行追加一行；读取时按 (chapter, checker) 取最新一行——
 * 后续有效检查覆盖旧的 UNKNOWN/INVALID，历史保留可追溯。
 */
public class CheckResultRepository {

    /** (chapterIndex, checker) 的最新检查状态 */
    public record LatestCheck(int chapterIndex, String checker, CheckResultEnvelope.Status status,
                              String reason, String createdAt) {}

    private final ConnectionProvider cp;

    public CheckResultRepository(ConnectionProvider cp) {
        this.cp = cp;
    }

    public void save(String projectId, int chapterIndex, String checker,
                     CheckResultEnvelope.Status status, String reason) {
        // 兼容调用：仅绑定调用时存在的正文；无正文/旧记录绝不当作已覆盖。
        String content = null;
        try (Connection conn = cp.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT content FROM chapters WHERE project_id=? AND chapter_index=?")) {
            ps.setString(1, projectId); ps.setInt(2, chapterIndex);
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) content = rs.getString(1); }
        } catch (SQLException e) { throw new RuntimeException(e); }
        save(projectId, chapterIndex, checker, status, reason, content);
    }

    /** 绑定实际被检查的正文，而非可能已被作者更新的当前稿。 */
    public void save(String projectId, int chapterIndex, String checker,
                     CheckResultEnvelope.Status status, String reason, String checkedContent) {
        String sql = "INSERT INTO check_results (id, project_id, chapter_index, checker, status, reason, created_at, content_fingerprint) " +
                "VALUES (?,?,?,?,?,?,?,?)";
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, java.util.UUID.randomUUID().toString());
            ps.setString(2, projectId);
            ps.setInt(3, chapterIndex);
            ps.setString(4, checker);
            ps.setString(5, status.name());
            ps.setString(6, reason == null ? "" : reason);
            ps.setString(7, Instant.now().toString());
            ps.setString(8, checkedContent == null ? null : com.writing.novel.domain.ChapterApproval.contentHash("", checkedContent));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** 每章每检查器的最新状态（UNKNOWN/INVALID 计为"尚未查清"） */
    public List<LatestCheck> latestStatuses(String projectId) {
        String sql = "SELECT chapter_index, checker, status, reason, created_at FROM check_results " +
                "WHERE project_id=? ORDER BY created_at ASC";
        Map<String, LatestCheck> latest = new HashMap<>();
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                int chapter = rs.getInt("chapter_index");
                String checker = rs.getString("checker");
                latest.put(chapter + "#" + checker, new LatestCheck(chapter, checker,
                        CheckResultEnvelope.Status.valueOf(rs.getString("status")),
                        rs.getString("reason"), rs.getString("created_at")));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return new ArrayList<>(latest.values());
    }

    /** 尚未查清（最新状态为 UNKNOWN/INVALID）的检查数 */
    public long unknownCount(String projectId) {
        return latestStatuses(projectId).stream()
                .filter(c -> c.status() == CheckResultEnvelope.Status.UNKNOWN
                        || c.status() == CheckResultEnvelope.Status.INVALID)
                .count();
    }

    /** NCS-088：单章每检查器的最新状态（批准自动豁免/手动豁免的目标集合） */
    public List<LatestCheck> latestForChapter(String projectId, int chapterIndex) {
        return latestStatuses(projectId).stream()
                .filter(c -> c.chapterIndex() == chapterIndex)
                .toList();
    }
}
