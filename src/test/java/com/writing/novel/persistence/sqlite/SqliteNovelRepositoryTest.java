package com.writing.novel.persistence.sqlite;

import com.writing.novel.spi.NovelRepository;
import com.writing.novel.spi.NovelRepositoryContractTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

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
}
