package com.writing.novel.persistence;

import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/** p38-05：不可变成书版本 schema——顺序确定、换行保持、hash 版本敏感、双迁移不损坏。 */
class BookEditionSchemaTest {

    @TempDir
    Path tempDir;

    private ConnectionProvider cp;
    private String projectId = "p-be";

    private void insertEdition(ConnectionProvider cp, String eid, String hash) throws SQLException {
        try (Connection conn = cp.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO book_editions (edition_id, project_id, manifest_hash, state, created_at) " +
                        "VALUES (?,?,?,?,?)")) {
            ps.setString(1, eid); ps.setString(2, projectId); ps.setString(3, hash);
            ps.setString(4, "DRAFT"); ps.setString(5, Instant.now().toString());
            ps.executeUpdate();
        }
        try (Connection conn = cp.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO book_edition_chapters (edition_id, chapter_index, version, title, content, " +
                        "content_hash, approval_id, plan_ref) VALUES (?,?,?,?,?,?,?,?)")) {
            Object[][] chapters = {
                    {1, 2, "第一章\n标题含换行", "正文\n第一段\n第二段\n", "h1", "a1", "vr1"},
                    {2, 1, "第二章", "第二章正文\n含换行", "h2", "a2", "vr2"},
            };
            for (Object[] ch : chapters) {
                ps.setString(1, eid);
                ps.setInt(2, (int) ch[0]);
                ps.setInt(3, (int) ch[1]);
                ps.setString(4, (String) ch[2]);
                ps.setString(5, (String) ch[3]);
                ps.setString(6, (String) ch[4]);
                ps.setString(7, (String) ch[5]);
                ps.setString(8, (String) ch[6]);
                ps.executeUpdate();
            }
        }
    }

    @Test
    void 多章快照顺序确定且换行保持() throws SQLException {
        cp = new ConnectionProvider(tempDir.resolve("be.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        insertEdition(cp, "be-1", "hash-1");
        try (Connection conn = cp.getConnection(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT title, content FROM book_edition_chapters " +
                             "WHERE edition_id='be-1' ORDER BY chapter_index")) {
            assertTrue(rs.next());
            assertEquals("第一章\n标题含换行", rs.getString("title"), "标题换行保持");
            assertEquals("正文\n第一段\n第二段\n", rs.getString("content"), "正文换行保持");
            assertTrue(rs.next());
            assertEquals("第二章", rs.getString("title"));
            assertFalse(rs.next(), "恰好两章");
        }
    }

    @Test
    void 同标题不同版本contentHash不同() {
        String h1 = com.writing.novel.domain.ChapterApproval.contentHash("第一章\n标题含换行", "正文\n第一段");
        String h2 = com.writing.novel.domain.ChapterApproval.contentHash("第一章\n标题含换行V2", "正文\n第一段");
        assertNotEquals(h1, h2, "标题变化 → hash 变化");
    }

    @Test
    void 两次schema初始化不损坏既有edition() throws SQLException {
        cp = new ConnectionProvider(tempDir.resolve("dual.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        insertEdition(cp, "be-2", "hash-2");
        new SqliteSchemaInitializer(cp).initialize();
        try (Connection conn = cp.getConnection(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM book_edition_chapters WHERE edition_id='be-2'")) {
            rs.next();
            assertEquals(2, rs.getInt(1), "双迁移不丢章节行");
        }
    }
}
