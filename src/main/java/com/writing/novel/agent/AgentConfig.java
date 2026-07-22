package com.writing.novel.agent;

import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.prompt.PromptTemplateService;
import com.writing.novel.memory.ContextAssembler;
import com.writing.novel.memory.KnowledgeBaseService;
import com.writing.novel.memory.SummaryService;
import com.writing.novel.memory.VectorRetrievalService;
import com.writing.novel.service.EntityGraphService;
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
                                                   PromptTemplateService promptService) {
        return new ChapterWriterAgent(gateway, assembler, repo, vector, promptService);
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
}
