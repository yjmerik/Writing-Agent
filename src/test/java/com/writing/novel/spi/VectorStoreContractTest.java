package com.writing.novel.spi;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public abstract class VectorStoreContractTest {

    protected abstract VectorStore store();

    @Test
    void 存入并取回原文() {
        float[] v = {0.1f, 0.2f, 0.3f};
        store().upsert("c1", "proj-1", "玄黄大陆位于东方", v);
        assertEquals("玄黄大陆位于东方", store().getText("c1"));
    }

    @Test
    void 检索返回相似度排序结果() {
        store().upsert("e1", "proj-1", "剑修张三", new float[]{1.0f, 0.0f});
        store().upsert("e2", "proj-1", "丹师李四", new float[]{0.0f, 1.0f});

        List<VectorSearchResult> results = store().search(new float[]{1.0f, 0.0f}, "proj-1", 2);
        assertFalse(results.isEmpty());
        // 最相似的应为 e1
        assertEquals("e1", results.get(0).id());
        assertTrue(results.get(0).score() >= results.get(1).score());
    }

    @Test
    void 检索按项目隔离() {
        store().upsert("e1", "proj-1", "a", new float[]{1.0f});
        store().upsert("e2", "proj-2", "b", new float[]{1.0f});

        List<VectorSearchResult> results = store().search(new float[]{1.0f}, "proj-1", 10);
        assertEquals(1, results.size());
        assertEquals("e1", results.get(0).id());
    }
}
