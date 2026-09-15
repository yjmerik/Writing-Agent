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

/** p38-06：成书快照创建与批准——物化多章、批准绑定 manifestHash、重读一致。 */
class BookEditionRepositoryTest {

    @TempDir
    Path tempDir;
    private ConnectionProvider cp;
    private SqliteNovelRepository repo;
    private String pid = "p-be";

    @BeforeEach
    void setUp() {
        cp = new ConnectionProvider(tempDir.resolve("be.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
    }

    private BookEdition edition(String eid, String hash) {
        return new BookEdition(eid, pid, hash, BookEdition.State.DRAFT, Instant.now(), null);
    }

    private List<BookEdition.EditionChapter> chapters() {
        return List.of(
                new BookEdition.EditionChapter(1, 2, "Chapter 1\ntitle", "Content\npara 1\npara 2\n", "h1", "a1", "vr1"),
                new BookEdition.EditionChapter(2, 1, "Chapter 2", "Content 2\nwith newline", "h2", "a2", "vr2"));
    }

    @Test
    void save_find_chapters有序且内容保持() {
        BookEdition ed = edition("be-1", "hash-abc");
        repo.saveBookEdition(ed, chapters());
        var found = repo.findBookEdition(pid, "be-1").orElseThrow();
        assertEquals("hash-abc", found.getManifestHash());
        assertEquals(BookEdition.State.DRAFT, found.getState());
        var chapterList = repo.findBookEditionChapters("be-1");
        assertEquals(2, chapterList.size());
        assertEquals("Chapter 1\ntitle", chapterList.get(0).title(), "newline preserved");
        assertEquals("Content\npara 1\npara 2\n", chapterList.get(0).content(), "content newline preserved");
        assertEquals(1, chapterList.get(0).chapterIndex(), "ordered");
        assertEquals(2, chapterList.get(1).chapterIndex());
    }

    @Test
    void approve绑定manifestHash且重读一致() {
        BookEdition ed = edition("be-2", "hash-approve");
        repo.saveBookEdition(ed, chapters());
        repo.approveBookEdition(pid, "be-2", "hash-approve");
        var found = repo.findBookEdition(pid, "be-2").orElseThrow();
        assertEquals(BookEdition.State.APPROVED, found.getState());
        assertNotNull(found.getApprovedAt());
        // wrong hash → reject
        BookEdition ed2 = edition("be-3", "hash-wrong");
        repo.saveBookEdition(ed2, chapters());
        assertThrows(IllegalStateException.class,
                () -> repo.approveBookEdition(pid, "be-3", "different-hash"));
    }
}
