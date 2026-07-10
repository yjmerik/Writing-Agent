package com.writing.novel.memory;

/**
 * 待索引的文本块。sourceType 标识来源（CHARACTER/WORLDBUILDING/CHAPTER/FORESHADOWING）。
 */
public record IndexedText(String id, String projectId, String text, String sourceType) {}
