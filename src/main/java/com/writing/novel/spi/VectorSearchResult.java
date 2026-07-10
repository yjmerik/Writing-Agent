package com.writing.novel.spi;

/** 向量检索的单条结果 */
public record VectorSearchResult(String id, double score) {}
