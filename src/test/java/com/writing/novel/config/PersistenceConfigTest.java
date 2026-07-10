package com.writing.novel.config;

import com.writing.novel.persistence.embedding.EmbeddedVectorStore;
import com.writing.novel.persistence.embedding.StubEmbeddingProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.spi.EmbeddingProvider;
import com.writing.novel.spi.NovelRepository;
import com.writing.novel.spi.VectorStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@TestPropertySource(properties = {
        "persistence.embedding=stub",
        "persistence.sqlite.db-path=./data/test-config.db",
        "persistence.vector-store.index-path=./data/test-config-vecs.idx"
})
class PersistenceConfigTest {

    // SQLite JDBC 不会自动创建父目录；@SpringBootTest 在依赖注入阶段加载上下文，
    // 而 PER_METHOD 生命周期下静态 @BeforeAll 早于实例创建与上下文加载，故在此确保目录存在。
    @BeforeAll
    static void ensureDataDir() {
        new File("./data").mkdirs();
    }

    @Autowired
    ApplicationContext context;

    @Test
    void 默认装配SQLite与嵌入式向量库() {
        // 默认 application.yml: relational=sqlite, vector=embedded
        Object repo = context.getBean(NovelRepository.class);
        assertNotNull(repo);
        assertTrue(repo instanceof SqliteNovelRepository);
        assertTrue(context.getBean(VectorStore.class) instanceof EmbeddedVectorStore);
    }

    @Test
    void 默认装配StubEmbedding() {
        // 默认 application.yml: embedding=stub
        assertTrue(context.getBean(EmbeddingProvider.class) instanceof StubEmbeddingProvider);
    }
}
