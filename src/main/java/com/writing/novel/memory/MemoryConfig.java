package com.writing.novel.memory;

import com.writing.novel.service.ArcScheduler;
import com.writing.novel.service.ClimaxDiversityTracker;
import com.writing.novel.service.ClicheDetector;
import com.writing.novel.service.DiversityTracker;
import com.writing.novel.service.EntityGraphService;
import com.writing.novel.service.NarrativeThreadTracker;
import com.writing.novel.domain.WritingPreset;
import com.writing.novel.spi.EmbeddingProvider;
import com.writing.novel.spi.NovelRepository;
import com.writing.novel.spi.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

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
    public com.writing.novel.service.ConsistencyGate consistencyGate(NovelRepository repository,
                                                                     com.writing.novel.service.PresetRegistry presetRegistry) {
        // NCS-103: preset forbiddenLexicon 按题材并入默认词表（preset 可扩展，无则行为等价）
        Map<com.writing.novel.domain.GenreProfile, List<String>> extra = new java.util.HashMap<>();
        for (WritingPreset preset : presetRegistry.listPresets()) {
            if (preset.getGenreHint() == null || preset.getForbiddenLexicon().isEmpty()) continue;
            extra.put(preset.getGenreHint(), preset.getForbiddenLexicon());
        }
        return extra.isEmpty() ? new com.writing.novel.service.ConsistencyGate(repository)
                : new com.writing.novel.service.ConsistencyGate(repository, extra);
    }

    @Bean
    public com.writing.novel.service.ExpositionDetector expositionDetector() {
        return new com.writing.novel.service.ExpositionDetector();
    }

    @Bean
    public com.writing.novel.service.WritingTaskExecutor writingTaskExecutor(
            com.writing.novel.orchestrator.NovelOrchestrator orchestrator,
            NovelRepository repository,
            com.writing.novel.telemetry.CostBudgetMonitor costBudgetMonitor,
            com.writing.novel.service.WriteLeaseService writeLease) {
        return new com.writing.novel.service.WritingTaskExecutor(orchestrator, repository, costBudgetMonitor, writeLease);
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
                                              com.writing.novel.spi.StoryCanon storyCanon,
                                              RelevanceGateway relevanceGateway,
                                              com.writing.novel.service.AuthorIntentContextService authorIntentContextService,
                                              com.writing.novel.persistence.sqlite.InjectionManifestRepository injectionManifestRepository,
                                              RecallFeedbackService recallFeedbackService) {
        ContextAssembler assembler = new ContextAssembler(repository, knowledgeBase, summaryService,
                vectorRetrieval, entityGraphService, climaxDiversityTracker,
                narrativeThreadTracker, diversityTracker, clicheDetector, memoryRevision);
        // p37-10: 有效作者要求注入生成上下文
        assembler.setDirectiveContextService(new com.writing.novel.service.DirectiveContextService(repository));
        assembler.setAuthorIntentContextService(authorIntentContextService);
        assembler.setStoryCanon(storyCanon);
        assembler.setRelevanceGateway(relevanceGateway);
        // NCS-096: 注入清单随组装落库
        assembler.setManifestStore(injectionManifestRepository);
        // NCS-114: 召回命中率调节真典 cap
        assembler.setRecallFeedbackService(recallFeedbackService);
        return assembler;
    }

    @Bean
    public RelevanceGateway relevanceGateway(NovelRepository repository,
                                              com.writing.novel.spi.StoryCanon storyCanon) {
        return new RelevanceGateway(repository, storyCanon);
    }
}
