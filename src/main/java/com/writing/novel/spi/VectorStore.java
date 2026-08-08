package com.writing.novel.spi;

import java.util.List;

/**
 * 向量存储与检索 SPI。
 * MVP 实现：EmbeddedVectorStore（本地文件）；云端：Qdrant/Milvus。
 */
public interface VectorStore {

    /**
     * 存入一个文本块及其向量。
     * @param id         唯一标识（如 chapterId / entryId）
     * @param projectId  所属项目（用于隔离检索范围）
     * @param text       原文（便于取回展示）
     * @param vector     向量
     */
    void upsert(String id, String projectId, String text, float[] vector);

    /** 删除 */
    void delete(String id);

    /**
     * 语义检索 top-K 最相似的文本块。
     * @param queryVector 查询向量
     * @param projectId   限定项目范围
     * @param topK        返回条数
     */
    List<VectorSearchResult> search(float[] queryVector, String projectId, int topK);

    /** 取回某 id 的原文 */
    String getText(String id);

    /**
     * p12-K3: 刷新持久化（批量写入）。
     * 默认空实现（对不需要持久化的实现是 no-op）。
     * EmbeddedVectorStore 覆盖为把内存索引写入文件。
     */
    default void flush() {}
}
