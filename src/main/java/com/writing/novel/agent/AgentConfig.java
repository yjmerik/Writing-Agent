package com.writing.novel.agent;

import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.prompt.PromptTemplateService;
import com.writing.novel.memory.ContextAssembler;
import com.writing.novel.memory.KnowledgeBaseService;
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
                                               PromptTemplateService promptService) {
        return new WorldBuilderAgent(gateway, kb, promptService);
    }

    @Bean
    public CharacterDesignerAgent characterDesignerAgent(LlmGateway gateway, KnowledgeBaseService kb,
                                                         PromptTemplateService promptService) {
        return new CharacterDesignerAgent(gateway, kb, promptService);
    }

    @Bean
    public PlotterAgent plotterAgent(LlmGateway gateway, NovelRepository repo,
                                     PromptTemplateService promptService) {
        return new PlotterAgent(gateway, repo, promptService);
    }

    @Bean
    public ChapterWriterAgent chapterWriterAgent(LlmGateway gateway, ContextAssembler assembler,
                                                   NovelRepository repo, VectorRetrievalService vector,
                                                   PromptTemplateService promptService,
                                                   com.writing.novel.service.GenreRuleProvider genreRuleProvider) {
        return new ChapterWriterAgent(gateway, assembler, repo, vector, promptService, genreRuleProvider);
    }

    @Bean
    public ContinuityCheckerAgent continuityCheckerAgent(LlmGateway gateway, NovelRepository repo,
                                                          PromptTemplateService promptService) {
        return new ContinuityCheckerAgent(gateway, repo, promptService);
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
                                                         PromptTemplateService promptService) {
        return new QualityEvaluationAgent(gateway, repo, promptService);
    }

    @Bean
    public EntityExtractorAgent entityExtractorAgent(LlmGateway gateway, NovelRepository repo,
                                                     PromptTemplateService promptService) {
        return new EntityExtractorAgent(gateway, repo, promptService);
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
                                                              PromptTemplateService promptService) {
        return new CharacterProfileUpgrader(gateway, repo, promptService);
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
                                  PromptTemplateService promptService) {
        return new ArcUpdater(gateway, repo, promptService);
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
                                                                             PromptTemplateService promptService) {
        return new ForeshadowingResolutionDetector(gateway, repo, promptService);
    }

    @Bean
    public SceneClassifier sceneClassifier(LlmGateway gateway, PromptTemplateService promptService) {
        return new SceneClassifier(gateway, promptService);
    }

    @Bean
    public ConsistencyGuard consistencyGuard(LlmGateway gateway, NovelRepository repo,
                                              PromptTemplateService promptService) {
        return new ConsistencyGuard(gateway, repo, promptService);
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
    public PassageEditor passageEditor(LlmGateway gateway, PromptTemplateService promptService) {
        return new PassageEditor(gateway, promptService);
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
