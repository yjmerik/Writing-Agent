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

    public List<VectorSearchResult> search(String queryText, String projectId, int topK) {
        float[] queryVector = embeddingProvider.embed(queryText);
        return vectorStore.search(queryVector, projectId, topK);
    }

    public String getText(String id) {
        return vectorStore.getText(id);
    }

    public void remove(String id) {
        vectorStore.delete(id);
    }
}
