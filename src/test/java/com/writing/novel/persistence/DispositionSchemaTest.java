package com.writing.novel.persistence;

import com.writing.novel.domain.ChapterApproval;
import com.writing.novel.domain.ReviewDisposition;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** p38-09：裁决 schema——缺理由拒绝、DEFER 缺期限拒绝、双问题不混淆、旧稿裁决不自动有效、迁移幂等。 */
class DispositionSchemaTest {

    @TempDir
    Path tempDir;

    private ChapterApproval.Identity identity(String projectId, int ch, int ver, String hash) {
        return new ChapterApproval.Identity(projectId, ch, ver, hash);
    }

    private ReviewDisposition disposition(String pid, String issueId, int ver, String hash, String action) {
        return new ReviewDisposition(UUID.randomUUID().toString(), pid, issueId, "fp-" + issueId,
                identity(pid, 4, ver, hash), ReviewDisposition.Action.valueOf(action),
                "理由：" + action, action.equals("DEFER") ? 8 : null, Instant.now());
    }

    private void insert(ConnectionProvider cp, ReviewDisposition d) throws SQLException {
        try (Connection conn = cp.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO review_dispositions (id, project_id, issue_id, issue_fingerprint, " +
                        "chapter_index, chapter_version, content_hash, action, reason, defer_until_chapter, " +
                        "created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, d.getId()); ps.setString(2, d.getProjectId());
            ps.setString(3, d.getIssueId()); ps.setString(4, d.getIssueFingerprint());
            ps.setInt(5, d.getIdentity().chapterIndex());
            ps.setInt(6, d.getIdentity().version());
            ps.setString(7, d.getIdentity().contentHash());
            ps.setString(8, d.getAction().name()); ps.setString(9, d.getReason());
            if (d.getDeferUntilChapter() != null) ps.setInt(10, d.getDeferUntilChapter());
            else ps.setNull(10, Types.NULL);
            ps.setString(11, d.getCreatedAt().toString());
            ps.executeUpdate();
        }
    }

    @Test
    void 缺理由拒绝_DEFER缺期限拒绝() {
        assertThrows(IllegalArgumentException.class, () -> new ReviewDisposition(
                "d1", "p1", "i1", "fp", identity("p1", 4, 2, "h"),
                ReviewDisposition.Action.FALSE_POSITIVE, null, null, Instant.now()));
        assertThrows(IllegalArgumentException.class, () -> new ReviewDisposition(
                "d2", "p1", "i1", "fp", identity("p1", 4, 2, "h"),
                ReviewDisposition.Action.DEFER, "延期", null, Instant.now()),
                "DEFER 缺期限");
        assertDoesNotThrow(() -> new ReviewDisposition(
                "d3", "p1", "i1", "fp", identity("p1", 4, 2, "h"),
                ReviewDisposition.Action.DEFER, "延期", 8, Instant.now()));
    }

    @Test
    void 同一句描述的两个问题不混淆() throws SQLException {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("dup.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        // 两个不同 issueId 有相同描述，用不同 issueId + fingerprint 区分
        insert(cp, disposition("p1", "issue-A", 2, "hash-v2", "FALSE_POSITIVE"));
        insert(cp, disposition("p1", "issue-B", 2, "hash-v2", "EXCEPTION"));
        try (Connection conn = cp.getConnection(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM review_dispositions WHERE project_id='p1'")) {
            rs.next();
            assertEquals(2, rs.getInt(1), "同一句描述的两个问题不混淆");
        }
    }

    @Test
    void 旧稿裁决不自动有效() {
        ReviewDisposition old = disposition("p1", "issue-A", 2, "hash-v2", "FALSE_POSITIVE");
        ChapterApproval.Identity currentV3 = identity("p1", 4, 3, "hash-v3");
        assertFalse(old.matchesIdentity(currentV3), "旧稿裁决不自动有效于新稿");
        assertTrue(old.matchesIdentity(identity("p1", 4, 2, "hash-v2")), "同稿匹配");
    }

    @Test
    void 迁移幂等() throws SQLException {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("dual.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        insert(cp, disposition("p1", "issue-A", 2, "h2", "EXCEPTION"));
        new SqliteSchemaInitializer(cp).initialize();
        try (Connection conn = cp.getConnection(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM review_dispositions")) {
            rs.next();
            assertEquals(1, rs.getInt(1), "双迁移不丢行");
        }
    }
}
