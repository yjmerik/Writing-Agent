package com.writing.novel.spi;

/** 文本向量化 SPI。MVP 调云 API；可换本地模型。 */
public interface EmbeddingProvider {
    /** 将文本转为向量（浮点数组） */
    float[] embed(String text);

    /** 向量维度 */
    int dimension();
}
