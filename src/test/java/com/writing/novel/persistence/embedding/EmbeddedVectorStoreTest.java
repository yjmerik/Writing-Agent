package com.writing.novel.persistence.embedding;

import com.writing.novel.spi.VectorStore;
import com.writing.novel.spi.VectorStoreContractTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EmbeddedVectorStoreTest extends VectorStoreContractTest {

    @TempDir
    Path tempDir;
    private EmbeddedVectorStore store;

    @BeforeEach
    void setUp() {
        store = new EmbeddedVectorStore(tempDir.resolve("vecs.idx").toString());
    }

    @Override
    protected VectorStore store() {
        return store;
    }

    @Test
    void 重启后数据保留() {
        store().upsert("x1", "p1", "文本A", new float[]{1.0f, 0.0f});
        // 用同一路径重新加载
        EmbeddedVectorStore reloaded = new EmbeddedVectorStore(tempDir.resolve("vecs.idx").toString());
        assertEquals("文本A", reloaded.getText("x1"));
    }
}
