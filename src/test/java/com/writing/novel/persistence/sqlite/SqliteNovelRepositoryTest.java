package com.writing.novel.persistence.sqlite;

import com.writing.novel.domain.WritingTask;
import com.writing.novel.spi.NovelRepository;
import com.writing.novel.spi.NovelRepositoryContractTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SqliteNovelRepositoryTest extends NovelRepositoryContractTest {

    @TempDir
    Path tempDir;
    private NovelRepository repository;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("t.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repository = new SqliteNovelRepository(cp);
    }

    @Override
    protected NovelRepository repository() {
        return repository;
    }

    @Test
    void writingTask类型roundTrip() {
        WritingTask t = new WritingTask("p1", 5, WritingTask.Type.CONCEIVE);
        repository.saveWritingTask(t);

        Optional<WritingTask> found = repository.findWritingTask(t.getId());
        assertTrue(found.isPresent());
        assertEquals(WritingTask.Type.CONCEIVE, found.get().getType());
        assertEquals("p1", found.get().getProjectId());
        assertEquals(5, found.get().getTotalChapters());

        assertEquals(WritingTask.Type.CONCEIVE,
                repository.findWritingTasksByProject("p1").get(0).getType());
    }

    @Test
    void writingTaskUpsert保留并更新类型() {
        WritingTask t = new WritingTask("p1", 5, WritingTask.Type.WRITE_CHAPTER);
        repository.saveWritingTask(t);
        t.setStatus(WritingTask.Status.PAUSED);
        repository.saveWritingTask(t);

        Optional<WritingTask> found = repository.findWritingTask(t.getId());
        assertTrue(found.isPresent());
        assertEquals(WritingTask.Type.WRITE_CHAPTER, found.get().getType());
        assertEquals(WritingTask.Status.PAUSED, found.get().getStatus());
    }
}
