package com.writing.novel.persistence;
import com.writing.novel.domain.WritingTask;
import com.writing.novel.persistence.sqlite.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
class CollaborationTaskPersistenceTest {
    @TempDir Path dir;
    @Test void taskInstructionStopAndTimestampsSurviveReopening() {
        ConnectionProvider cp=new ConnectionProvider(dir.resolve("collab.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        var repo=new SqliteNovelRepository(cp);
        var task=new WritingTask("p",400,WritingTask.Type.AUTO_RUN);
        task.setInstruction("保留人审细节");task.setStopRequested(true);repo.saveWritingTask(task);
        new SqliteSchemaInitializer(cp).initialize();
        var stored=new SqliteNovelRepository(cp).findWritingTask(task.getId()).orElseThrow();
        assertEquals(task.getInstruction(),stored.getInstruction());assertTrue(stored.isStopRequested());
        assertEquals(task.getCreatedAt(),stored.getCreatedAt());assertEquals(task.getUpdatedAt(),stored.getUpdatedAt());
        assertEquals(WritingTask.Type.AUTO_RUN,stored.getType());
    }
    @Test void legacyTaskTableMigratesWithSafeDefaults() throws Exception {
        ConnectionProvider cp=new ConnectionProvider(dir.resolve("legacy.db").toString());
        try(var connection=cp.getConnection();var statement=connection.createStatement()) {
            statement.execute("CREATE TABLE writing_tasks (id TEXT PRIMARY KEY,project_id TEXT,status TEXT,current_chapter INTEGER,total_chapters INTEGER,chapters_written INTEGER,total_words INTEGER,error_message TEXT,created_at TEXT,updated_at TEXT)");
            statement.execute("INSERT INTO writing_tasks VALUES ('old','p','PAUSED',2,24,2,5000,NULL,'2026-09-01T00:00:00Z','2026-09-01T00:00:00Z')");
        }
        new SqliteSchemaInitializer(cp).initialize();
        var task=new SqliteNovelRepository(cp).findWritingTask("old").orElseThrow();
        assertEquals(WritingTask.Type.WRITE_ALL,task.getType());
        assertNull(task.getInstruction());assertFalse(task.isStopRequested());
        assertEquals(2,task.getCurrentChapter());
        assertEquals(java.time.Instant.parse("2026-09-01T00:00:00Z"),task.getCreatedAt());
    }

}
