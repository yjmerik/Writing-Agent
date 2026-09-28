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
 * NCS-111: 向量检索配额阈值去重——
 * 1) 绝对阈值：低于 minScore 的结果不返回（score>=0.0 形同虚设的修复）；
 * 2) 两桶配额：正文 chunk 不得数量级压没设定条目；
 * 3) 相邻 chunk 去重：同章 #chunkN 与 #chunkN+1 只保留前者。
 */
class VectorSearchQuotaTest {

    @Test
    void 绝对阈值过滤() {
        EmbeddedVectorStore store = new EmbeddedVectorStore("/tmp/nonexistent-quota-test.idx");
        store.upsert("t1", "p", "文本一", new float[]{1.0f, 0.0f});
        store.upsert("t2", "p", "文本二", new float[]{0.5f, 0.5f});
        EmbeddingProvider embed = new EmbeddingProvider() {
            @Override public float[] embed(String text) { return new float[]{1.0f, 0.0f}; }
            @Override public int dimension() { return 2; }
        };
        VectorRetrievalService service = new VectorRetrievalService(store, embed);
        // 余弦(t1, query)=1.0；余弦(t2, query)=0.707
        assertEquals(2, service.search("q", "p", 5, 0.0).size());
        assertEquals(List.of("t1"), service.search("q", "p", 5, 0.9).stream()
                .map(VectorSearchResult::id).toList(), "低于阈值的结果不返回");
    }

    @Test
    void 两桶配额_正文不压没设定() {
        List<String> ids = List.of("c1#chunk1", "c2#chunk1", "c3#chunk1", "c4#chunk1",
                "c5#chunk1", "k1", "k2");
        // topK=6：旧实现取前 6 → 全是 chunk；新实现两桶各半（chunk 配额 3）+ 补足规则
        List<String> out = ContextAssembler.applySourceQuotaAndAdjacency(ids, 6);
        assertEquals(6, out.size());
        long chunks = out.stream().filter(i -> i.contains("#chunk")).count();
        long others = out.stream().filter(i -> !i.contains("#chunk")).count();
        assertEquals(2, others, "设定条目保住配额（存在的 k1/k2 全保留）");
        assertEquals(4, chunks, "chunk 配额 3 + 设定桶不足时依序补足 1");
        assertTrue(out.contains("k1") && out.contains("k2"));
    }

    @Test
    void 相邻chunk去重_同章只保留前块() {
        List<String> ids = List.of("c1#chunk2", "c1#chunk1", "c1#chunk3", "k1");
        List<String> out = ContextAssembler.applySourceQuotaAndAdjacency(ids, 8);
        assertTrue(out.contains("c1#chunk1"), "首块保留");
        assertFalse(out.contains("c1#chunk2"), "与前块相邻的 #chunk2 去重");
        // #chunk3 与 #chunk2 相邻但 #chunk2 已被去重 → 保留（链式不传染）
        assertTrue(out.contains("c1#chunk3"));
        assertTrue(out.contains("k1"));
    }

    @Test
    void 某桶不足半额时另一桶补足() {
        List<String> ids = List.of("k1", "k2", "c1#chunk1", "c2#chunk1", "c3#chunk1", "c4#chunk1");
        List<String> out = ContextAssembler.applySourceQuotaAndAdjacency(ids, 6);
        assertEquals(6, out.size(), "无 chunk 被无谓丢弃");
    }
}
