package com.writing.novel.memory;

import com.writing.novel.spi.EmbeddingProvider;
import com.writing.novel.spi.VectorSearchResult;
import com.writing.novel.spi.VectorStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VectorRetrievalServiceTest {

    /**
     * 确定性嵌入：dim[0]=首字符码点，dim[1]=常数 1.0。
     * 第二维取常数使向量方向仅由首字符决定——相同首字符方向一致（余弦=1），
     * 不同首字符方向偏离（余弦<1）。这样"同首字符"在余弦相似度下必然排名更高。
     * （若 dim[1]=length，余弦会被首字符超大值压成 ≈1，导致排序由长度噪声决定。）
     */
    private EmbeddingProvider simpleEmbedding() {
        return new EmbeddingProvider() {
            @Override
            public float[] embed(String text) {
                float[] v = new float[2];
                v[0] = text.isEmpty() ? 0 : text.charAt(0);
                v[1] = 1.0f;
                return v;
            }
            @Override
            public int dimension() { return 2; }
        };
    }

    @Test
    void 索引文本后可按语义检索() throws Exception {
        VectorStore store = new com.writing.novel.persistence.embedding.EmbeddedVectorStore(
                java.nio.file.Files.createTempFile("vec", ".idx").toString());
        VectorRetrievalService svc = new VectorRetrievalService(store, simpleEmbedding());

        svc.index(new IndexedText("c1", "proj-1", "主角张三是火爆剑修", "CHARACTER"));
        svc.index(new IndexedText("c2", "proj-1", "玄黄大陆位于东方", "WORLDBUILDING"));

        List<VectorSearchResult> results = svc.search("主张相关", "proj-1", 2);
        assertFalse(results.isEmpty());
        assertEquals("c1", results.get(0).id());
    }

    @Test
    void 检索按项目隔离() throws Exception {
        VectorStore store = new com.writing.novel.persistence.embedding.EmbeddedVectorStore(
                java.nio.file.Files.createTempFile("vec2", ".idx").toString());
        VectorRetrievalService svc = new VectorRetrievalService(store, simpleEmbedding());

        svc.index(new IndexedText("a", "proj-1", "内容A", "CHAPTER"));
        svc.index(new IndexedText("b", "proj-2", "内容A", "CHAPTER"));

        List<VectorSearchResult> results = svc.search("内容A", "proj-1", 10);
        assertEquals(1, results.size());
        assertEquals("a", results.get(0).id());
    }

    @Test
    void 取回原文() throws Exception {
        VectorStore store = new com.writing.novel.persistence.embedding.EmbeddedVectorStore(
                java.nio.file.Files.createTempFile("vec3", ".idx").toString());
        VectorRetrievalService svc = new VectorRetrievalService(store, simpleEmbedding());

        svc.index(new IndexedText("x1", "p1", "这是要被检索的原文", "CHAPTER"));
        assertEquals("这是要被检索的原文", svc.getText("x1"));
    }
}
