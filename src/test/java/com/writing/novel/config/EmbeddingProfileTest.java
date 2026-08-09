package com.writing.novel.config;

import com.writing.novel.persistence.embedding.CloudApiEmbeddingProvider;
import com.writing.novel.persistence.embedding.StubEmbeddingProvider;
import com.writing.novel.spi.EmbeddingProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/**
 * mc-p2-04: 向量 embedding 默认 cloud-api 档；stub 仅显式配置生效（复现 M1）。
 * application.yml: persistence.embedding=cloud-api（生产默认，真实向量召回）
 */
@SpringBootTest
class EmbeddingProfileTest {

    @BeforeAll
    static void ensureDataDir() {
        new File("./data").mkdirs();
    }

    @Autowired
    ApplicationContext context;

    @Test
    void 默认档装配CloudApiEmbedding() {
        EmbeddingProvider provider = context.getBean(EmbeddingProvider.class);
        assertTrue(provider instanceof CloudApiEmbeddingProvider,
                "默认 profile 应装配 cloud-api embedding，实际: " + provider.getClass().getSimpleName());
    }

    /** stub 档：仅在显式配置 persistence.embedding=stub 时生效（离线/测试用） */
    @SpringBootTest
    @TestPropertySource(properties = "persistence.embedding=stub")
    static class StubOnlyWhenExplicitTest {
        @Autowired
        ApplicationContext context;

        @Test
        void 显式stub时装配StubEmbedding() {
            assertTrue(context.getBean(EmbeddingProvider.class) instanceof StubEmbeddingProvider);
        }
    }
}
