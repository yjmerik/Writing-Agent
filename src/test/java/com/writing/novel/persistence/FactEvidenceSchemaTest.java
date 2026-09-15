package com.writing.novel.persistence;

import com.writing.novel.domain.FactEvidence;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** p39-01：事实来源锚点 schema——多人知情、区间、旧事实不伪造来源、双迁移幂等。 */
class FactEvidenceSchemaTest {

    @TempDir
    Path tempDir;

    private ConnectionProvider cp;

    private void insertEvidence(ConnectionProvider cp, String eid, String factId,
                                int ch, int ver, String quote, int from, Integer to) throws SQLException {
        try (Connection conn = cp.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO fact_evidence (id, fact_id, project_id, chapter_index, chapter_version, " +
                        "content_hash, quote, paragraph_index, anchor_hash, valid_from_chapter, " +
                        "valid_to_chapter, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, eid); ps.setString(2, factId); ps.setString(3, "p1");
            ps.setInt(4, ch); ps.setInt(5, ver); ps.setString(6, "hash-" + ver);
            ps.setString(7, quote); ps.setInt(8, 0); ps.setString(9, "a-" + ver);
            ps.setInt(10, from);
            if (to != null) ps.setInt(11, to); else ps.setNull(11, Types.NULL);
            ps.setString(12, Instant.now().toString());
            ps.executeUpdate();
        }
    }

    private void insertObserver(ConnectionProvider cp, String eid, String charId,
                                int learnedAt, int ver) throws SQLException {
        try (Connection conn = cp.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO fact_evidence_observers (id, evidence_id, character_id, " +
                        "learned_at_chapter, chapter_version, quote) VALUES (?,?,?,?,?,?)")) {
            ps.setString(1, java.util.UUID.randomUUID().toString());
            ps.setString(2, eid); ps.setString(3, charId);
            ps.setInt(4, learnedAt); ps.setInt(5, ver); ps.setString(6, null);
            ps.executeUpdate();
        }
    }

    @Test
    void 多人在不同章知情分别记录() throws SQLException {
        cp = new ConnectionProvider(tempDir.resolve("fe.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        insertEvidence(cp, "e1", "f1", 3, 1, "主角拔剑", 3, null);
        insertObserver(cp, "e1", "char-A", 3, 1);
        insertObserver(cp, "e1", "char-B", 5, 2);
        try (Connection conn = cp.getConnection(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT character_id, learned_at_chapter FROM fact_evidence_observers " +
                             "WHERE evidence_id='e1' ORDER BY learned_at_chapter")) {
            assertTrue(rs.next());
            assertEquals("char-A", rs.getString("character_id"));
            assertEquals(3, rs.getInt("learned_at_chapter"));
            assertTrue(rs.next());
            assertEquals("char-B", rs.getString("character_id"));
            assertEquals(5, rs.getInt("learned_at_chapter"));
            assertFalse(rs.next());
        }
    }

    @Test
    void 区间边界合理且旧事实不伪造来源() throws SQLException {
        cp = new ConnectionProvider(tempDir.resolve("fe2.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        // 有来源的证据
        insertEvidence(cp, "e1", "f1", 3, 1, "引文", 3, 7);
        // 无来源（UNKNOWN）
        insertEvidence(cp, "e2", "f2", 5, 1, null, 5, null);
        try (Connection conn = cp.getConnection(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT quote, valid_from_chapter, valid_to_chapter FROM fact_evidence ORDER BY id")) {
            assertTrue(rs.next());
            assertEquals("引文", rs.getString("quote"));
            assertEquals(3, rs.getInt("valid_from_chapter"));
            assertEquals(7, rs.getInt("valid_to_chapter"));
            assertTrue(rs.next());
            assertNull(rs.getString("quote"), "缺证据 quote 为 NULL（UNKNOWN）");
            assertFalse(rs.next());
        }
    }

    @Test
    void 双迁移幂等不丢行() throws SQLException {
        cp = new ConnectionProvider(tempDir.resolve("fe3.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        insertEvidence(cp, "e1", "f1", 3, 1, "引文", 3, null);
        insertObserver(cp, "e1", "char-A", 3, 1);
        new SqliteSchemaInitializer(cp).initialize();
        try (Connection conn = cp.getConnection(); Statement st = conn.createStatement();
             ResultSet rs1 = st.executeQuery("SELECT COUNT(*) FROM fact_evidence");
             ResultSet rs2 = st.executeQuery("SELECT COUNT(*) FROM fact_evidence_observers")) {
            rs1.next(); rs2.next();
            assertEquals(1, rs1.getInt(1), "证据行不丢");
            assertEquals(1, rs2.getInt(1), "知情者行不丢");
        }
    }
}
