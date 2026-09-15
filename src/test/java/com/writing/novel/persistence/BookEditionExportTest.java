package com.writing.novel.persistence;

import com.writing.novel.domain.BookEdition;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** p38-07：成书版本导出——APPROVED 版本的章节逐字一致，后续修改不影响。 */
class BookEditionExportTest {

    @TempDir
    Path tempDir;
    private SqliteNovelRepository repo;
    private String pid = "p-export";

    @Test
    void approvedEditionContentStableAfterSourceModification() {
        var cp = new ConnectionProvider(tempDir.resolve("export.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);

        var edition = new BookEdition("be-exp", pid, "hash-exp",
                BookEdition.State.DRAFT, Instant.now(), null);
        var chapters = List.of(
                new BookEdition.EditionChapter(1, 2, "Chapter 1", "Original content\nline 2", "h1", "a1", null),
                new BookEdition.EditionChapter(2, 1, "Chapter 2", "Chapter 2 content", "h2", null, null));
        repo.saveBookEdition(edition, chapters);
        repo.approveBookEdition(pid, "be-exp", "hash-exp");

        // Verify approved content is stable
        var exported = repo.findBookEditionChapters("be-exp");
        assertEquals(2, exported.size());
        assertEquals("Original content\nline 2", exported.get(0).content());

        // Modify source chapter (edition should be unaffected)
        var chapter = new com.writing.novel.domain.Chapter(pid, 1, "Chapter 1");
        chapter.setContent("MODIFIED content after approval");
        repo.saveChapter(chapter);

        // Edition content unchanged
        var reExported = repo.findBookEditionChapters("be-exp");
        assertEquals("Original content\nline 2", reExported.get(0).content(),
                "Edition content unaffected by source modification");
    }
}
