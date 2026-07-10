package com.writing.novel.memory;

import com.writing.novel.domain.Summary;
import com.writing.novel.spi.NovelRepository;

import java.util.List;

/**
 * 第二层记忆：滚动摘要服务。
 * 摘要内容的生成由 MemoryManager Agent（Plan 4）负责，本类只负责存储与查询。
 */
public class SummaryService {

    private final NovelRepository repository;

    public SummaryService(NovelRepository repository) {
        this.repository = repository;
    }

    public void save(Summary summary) {
        repository.saveSummary(summary);
    }

    public List<Summary> findAll(String projectId) {
        return repository.findSummariesByProject(projectId);
    }

    /** 获取覆盖指定章节的所有摘要 */
    public List<Summary> findCovering(String projectId, int chapterIndex) {
        return repository.findSummariesByProject(projectId).stream()
                .filter(s -> s.covers(chapterIndex))
                .toList();
    }

    /**
     * 获取最近一段章节范围的摘要。
     * 返回与范围 [currentChapter - window + 1, currentChapter] 有交集的摘要。
     */
    public List<Summary> findRecent(String projectId, int currentChapter, int window) {
        int from = Math.max(1, currentChapter - window + 1);
        int to = currentChapter;
        return repository.findSummariesByProject(projectId).stream()
                .filter(s -> s.getFromChapter() <= to && s.getToChapter() >= from)
                .toList();
    }
}
