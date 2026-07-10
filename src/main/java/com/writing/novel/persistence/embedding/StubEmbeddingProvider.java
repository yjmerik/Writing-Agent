package com.writing.novel.persistence.embedding;

import com.writing.novel.spi.EmbeddingProvider;

import java.nio.charset.StandardCharsets;

/**
 * 确定性的占位 Embedding 实现。
 * 基于文本字节哈希生成固定 64 维向量，仅用于测试与离线开发。
 * 真实实现在 Plan 2（LLM Gateway）接入云 API。
 */
public class StubEmbeddingProvider implements EmbeddingProvider {

    private static final int DIM = 64;

    @Override
    public float[] embed(String text) {
        float[] v = new float[DIM];
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i++) {
            v[i % DIM] += bytes[i];
        }
        // 归一化（L1 范数：绝对值之和）
        double norm = 0;
        for (int i = 0; i < DIM; i++) norm += Math.abs(v[i]);
        if (norm == 0) return v;
        for (int i = 0; i < DIM; i++) v[i] /= norm;
        return v;
    }

    @Override
    public int dimension() { return DIM; }
}
