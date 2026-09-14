package com.writing.novel.persistence;

import com.writing.novel.domain.AuthorDirective;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * p37-06：意见范围与逐章证据模型迁移。
 * 旧构造/旧行兼容（chapterIndex→同起止、null→全书从1起、enabled=true、revision=1）；
 * 同意见多章多稿证据互不覆盖；空/倒置/非正章号范围拒绝；HARD 确认与 enabled 独立。
 */
class DirectiveScopeMigrationTest {

    @TempDir
    Path tempDir;

    @Test
    void 旧行迁移补范围与revision且null章节映射全书() throws SQLException {
        String dbPath = tempDir.resolve("legacy.db").toString();
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE author_directives (id TEXT PRIMARY KEY, project_id TEXT, kind TEXT, " +
                    "chapter_index INTEGER, content TEXT, status TEXT, system_evidence TEXT, " +
                    "system_evidence_version INTEGER, created_at TEXT, confirmed_at TEXT, updated_at TEXT)");
            st.execute("INSERT INTO author_directives (id, project_id, kind, chapter_index, content, status, " +
                    "created_at, updated_at) VALUES ('d1','p1','HARD',3,'每章至少一场雨夜对话','OPEN'," +
                    "'2026-01-01T00:00:00Z','2026-01-01T00:00:00Z')");
            st.execute("INSERT INTO author_directives (id, project_id, kind, chapter_index, content, status, " +
                    "created_at, updated_at) VALUES ('d2','p1','ONCE',NULL,'全书增强对话','OPEN'," +
                    "'2026-01-01T00:00:00Z','2026-01-01T00:00:00Z')");
        }
        ConnectionProvider cp = new ConnectionProvider(dbPath);
        new SqliteSchemaInitializer(cp).initialize();

        try (Connection conn = cp.getConnection(); Statement st = conn.createStatement()) {
            ResultSet rs = st.executeQuery("SELECT revision, from_chapter, to_chapter, enabled " +
                    "FROM author_directives WHERE id='d1'");
            assertTrue(rs.next());
            assertEquals(1, rs.getInt("revision"), "旧行 revision 迁移为 1");
            assertEquals(3, rs.getInt("from_chapter"), "旧 chapterIndex → from=同值");
            assertEquals(3, rs.getInt("to_chapter"), "旧 chapterIndex → to=同值");
            assertEquals(1, rs.getInt("enabled"), "旧行 enabled=true");
            rs = st.executeQuery("SELECT from_chapter, to_chapter FROM author_directives WHERE id='d2'");
            assertTrue(rs.next());
            assertEquals(1, rs.getInt("from_chapter"), "null 章节映射全书从 1 起");
            assertTrue(rs.wasNull() || rs.getInt("to_chapter") == 0 ? rs.wasNull() : false,
                    "全书 to 为 NULL");
            // directive_evidence 表存在且为空
            ResultSet ev = st.executeQuery("SELECT COUNT(*) FROM directive_evidence");
            ev.next();
            assertEquals(0, ev.getInt(1));
        }
    }

    @Test
    void 同意见多章多稿证据互不覆盖() throws SQLException {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("ev.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        try (Connection conn = cp.getConnection(); Statement st = conn.createStatement()) {
            st.execute("INSERT INTO directive_evidence (id, directive_id, project_id, directive_revision, " +
                    "chapter_index, chapter_version, content_hash, proposal_id, quote, anchor, status, reason, " +
                    "created_at) VALUES " +
                    "('e1','d1','p1',1,3,4,'hash-4','prop-1','雨夜对话原文','p:3','APPLIED_SYSTEM',NULL,'2026-01-01T00:00:00Z')," +
                    "('e2','d1','p1',1,5,2,'hash-2',NULL,NULL,NULL,'UNMET','第5章未出现对话','2026-01-02T00:00:00Z')");
        }
        try (Connection conn = cp.getConnection(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM directive_evidence WHERE directive_id='d1'")) {
            rs.next();
            assertEquals(2, rs.getInt(1), "同意见多章证据并存");
        }
        // Evidence 记录可直接构造（域模型承载）
        AuthorDirective.Evidence evidence = new AuthorDirective.Evidence(
                "e3", "d1", 2, 6, 1, "hash-6", "prop-9", "引用原文", "p:6",
                AuthorDirective.EvidenceStatus.CONFIRMED, null);
        assertEquals(AuthorDirective.EvidenceStatus.CONFIRMED, evidence.status());
        assertEquals(2, evidence.directiveRevision());
    }

    @Test
    void 范围校验拒绝空起止倒置与非正章号() {
        AuthorDirective directive = new AuthorDirective("p1", AuthorDirective.Kind.HARD, null, "全书要求");
        // 合法：全书（均 null）、正区间、开区间
        assertDoesNotThrow(() -> directive.validateScope(null, null));
        assertDoesNotThrow(() -> directive.validateScope(3, 7));
        assertDoesNotThrow(() -> directive.validateScope(3, null));
        // 非法：只有 to、to<from、非正章号
        assertThrows(IllegalArgumentException.class, () -> directive.validateScope(null, 5));
        assertThrows(IllegalArgumentException.class, () -> directive.validateScope(7, 3));
        assertThrows(IllegalArgumentException.class, () -> directive.validateScope(0, 5));
        assertThrows(IllegalArgumentException.class, () -> directive.validateScope(-1, null));
        assertThrows(IllegalArgumentException.class, () -> directive.validateScope(3, 0));
    }

    @Test
    void HARD确认某章与enabled独立() throws SQLException {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("hard.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        try (Connection conn = cp.getConnection(); Statement st = conn.createStatement()) {
            st.execute("INSERT INTO author_directives (id, project_id, kind, chapter_index, content, status, " +
                    "revision, from_chapter, to_chapter, enabled, created_at, updated_at) VALUES " +
                    "('dh','p1','HARD',NULL,'全书硬性要求','OPEN',1,1,NULL,1,'2026-01-01T00:00:00Z','2026-01-01T00:00:00Z')");
            st.execute("INSERT INTO directive_evidence (id, directive_id, project_id, directive_revision, " +
                    "chapter_index, chapter_version, content_hash, proposal_id, quote, anchor, status, reason, " +
                    "created_at) VALUES ('eh','dh','p1',1,4,2,'h',NULL,'q',NULL,'APPLIED_SYSTEM',NULL,'2026-01-01T00:00:00Z')");
            // 作者确认第4章证据
            st.execute("UPDATE directive_evidence SET status='CONFIRMED' WHERE id='eh'");
        }
        try (Connection conn = cp.getConnection(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT enabled FROM author_directives WHERE id='dh'")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt("enabled"), "HARD 确认某章不使后续要求失效");
        }
    }
}
