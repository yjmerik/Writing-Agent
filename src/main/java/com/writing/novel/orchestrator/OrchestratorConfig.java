package com.writing.novel.orchestrator;

import com.writing.novel.agent.*;
import com.writing.novel.memory.ContextAssembler;
import com.writing.novel.memory.SummaryService;
import com.writing.novel.spi.NovelRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OrchestratorConfig {

    @Bean
    public ProjectService projectService(NovelRepository repo) {
        return new ProjectService(repo);
    }

    @Bean
    public NovelOrchestrator novelOrchestrator(NovelRepository repo,
                                                 ProjectService projectService,
                                                 WorldBuilderAgent worldBuilder,
                                                 CharacterDesignerAgent characterDesigner,
                                                 PlotterAgent plotter,
                                                 ChapterWriterAgent chapterWriter,
                                                 ContinuityCheckerAgent continuityChecker,
                                                 StyleGuardianAgent styleGuardian,
                                                 MemoryManagerAgent memoryManager,
                                                 SummaryService summaryService,
                                                 ContextAssembler contextAssembler) {
        return new NovelOrchestrator(repo, projectService, worldBuilder, characterDesigner,
                plotter, chapterWriter, continuityChecker, styleGuardian, memoryManager,
                summaryService, contextAssembler);
    }
}
