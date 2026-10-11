package com.writing.novel.agent;

import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.prompt.PromptTemplateService;
import com.writing.novel.memory.ContextAssembler;
import com.writing.novel.memory.KnowledgeBaseService;
import com.writing.novel.memory.RelevanceGateway;
import com.writing.novel.memory.SummaryService;
import com.writing.novel.memory.VectorRetrievalService;
import com.writing.novel.service.ClimaxDiversityTracker;
import com.writing.novel.service.EntityGraphService;
import com.writing.novel.service.PlotProgressionDetector;
import com.writing.novel.spi.NovelRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentConfig {

    @Bean
    public WorldBuilderAgent worldBuilderAgent(LlmGateway gateway, KnowledgeBaseService kb,
                                               PromptTemplateService promptService,
                                               com.writing.novel.service.AuthorIntentContextService authorIntentContextService) {
        WorldBuilderAgent agent = new WorldBuilderAgent(gateway, kb, promptService);
        // NCS-034: 世界规划请求携带精确契约版本及内容
        agent.setAuthorIntentContextService(authorIntentContextService);
        return agent;
    }

    @Bean
    public CharacterDesignerAgent characterDesignerAgent(LlmGateway gateway, KnowledgeBaseService kb,
                                                         PromptTemplateService promptService,
                                                         com.writing.novel.service.AuthorIntentContextService authorIntentContextService) {
        CharacterDesignerAgent agent = new CharacterDesignerAgent(gateway, kb, promptService);
        // NCS-034: 人物设计请求携带精确契约版本及内容
        agent.setAuthorIntentContextService(authorIntentContextService);
        return agent;
    }

    @Bean
    public PlotterAgent plotterAgent(LlmGateway gateway, NovelRepository repo,
                                     PromptTemplateService promptService,
                                     com.writing.novel.service.GenreRuleProvider genreRuleProvider,
                                     com.writing.novel.persistence.sqlite.NarrativePolicyRepository narrativePolicyRepository,
                                     com.writing.novel.service.AuthorIntentContextService authorIntentContextService) {
        PlotterAgent plotter = new PlotterAgent(gateway, repo, promptService);
        plotter.setGenreRuleProvider(genreRuleProvider); // p17-03: 卷大纲题材约束
        // NCS-192: 卷大纲节拍数要求读叙事策略（宽松策略保持现状文案）
        plotter.setNarrativePolicyService(
                new com.writing.novel.service.NarrativePolicyService(repo, narrativePolicyRepository));
        // NCS-034: 卷计划请求携带精确契约版本及内容
        plotter.setAuthorIntentContextService(authorIntentContextService);
        return plotter;
    }

    @Bean
    public ChapterWriterAgent chapterWriterAgent(LlmGateway gateway, ContextAssembler assembler,
                                                   NovelRepository repo, VectorRetrievalService vector,
                                                   PromptTemplateService promptService,
                                                   com.writing.novel.service.GenreRuleProvider genreRuleProvider,
                                                   com.writing.novel.llm.config.LlmProperties llmProperties,
                                                   com.writing.novel.service.WriteLeaseService writeLease,
                                                   com.writing.novel.persistence.sqlite.NarrativePolicyRepository narrativePolicyRepository) {
        ChapterWriterAgent agent = new ChapterWriterAgent(gateway, assembler, repo, vector, promptService,
                genreRuleProvider, llmProperties, writeLease);
        // NCS-191: 叙事策略（宽松策略保持现状文案）
        agent.setNarrativePolicyService(
                new com.writing.novel.service.NarrativePolicyService(repo, narrativePolicyRepository));
        return agent;
    }

    @Bean
    public ContinuityCheckerAgent continuityCheckerAgent(LlmGateway gateway, NovelRepository repo,
                                                          PromptTemplateService promptService,
                                                          RelevanceGateway relevanceGateway,
                                                          com.writing.novel.persistence.sqlite.CheckResultRepository checkResultRepository,
                                                          com.writing.novel.persistence.sqlite.InjectionManifestRepository injectionManifestRepository,
                                                          com.writing.novel.spi.StoryCanon storyCanon,
                                                          com.writing.novel.service.AuthorIntentContextService authorIntentContextService) {
        ContinuityCheckerAgent agent = new ContinuityCheckerAgent(gateway, repo, promptService,
                relevanceGateway, checkResultRepository);
        // NCS-097: 封闭式核对段数据源
        agent.setManifestRepository(injectionManifestRepository);
        // NCS-180: 约束清单与真典现值比对
        agent.setStoryCanon(storyCanon);
        // NCS-035: 核查遵从创作约定
        agent.setAuthorIntentContextService(authorIntentContextService);
        return agent;
    }

    @Bean
    public StyleGuardianAgent styleGuardianAgent(LlmGateway gateway, NovelRepository repo,
                                                  PromptTemplateService promptService) {
        return new StyleGuardianAgent(gateway, repo, promptService);
    }

    @Bean
    public MemoryManagerAgent memoryManagerAgent(LlmGateway gateway, SummaryService summaryService,
                                                  PromptTemplateService promptService) {
        return new MemoryManagerAgent(gateway, summaryService, promptService);
    }

    @Bean
    public QualityEvaluationAgent qualityEvaluationAgent(LlmGateway gateway, NovelRepository repo,
                                                         PromptTemplateService promptService,
                                                         RelevanceGateway relevanceGateway) {
        return new QualityEvaluationAgent(gateway, repo, promptService, relevanceGateway);
    }

    @Bean
    public EntityExtractorAgent entityExtractorAgent(LlmGateway gateway, NovelRepository repo,
                                                     PromptTemplateService promptService,
                                                     RelevanceGateway relevanceGateway) {
        return new EntityExtractorAgent(gateway, repo, promptService, relevanceGateway);
    }

    @Bean
    public EntityGraphService entityGraphService(NovelRepository repo, EntityExtractorAgent extractor) {
        return new EntityGraphService(repo, extractor);
    }

    @Bean
    public WritingPreferencesAgent writingPreferencesAgent(LlmGateway gateway, NovelRepository repo,
                                                            PromptTemplateService promptService) {
        return new WritingPreferencesAgent(gateway, repo, promptService);
    }

    @Bean
    public CharacterProfileUpgrader characterProfileUpgrader(LlmGateway gateway, NovelRepository repo,
                                                              PromptTemplateService promptService,
                                                              RelevanceGateway relevanceGateway,
                                                              com.writing.novel.spi.StoryCanon storyCanon) {
        return new CharacterProfileUpgrader(gateway, repo, promptService, relevanceGateway, storyCanon);
    }

    @Bean
    public ClimaxDiversityTracker climaxDiversityTracker() {
        return new ClimaxDiversityTracker();
    }

    @Bean
    public PlotProgressionDetector plotProgressionDetector(NovelRepository repo) {
        return new PlotProgressionDetector(repo);
    }

    @Bean
    public ArcUpdater arcUpdater(LlmGateway gateway, NovelRepository repo,
                                  PromptTemplateService promptService,
                                  com.writing.novel.spi.StoryCanon storyCanon) {
        return new ArcUpdater(gateway, repo, promptService, storyCanon);
    }

    @Bean
    public ForeshadowingExtractor foreshadowingExtractor(LlmGateway gateway, NovelRepository repo,
                                                           PromptTemplateService promptService) {
        return new ForeshadowingExtractor(gateway, repo, promptService);
    }

    @Bean
    public com.writing.novel.service.GenreDetector genreDetector() {
        return new com.writing.novel.service.GenreDetector();
    }

    @Bean
    public com.writing.novel.service.GenreRuleProvider genreRuleProvider() {
        return new com.writing.novel.service.GenreRuleProvider();
    }

    @Bean
    public com.writing.novel.service.CreativeDiversityService creativeDiversityService(NovelRepository repo) {
        return new com.writing.novel.service.CreativeDiversityService(repo);
    }

    @Bean
    public com.writing.novel.service.NarrativeThreadTracker narrativeThreadTracker(NovelRepository repo) {
        return new com.writing.novel.service.NarrativeThreadTracker(repo);
    }

    @Bean
    public NarrativeThreadExtractor narrativeThreadExtractor(LlmGateway gateway,
                                                              com.writing.novel.service.NarrativeThreadTracker tracker,
                                                              PromptTemplateService promptService) {
        return new NarrativeThreadExtractor(gateway, tracker, promptService);
    }

    @Bean
    public ReaderReviewAgent readerReviewAgent(LlmGateway gateway, PromptTemplateService promptService) {
        return new ReaderReviewAgent(gateway, promptService);
    }

    @Bean
    public ForeshadowingResolutionDetector foreshadowingResolutionDetector(LlmGateway gateway,
                                                                             NovelRepository repo,
                                                                             PromptTemplateService promptService,
            com.writing.novel.spi.StoryCanon storyCanon) {
        return new ForeshadowingResolutionDetector(gateway, repo, promptService);
    }

    @Bean
    public SceneClassifier sceneClassifier(LlmGateway gateway, PromptTemplateService promptService) {
        return new SceneClassifier(gateway, promptService);
    }

    @Bean
    public ConsistencyGuard consistencyGuard(LlmGateway gateway, NovelRepository repo,
                                              PromptTemplateService promptService,
                                              RelevanceGateway relevanceGateway,
                                              com.writing.novel.persistence.sqlite.CheckResultRepository checkResults) {
        return new ConsistencyGuard(gateway, repo, promptService, relevanceGateway, checkResults);
    }

    @Bean
    public ForeshadowingImportanceAuditor foreshadowingImportanceAuditor(LlmGateway gateway,
                                                                           NovelRepository repo,
                                                                           PromptTemplateService promptService) {
        return new ForeshadowingImportanceAuditor(gateway, repo, promptService);
    }

    @Bean
    public RelationshipEvolutionTracker relationshipEvolutionTracker(LlmGateway gateway,
                                                                      NovelRepository repo,
                                                                      PromptTemplateService promptService) {
        return new RelationshipEvolutionTracker(gateway, repo, promptService);
    }

    @Bean
    public PassageEditor passageEditor(LlmGateway gateway, PromptTemplateService promptService,
                                       com.writing.novel.service.AuthorIntentContextService authorIntentContextService) {
        PassageEditor editor = new PassageEditor(gateway, promptService);
        // NCS-035: 段落修订遵从创作约定
        editor.setAuthorIntentContextService(authorIntentContextService);
        return editor;
    }

    @Bean
    public PlotMotifTracker plotMotifTracker(LlmGateway gateway, NovelRepository repo,
                                              PromptTemplateService promptService) {
        return new PlotMotifTracker(gateway, repo, promptService);
    }

    @Bean
    public com.writing.novel.service.DiversityTracker diversityTracker() {
        return new com.writing.novel.service.DiversityTracker();
    }
}
