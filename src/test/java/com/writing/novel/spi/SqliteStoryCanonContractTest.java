package com.writing.novel.spi;

import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.persistence.sqlite.SqliteStoryCanon;
import com.writing.novel.persistence.sqlite.StoryFactRepository;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/** p22-01: 契约在 SQLite 适配器上的实例化 */
class SqliteStoryCanonContractTest extends StoryCanonContract {

    @TempDir Path tempDir;

    @Override StoryCanon create() {
        ConnectionProvider cp = new ConnectionProvider(
                tempDir.resolve("canon-" + System.nanoTime() + ".db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        return new SqliteStoryCanon(new StoryFactRepository(cp));
    }
}
