package com.writing.novel.memory;

import com.writing.novel.service.ArcScheduler;
import com.writing.novel.service.ClimaxDiversityTracker;
import com.writing.novel.service.ClicheDetector;
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
                                                      VectorRetrievalService vectorRetrieval,
                                                      MemoryRevision memoryRevision) {
        return new KnowledgeBaseService(repository, vectorRetrieval, memoryRevision);
    }

    @Bean
    public SummaryService summaryService(NovelRepository repository) {
        return new SummaryService(repository);
    }

    @Bean
    public ClicheDetector clicheDetector(NovelRepository repository) {
        return new ClicheDetector(repository);
    }

    @Bean
    public ArcScheduler arcScheduler(NovelRepository repository) {
        return new ArcScheduler(repository);
    }

    @Bean
    public com.writing.novel.service.ConsistencyGate consistencyGate(NovelRepository repository) {
        return new com.writing.novel.service.ConsistencyGate(repository);
    }

    @Bean
    public com.writing.novel.service.ExpositionDetector expositionDetector() {
        return new com.writing.novel.service.ExpositionDetector();
    }

    @Bean
    public com.writing.novel.service.WritingTaskExecutor writingTaskExecutor(
            com.writing.novel.orchestrator.NovelOrchestrator orchestrator,
            NovelRepository repository,
            com.writing.novel.telemetry.CostBudgetMonitor costBudgetMonitor) {
        return new com.writing.novel.service.WritingTaskExecutor(orchestrator, repository, costBudgetMonitor);
    }

    @Bean
    public ContextAssembler contextAssembler(NovelRepository repository,
                                              KnowledgeBaseService knowledgeBase,
                                              SummaryService summaryService,
                                              VectorRetrievalService vectorRetrieval,
                                              EntityGraphService entityGraphService,
                                              ClimaxDiversityTracker climaxDiversityTracker,
                                              NarrativeThreadTracker narrativeThreadTracker,
                                              DiversityTracker diversityTracker,
                                              ClicheDetector clicheDetector,
                                              MemoryRevision memoryRevision,
                                              com.writing.novel.persistence.sqlite.StoryFactRepository storyFactRepository,
                                              RelevanceGateway relevanceGateway) {
        ContextAssembler assembler = new ContextAssembler(repository, knowledgeBase, summaryService,
                vectorRetrieval, entityGraphService, climaxDiversityTracker,
                narrativeThreadTracker, diversityTracker, clicheDetector, memoryRevision);
        assembler.setStoryFactRepository(storyFactRepository);
        assembler.setRelevanceGateway(relevanceGateway);
        return assembler;
    }

    @Bean
    public RelevanceGateway relevanceGateway(NovelRepository repository,
                                              com.writing.novel.persistence.sqlite.StoryFactRepository storyFactRepository) {
        return new RelevanceGateway(repository, storyFactRepository);
    }
}
