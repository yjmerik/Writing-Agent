package com.writing.novel.memory;

import com.writing.novel.spi.EmbeddingProvider;
import com.writing.novel.spi.VectorSearchResult;
import com.writing.novel.spi.VectorStore;

import java.util.List;

/**
 * 第三层记忆：向量检索服务。
 * 封装"文本→向量→存储/检索"流程，上层不直接碰 VectorStore/EmbeddingProvider。
 */
public class VectorRetrievalService {

    private final VectorStore vectorStore;
    private final EmbeddingProvider embeddingProvider;

    public VectorRetrievalService(VectorStore vectorStore, EmbeddingProvider embeddingProvider) {
        this.vectorStore = vectorStore;
        this.embeddingProvider = embeddingProvider;
    }

    public void index(IndexedText text) {
        float[] vector = embeddingProvider.embed(text.text());
        vectorStore.upsert(text.id(), text.projectId(), text.text(), vector);
    }

    /** D2: 批量索引——一次 embedding 调用索引多个文本（~5-8 次/章 → 1 次） */
    public void indexBatch(List<IndexedText> texts) {
        if (texts.isEmpty()) return;
        var vectors = embeddingProvider.embedBatch(texts.stream().map(IndexedText::text).toList());
        for (int i = 0; i < texts.size(); i++) {
            IndexedText t = texts.get(i);
            vectorStore.upsert(t.id(), t.projectId(), t.text(), vectors.get(i));
        }
    }

    public List<VectorSearchResult> search(String queryText, String projectId, int topK) {
        return search(queryText, projectId, topK, 0.0);
    }

    /** NCS-111: 绝对相似度阈值——低于 minScore 的结果不返回（宁缺毋滥，替换 score>=0.0 形同虚设） */
    public List<VectorSearchResult> search(String queryText, String projectId, int topK, double minScore) {
        float[] queryVector = embeddingProvider.embed(queryText);
        return vectorStore.search(queryVector, projectId, topK).stream()
                .filter(r -> r.score() >= minScore)
                .toList();
    }

    public String getText(String id) {
        return vectorStore.getText(id);
    }

    public void remove(String id) {
        vectorStore.delete(id);
    }

    /** p10-w1-03: 按 id 前缀批量删除（章节重写清旧 chunk），返回删除条数 */
    public int removeByIdPrefix(String idPrefix) {
        return vectorStore.deleteByPrefix(idPrefix);
    }

    /** p12-K3: 批量持久化 flush */
    public void flush() {
        vectorStore.flush();
    }
}
