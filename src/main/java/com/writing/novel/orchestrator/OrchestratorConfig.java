package com.writing.novel.orchestrator;

import com.writing.novel.agent.*;
import com.writing.novel.memory.ContextAssembler;
import com.writing.novel.memory.SummaryService;
import com.writing.novel.service.ClimaxDiversityTracker;
import com.writing.novel.service.CreativeDiversityService;
import com.writing.novel.service.EntityGraphService;
import com.writing.novel.service.GenreDetector;
import com.writing.novel.service.GenreRuleProvider;
import com.writing.novel.service.PlotProgressionDetector;
import com.writing.novel.service.PresetRegistry;
import com.writing.novel.spi.NovelRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OrchestratorConfig {

    @Bean
    public ProjectService projectService(NovelRepository repo, PresetRegistry presetRegistry) {
        return new ProjectService(repo, presetRegistry);
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
                                                 ContextAssembler contextAssembler,
                                                 QualityEvaluationAgent qualityEvaluationAgent,
                                                 EntityGraphService entityGraphService,
                                                 CharacterProfileUpgrader characterProfileUpgrader,
                                                 ClimaxDiversityTracker climaxDiversityTracker,
                                                 PlotProgressionDetector plotProgressionDetector,
                                                 ArcUpdater arcUpdater,
                                                 ForeshadowingExtractor foreshadowingExtractor,
                                                 GenreDetector genreDetector,
                                                 GenreRuleProvider genreRuleProvider,
                                                 CreativeDiversityService creativeDiversityService,
                                                 NarrativeThreadExtractor narrativeThreadExtractor,
                                                 ReaderReviewAgent readerReviewAgent) {
        return new NovelOrchestrator(repo, projectService, worldBuilder, characterDesigner,
                plotter, chapterWriter, continuityChecker, styleGuardian, memoryManager,
                summaryService, contextAssembler, qualityEvaluationAgent, entityGraphService,
                characterProfileUpgrader, climaxDiversityTracker, plotProgressionDetector,
                arcUpdater, foreshadowingExtractor, genreDetector, genreRuleProvider,
                creativeDiversityService, narrativeThreadExtractor, readerReviewAgent);
    }
}
