package com.writing.novel.persistence;

import com.writing.novel.domain.ChapterVersion;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.spi.NovelRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChapterVersionHistoryTest {

    @TempDir
    Path tempDir;

    @Test
    void 多个版本按版本倒序查询且全部字段精确往返() {
        NovelRepository repository = repository("round-trip.db");
        Instant firstCreatedAt = Instant.parse("2026-09-03T01:02:03.123456789Z");
        Instant secondCreatedAt = Instant.parse("2026-09-03T02:03:04.987654321Z");
        ChapterVersion first = new ChapterVersion(
                "history-1", "project-a", 7, 1,
                "第七章·旧题", "第一版正文", "DIRECT_EDIT", firstCreatedAt);
        ChapterVersion second = new ChapterVersion(
                "history-2", "project-a", 7, 2,
                "第七章·新题", "第二版正文", "RESTORE", secondCreatedAt);

        repository.saveChapterVersion(first);
        repository.saveChapterVersion(second);

        List<ChapterVersion> history = repository.findChapterVersions("project-a", 7);
        assertEquals(List.of(2, 1), history.stream().map(ChapterVersion::getVersion).toList());
        assertChapterVersion(second, history.get(0));
        assertChapterVersion(first, history.get(1));
        assertChapterVersion(first,
                repository.findChapterVersion("project-a", 7, 1).orElseThrow());
        assertTrue(repository.findChapterVersion("project-a", 7, 99).isEmpty());
    }

    @Test
    void 重复项目章号版本插入失败且原快照保持不变() {
        NovelRepository repository = repository("duplicate.db");
        ChapterVersion original = new ChapterVersion(
                "history-original", "project-a", 3, 4,
                "原题", "原正文", "REWRITE", Instant.parse("2026-09-03T03:04:05Z"));
        ChapterVersion duplicate = new ChapterVersion(
                "history-duplicate", "project-a", 3, 4,
                "篡改题", "篡改正文", "DIRECT_EDIT", Instant.parse("2026-09-03T04:05:06Z"));
        repository.saveChapterVersion(original);

        assertThrows(RuntimeException.class, () -> repository.saveChapterVersion(duplicate));
        ChapterVersion afterFailure = new ChapterVersion(
                "history-after-failure", "project-a", 3, 5,
                "后续题", "后续正文", "RESTORE", Instant.parse("2026-09-03T05:06:07Z"));
        repository.saveChapterVersion(afterFailure);

        List<ChapterVersion> history = repository.findChapterVersions("project-a", 3);
        assertEquals(2, history.size());
        assertChapterVersion(afterFailure, history.get(0));
        assertChapterVersion(original, history.get(1));
    }

    @Test
    void 不同项目或不同章节可以保存相同版本号() {
        NovelRepository repository = repository("scope.db");
        Instant createdAt = Instant.parse("2026-09-03T05:06:07Z");
        repository.saveChapterVersion(new ChapterVersion(
                "a-1", "project-a", 1, 1, "A1", "正文A1", "DIRECT_EDIT", createdAt));
        repository.saveChapterVersion(new ChapterVersion(
                "a-2", "project-a", 2, 1, "A2", "正文A2", "DIRECT_EDIT", createdAt));
        repository.saveChapterVersion(new ChapterVersion(
                "b-1", "project-b", 1, 1, "B1", "正文B1", "DIRECT_EDIT", createdAt));

        assertEquals("A1", repository.findChapterVersion("project-a", 1, 1).orElseThrow().getTitle());
        assertEquals("A2", repository.findChapterVersion("project-a", 2, 1).orElseThrow().getTitle());
        assertEquals("B1", repository.findChapterVersion("project-b", 1, 1).orElseThrow().getTitle());
    }

    @Test
    void 版本对象不可变且仓储不暴露更新删除入口() {
        assertTrue(Arrays.stream(ChapterVersion.class.getDeclaredFields())
                .allMatch(field -> Modifier.isPrivate(field.getModifiers())
                        && Modifier.isFinal(field.getModifiers())));
        assertFalse(Arrays.stream(ChapterVersion.class.getMethods())
                .anyMatch(method -> method.getName().startsWith("set")));
        assertFalse(Arrays.stream(NovelRepository.class.getDeclaredMethods())
                .map(method -> method.getName().toLowerCase())
                .anyMatch(name -> name.contains("updatechapterversion")
                        || name.contains("deletechapterversion")));
    }

    @Test
    void 新库初始化时创建完整版本表() throws SQLException {
        String dbPath = tempDir.resolve("fresh.db").toString();
        ConnectionProvider cp = new ConnectionProvider(dbPath);
        new SqliteSchemaInitializer(cp).initialize();

        try (Connection connection = cp.getConnection();
             Statement statement = connection.createStatement();
             ResultSet columns = statement.executeQuery("PRAGMA table_info(chapter_versions)")) {
            int columnCount = 0;
            while (columns.next()) {
                columnCount++;
                assertEquals(1, columns.getInt("notnull"),
                        "所有版本列都必须 NOT NULL: " + columns.getString("name"));
            }
            assertEquals(8, columnCount);
        }
    }

    @Test
    void 空快照主键被数据库拒绝且不留下历史行() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("null-id.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        NovelRepository repository = new SqliteNovelRepository(cp);
        ChapterVersion nullId = new ChapterVersion(
                null, "project-a", 1, 1, "第一章", "正文", "DIRECT_EDIT",
                Instant.parse("2026-09-03T07:08:09Z"));
        assertThrows(RuntimeException.class, () -> repository.saveChapterVersion(nullId));
        assertTrue(repository.findChapterVersions("project-a", 1).isEmpty());
    }

    @Test
    void 已有库迁移新增版本表且不改写原章节() throws SQLException {
        String dbPath = tempDir.resolve("legacy.db").toString();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE chapters (" +
                    "id TEXT PRIMARY KEY, project_id TEXT, chapter_index INTEGER, " +
                    "title TEXT, content TEXT, word_count INTEGER, version INTEGER, " +
                    "created_at TEXT, updated_at TEXT)");
            statement.execute("INSERT INTO chapters VALUES (" +
                    "'chapter-1','legacy-project',1,'旧章','不可改写的正文',6,3," +
                    "'2026-09-01T00:00:00Z','2026-09-02T00:00:00Z')");
        }

        ConnectionProvider cp = new ConnectionProvider(dbPath);
        new SqliteSchemaInitializer(cp).initialize();
        NovelRepository repository = new SqliteNovelRepository(cp);
        ChapterVersion snapshot = new ChapterVersion(
                "legacy-history", "legacy-project", 1, 3,
                "旧章", "不可改写的正文", "MIGRATION_TEST", Instant.parse("2026-09-03T06:07:08Z"));
        repository.saveChapterVersion(snapshot);

        try (Connection connection = cp.getConnection();
             Statement statement = connection.createStatement();
             ResultSet chapter = statement.executeQuery(
                     "SELECT title, content, version FROM chapters WHERE id='chapter-1'")) {
            assertTrue(chapter.next());
            assertEquals("旧章", chapter.getString("title"));
            assertEquals("不可改写的正文", chapter.getString("content"));
            assertEquals(3, chapter.getInt("version"));
        }
        assertChapterVersion(snapshot,
                repository.findChapterVersion("legacy-project", 1, 3).orElseThrow());
    }

    private NovelRepository repository(String filename) {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve(filename).toString());
        new SqliteSchemaInitializer(cp).initialize();
        return new SqliteNovelRepository(cp);
    }

    private static void assertChapterVersion(ChapterVersion expected, ChapterVersion actual) {
        assertEquals(expected.getId(), actual.getId());
        assertEquals(expected.getProjectId(), actual.getProjectId());
        assertEquals(expected.getChapterIndex(), actual.getChapterIndex());
        assertEquals(expected.getVersion(), actual.getVersion());
        assertEquals(expected.getTitle(), actual.getTitle());
        assertEquals(expected.getContent(), actual.getContent());
        assertEquals(expected.getSource(), actual.getSource());
        assertEquals(expected.getCreatedAt(), actual.getCreatedAt());
    }
}
