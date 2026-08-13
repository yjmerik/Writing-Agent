package com.writing.novel.spi;

import java.util.List;

/** 文本向量化 SPI。MVP 调云 API；可换本地模型。 */
public interface EmbeddingProvider {
    /** 将文本转为向量（浮点数组） */
    float[] embed(String text);

    /** D2: 批量向量化——一次 HTTP 调用嵌入多个文本（OpenAI 兼容 input:array） */
    default List<float[]> embedBatch(List<String> texts) {
        return texts.stream().map(this::embed).toList(); // 默认逐个（兼容旧实现）
    }

    /** 向量维度 */
    int dimension();
}
