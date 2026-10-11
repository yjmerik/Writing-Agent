package com.writing.novel.memory;

import com.writing.novel.persistence.embedding.EmbeddedVectorStore;
import com.writing.novel.spi.EmbeddingProvider;
import com.writing.novel.spi.VectorSearchResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-040: 向量投影原子替换——embedding 失败时原索引完整；
 * 成功后旧投影被替换、新投影可检索。
 */
class AtomicVectorProjectionTest {

    @TempDir
    Path tempDir;

    private EmbeddingProvider okProvider() {
        return new EmbeddingProvider() {
            @Override public float[] embed(String text) {
                return new float[]{text.length() % 7 + 1, 2f, 3f};
            }
            @Override public int dimension() { return 3; }
        };
    }

    @Test
    void embedding失败时原索引完整() {
        var store = new EmbeddedVectorStore(tempDir.resolve("v.idx").toString());
        var gatewaySvc = new VectorRetrievalService(store, okProvider());
        gatewaySvc.index(new IndexedText("ch1", "p1", "初稿：账房残账的线索。", "CHAPTER"));
        gatewaySvc.index(new IndexedText("ch1#chunk1", "p1", "初稿第二节：送信人沿河走。", "CHAPTER"));

        // 新内容 embedding 爆炸
        VectorRetrievalService failing = new VectorRetrievalService(store, new EmbeddingProvider() {
            @Override public float[] embed(String text) {
                throw new RuntimeException("embedding 服务不可用");
            }
            @Override public int dimension() { return 3; }
        });
        assertThrows(RuntimeException.class, () -> failing.replaceByPrefixAtomic("ch1",
                List.of(new IndexedText("ch1", "p1", "重写稿：新线索。", "CHAPTER"))));

        // 原索引完整：初稿仍可检索（同前缀无残留污染）
        assertEquals("初稿：账房残账的线索。", gatewaySvc.getText("ch1"));
        assertEquals("初稿第二节：送信人沿河走。", gatewaySvc.getText("ch1#chunk1"));
        List<VectorSearchResult> hits = gatewaySvc.search("账房 残账", "p1", 5);
        assertTrue(hits.stream().anyMatch(r -> r.id().equals("ch1")), "初稿投影仍在");
    }

    @Test
    void 成功后旧投影被替换_新投影可检索() {
        var store = new EmbeddedVectorStore(tempDir.resolve("v2.idx").toString());
        var svc = new VectorRetrievalService(store, okProvider());
        svc.index(new IndexedText("ch1", "p1", "初稿：账房残账的线索。", "CHAPTER"));
        svc.index(new IndexedText("ch1#chunk1", "p1", "初稿第二节：送信人沿河走。", "CHAPTER"));

        svc.replaceByPrefixAtomic("ch1", List.of(
                new IndexedText("ch1", "p1", "重写稿：新开局的账房场景。", "CHAPTER"),
                new IndexedText("ch1#chunk1", "p1", "重写稿第二节：柜坊对质。", "CHAPTER")));

        // 旧投影应被替换删除——项目内检索结果 id 集合恰为新投影（无陈旧残留）
        var ids = svc.search("任意查询", "p1", 10).stream().map(r -> r.id()).toList();
        assertTrue(ids.stream().allMatch(id -> id.equals("ch1") || id.equals("ch1#chunk1")),
                "检索结果应只含新投影: " + ids);
        assertEquals("重写稿：新开局的账房场景。", svc.getText("ch1"));
        assertEquals("重写稿第二节：柜坊对质。", svc.getText("ch1#chunk1"));
    }

    @Test
    void 空新块等于纯删除() {
        var store = new EmbeddedVectorStore(tempDir.resolve("v3.idx").toString());
        var svc = new VectorRetrievalService(store, okProvider());
        svc.index(new IndexedText("ch9", "p1", "将被清空的章节。", "CHAPTER"));
        svc.replaceByPrefixAtomic("ch9", List.of());
        assertNull(svc.getText("ch9"), "纯删除后 id 应不存在");
        assertTrue(svc.search("将被清空", "p1", 5).isEmpty(), "纯删除后不可检索");
    }
}
