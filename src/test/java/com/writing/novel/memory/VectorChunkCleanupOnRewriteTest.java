package com.writing.novel.memory;

import com.writing.novel.persistence.embedding.EmbeddedVectorStore;
import com.writing.novel.spi.EmbeddingProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * p10-w1-03: 向量去污（重写删旧 chunk）。
 * bug: 章节重写后旧版本 chunk 永不删除，新旧正文并存 → 向量检索可能召回已被废弃的旧情节（自相矛盾）。
 * 修复: ChapterWriter.indexChapterInChunks 重索引前 removeByIdPrefix(chapterId) 清旧 chunk。
 * （top-K 联动预算为 ContextAssembler 行为，由其单元测试覆盖；此处聚焦向量去污正确性。）
 */
class VectorChunkCleanupOnRewriteTest {

    @TempDir
    Path tempDir;
    private EmbeddedVectorStore store;
    private VectorRetrievalService retrieval;
    private final EmbeddingProvider emb = new EmbeddingProvider() {
        @Override public float[] embed(String text) { return new float[]{text.hashCode() % 100 / 100.0f}; }
        @Override public int dimension() { return 1; }
        @Override public List<float[]> embedBatch(List<String> texts) {
            return texts.stream().map(this::embed).toList();
        }
    };

    @BeforeEach
    void setUp() {
        store = new EmbeddedVectorStore(tempDir.resolve("vec.idx").toString());
        retrieval = new VectorRetrievalService(store, emb);
    }

    @Test
    void 重写章节后旧版本高序号chunk应被清理() {
        String cid = "ch-001";
        // 首次索引：3 个 chunk（含高序号 chunk2）
        retrieval.indexBatch(List.of(
                new IndexedText(cid + "#chunk0", "p", "旧版本段落0", "CHAPTER"),
                new IndexedText(cid + "#chunk1", "p", "旧版本段落1", "CHAPTER"),
                new IndexedText(cid + "#chunk2", "p", "旧版本段落2（重写后不再存在）", "CHAPTER")));
        assertEquals("旧版本段落2（重写后不再存在）", store.getText(cid + "#chunk2"));

        // 重写流程：清旧 + 索引新版本（新版本只有 2 个 chunk）
        retrieval.removeByIdPrefix(cid);
        retrieval.indexBatch(List.of(
                new IndexedText(cid + "#chunk0", "p", "新版本段落0", "CHAPTER"),
                new IndexedText(cid + "#chunk1", "p", "新版本段落1", "CHAPTER")));

        // bug: chunk2 残留旧版本；修复: 清旧后 chunk2 为 null，且 chunk0/1 是新版本
        assertNull(store.getText(cid + "#chunk2"), "重写清旧后高序号旧 chunk 不应残留");
        assertEquals("新版本段落0", store.getText(cid + "#chunk0"), "chunk0 应为新版本");
        assertEquals("新版本段落1", store.getText(cid + "#chunk1"), "chunk1 应为新版本");
    }

    @Test
    void deleteByPrefix只清目标章节_不影响其他() {
        retrieval.indexBatch(List.of(
                new IndexedText("ch-A#chunk0", "p", "A章内容", "CHAPTER"),
                new IndexedText("ch-B#chunk0", "p", "B章内容", "CHAPTER")));
        int removed = retrieval.removeByIdPrefix("ch-A");
        assertEquals(1, removed);
        assertNull(store.getText("ch-A#chunk0"));
        assertEquals("B章内容", store.getText("ch-B#chunk0"), "B 章不应被误删");
    }

    @Test
    void removeByIdPrefix返回实际删除条数() {
        retrieval.indexBatch(List.of(
                new IndexedText("ch-X#chunk0", "p", "x0", "CHAPTER"),
                new IndexedText("ch-X#chunk1", "p", "x1", "CHAPTER"),
                new IndexedText("ch-Y#chunk0", "p", "y0", "CHAPTER")));
        assertEquals(2, retrieval.removeByIdPrefix("ch-X"));
        assertEquals(0, retrieval.removeByIdPrefix("ch-X"), "二次清理应返回 0");
    }
}
