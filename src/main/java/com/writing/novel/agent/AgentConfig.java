package com.writing.novel.agent;

import com.writing.novel.llm.LlmGateway;
import com.writing.novel.memory.ContextAssembler;
import com.writing.novel.memory.KnowledgeBaseService;
import com.writing.novel.memory.SummaryService;
import com.writing.novel.memory.VectorRetrievalService;
import com.writing.novel.spi.NovelRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentConfig {

    @Bean
    public WorldBuilderAgent worldBuilderAgent(LlmGateway gateway, KnowledgeBaseService kb) {
        return new WorldBuilderAgent(gateway, kb);
    }

    @Bean
    public CharacterDesignerAgent characterDesignerAgent(LlmGateway gateway, KnowledgeBaseService kb) {
        return new CharacterDesignerAgent(gateway, kb);
    }

    @Bean
    public PlotterAgent plotterAgent(LlmGateway gateway, NovelRepository repo) {
        return new PlotterAgent(gateway, repo);
    }

    @Bean
    public ChapterWriterAgent chapterWriterAgent(LlmGateway gateway, ContextAssembler assembler,
                                                   NovelRepository repo, VectorRetrievalService vector) {
        return new ChapterWriterAgent(gateway, assembler, repo, vector);
    }

    @Bean
    public ContinuityCheckerAgent continuityCheckerAgent(LlmGateway gateway, NovelRepository repo) {
        return new ContinuityCheckerAgent(gateway, repo);
    }

    @Bean
    public StyleGuardianAgent styleGuardianAgent(LlmGateway gateway, NovelRepository repo) {
        return new StyleGuardianAgent(gateway, repo);
    }

    @Bean
    public MemoryManagerAgent memoryManagerAgent(LlmGateway gateway, SummaryService summaryService) {
        return new MemoryManagerAgent(gateway, summaryService);
    }

    @Bean
    public QualityEvaluationAgent qualityEvaluationAgent(LlmGateway gateway, NovelRepository repo) {
        return new QualityEvaluationAgent(gateway, repo);
    }
}
