package com.writing.novel.persistence.embedding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-134: 向量存储工程化——维度校验自动重建（发事件）、按项目派生路径、
 * 分段增量持久化跨重启往返（含多次 flush 段合并）。
 */
class VectorStoreEngineeringTest {

    @TempDir
    Path tempDir;

    @Test
    void 维度不匹配_自动清空重建发事件() {
        var store = new EmbeddedVectorStore(tempDir.resolve("v.idx").toString());
        store.upsert("a", "p", "文本一", new float[]{1.0f, 0.0f, 0.0f});
        assertTrue(store.validateDimension(3), "首次设定维度");

        boolean ok = store.validateDimension(4);
        assertFalse(ok, "维度 3→4 不匹配应返回 false（调用方重灌）");
        assertEquals(0, store.search(new float[]{1, 0, 0, 0}, "p", 5).size(),
                "旧向量已清空（不再静默失联）");
        // 重建后新维度数据可用
        store.upsert("b", "p", "文本二", new float[]{1.0f, 0.0f, 0.0f, 0.0f});
        assertEquals(1, store.search(new float[]{1, 0, 0, 0}, "p", 5).size());
        assertTrue(store.validateDimension(4), "重建后同维度通过");
    }

    @Test
    void 按项目派生路径() {
        assertEquals(tempDir + "/vectors.idx-proj1.idx",
                EmbeddedVectorStore.perProjectPath(tempDir + "/vectors.idx", "proj1"));
        assertEquals("./vectors.idx-p.idx",
                EmbeddedVectorStore.perProjectPath("vectors.idx", "p"));
    }

    @Test
    void 分段持久化_多次flush跨重启往返() {
        String path = tempDir.resolve("seg.idx").toString();
        var store = new EmbeddedVectorStore(path);
        store.upsert("a", "p", "文本A", new float[]{1.0f, 0.0f});
        store.flush();   // 段1
        store.upsert("b", "p", "文本B", new float[]{0.7f, 0.7f});
        store.delete("a");
        store.flush();   // 段2（含删除后的全量快照）

        var reloaded = new EmbeddedVectorStore(path);
        assertNull(reloaded.getText("a"), "删除跨重启生效");
        assertEquals("文本B", reloaded.getText("b"));
        var hits = reloaded.search(new float[]{1.0f, 0.0f}, "p", 5);
        assertEquals(List.of("b"), hits.stream().map(h -> h.id()).toList());
    }

    @Test
    void 多段追加_后段覆盖前段() {
        String path = tempDir.resolve("multi.idx").toString();
        var store = new EmbeddedVectorStore(path);
        store.upsert("x", "p", "旧文本", new float[]{1.0f});
        store.flush();
        store.upsert("x", "p", "新文本", new float[]{1.0f});
        store.flush();

        var reloaded = new EmbeddedVectorStore(path);
        assertEquals("新文本", reloaded.getText("x"), "多段合并后写覆盖先写");
    }
}
