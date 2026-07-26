package com.writing.novel.memory;

import com.writing.novel.service.ClimaxDiversityTracker;
import com.writing.novel.service.DiversityTracker;
import com.writing.novel.service.EntityGraphService;
import com.writing.novel.service.NarrativeThreadTracker;
import com.writing.novel.spi.EmbeddingProvider;
import com.writing.novel.spi.NovelRepository;
import com.writing.novel.spi.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 记忆模块的 Bean 装配。
 * 装配三个记忆服务 + 上下文组装器。
 */
@Configuration
public class MemoryConfig {

    @Bean
    public VectorRetrievalService vectorRetrievalService(VectorStore vectorStore,
                                                          EmbeddingProvider embeddingProvider) {
        return new VectorRetrievalService(vectorStore, embeddingProvider);
    }

    @Bean
    public KnowledgeBaseService knowledgeBaseService(NovelRepository repository,
                                                      VectorRetrievalService vectorRetrieval) {
        return new KnowledgeBaseService(repository, vectorRetrieval);
    }

    @Bean
    public SummaryService summaryService(NovelRepository repository) {
        return new SummaryService(repository);
    }

    @Bean
    public ContextAssembler contextAssembler(NovelRepository repository,
                                              KnowledgeBaseService knowledgeBase,
                                              SummaryService summaryService,
                                              VectorRetrievalService vectorRetrieval,
                                              EntityGraphService entityGraphService,
                                              ClimaxDiversityTracker climaxDiversityTracker,
                                              NarrativeThreadTracker narrativeThreadTracker,
                                              DiversityTracker diversityTracker) {
        return new ContextAssembler(repository, knowledgeBase, summaryService,
                vectorRetrieval, entityGraphService, climaxDiversityTracker,
                narrativeThreadTracker, diversityTracker);
    }
}
