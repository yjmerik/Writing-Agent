package com.writing.novel.config;

import com.writing.novel.persistence.embedding.EmbeddedVectorStore;
import com.writing.novel.persistence.embedding.StubEmbeddingProvider;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.spi.EmbeddingProvider;
import com.writing.novel.spi.NovelRepository;
import com.writing.novel.spi.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PersistenceConfig {

    // --- SQLite ---
    @Bean
    @ConditionalOnProperty(name = "persistence.relational", havingValue = "sqlite", matchIfMissing = true)
    public ConnectionProvider sqliteConnectionProvider(PersistenceProperties props) {
        return new ConnectionProvider(props.getSqlite().getDbPath());
    }

    @Bean
    @ConditionalOnProperty(name = "persistence.relational", havingValue = "sqlite", matchIfMissing = true)
    public SqliteSchemaInitializer sqliteSchemaInitializer(ConnectionProvider cp) {
        return new SqliteSchemaInitializer(cp);
    }

    @Bean
    @ConditionalOnProperty(name = "persistence.relational", havingValue = "sqlite", matchIfMissing = true)
    public NovelRepository sqliteNovelRepository(ConnectionProvider cp,
                                                  SqliteSchemaInitializer schemaInit) {
        schemaInit.initialize();
        return new SqliteNovelRepository(cp);
    }

    // --- Embedded VectorStore ---
    @Bean
    @ConditionalOnProperty(name = "persistence.vector", havingValue = "embedded", matchIfMissing = true)
    public VectorStore embeddedVectorStore(PersistenceProperties props) {
        return new EmbeddedVectorStore(props.getVectorStore().getIndexPath());
    }

    // --- Embedding: 默认用 Stub（真实云 API 在 Plan 2 接入） ---
    @Bean
    @ConditionalOnProperty(name = "persistence.embedding", havingValue = "stub", matchIfMissing = false)
    public EmbeddingProvider stubEmbeddingProvider() {
        return new StubEmbeddingProvider();
    }
}
