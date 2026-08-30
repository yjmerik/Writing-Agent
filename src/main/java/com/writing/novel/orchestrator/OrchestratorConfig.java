package com.writing.novel.orchestrator;

import com.writing.novel.agent.*;
import com.writing.novel.memory.ContextAssembler;
import com.writing.novel.memory.SummaryService;
import com.writing.novel.service.ClimaxDiversityTracker;
import com.writing.novel.service.CanonPatchService;
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
                                                 ReaderReviewAgent readerReviewAgent,
                                                 ForeshadowingResolutionDetector foreshadowingResolutionDetector,
                                                 SceneClassifier sceneClassifier,
                                                 com.writing.novel.service.DiversityTracker diversityTracker,
                                                 com.writing.novel.service.ArcScheduler arcScheduler,
                                                 ConsistencyGuard consistencyGuard,
                                                 com.writing.novel.service.ConsistencyGate consistencyGate,
                                                 ForeshadowingImportanceAuditor foreshadowingImportanceAuditor,
                                                 com.writing.novel.service.ClicheDetector clicheDetector,
                                                 RelationshipEvolutionTracker relationshipEvolutionTracker,
                                                 PassageEditor passageEditor,
                                                 PlotMotifTracker plotMotifTracker,
                                                 com.writing.novel.service.ExpositionDetector expositionDetector,
                                                 com.writing.novel.persistence.sqlite.StoryFactRepository storyFactRepository,
                                                 com.writing.novel.agent.FactHarvesterAgent factHarvester,
                                                 com.writing.novel.service.ArchiveCompactor archiveCompactor,
                                                 com.writing.novel.telemetry.CostBudgetMonitor costBudgetMonitor,
                                                 com.writing.novel.memory.RelevanceGateway relevanceGateway,
                                                 com.writing.novel.service.RepairArbiter repairArbiter,
                                                 com.writing.novel.service.ConceiveGate conceiveGate,
                                                 com.writing.novel.service.PlanGate planGate,
                                                  com.writing.novel.service.QualityCouncil qualityCouncil,
                                                  com.writing.novel.service.Arbiter arbiter,
                                                  com.writing.novel.persistence.sqlite.PostProcessLedgerRepository postProcessLedger) {
        NovelOrchestrator orchestrator = new NovelOrchestrator(repo, projectService, worldBuilder, characterDesigner,
                plotter, chapterWriter, continuityChecker, styleGuardian, memoryManager,
                summaryService, contextAssembler, qualityEvaluationAgent, entityGraphService,
                characterProfileUpgrader, climaxDiversityTracker, plotProgressionDetector,
                arcUpdater, foreshadowingExtractor, genreDetector, genreRuleProvider,
                creativeDiversityService, narrativeThreadExtractor, readerReviewAgent,
                foreshadowingResolutionDetector, sceneClassifier, diversityTracker, arcScheduler,
                consistencyGuard, consistencyGate, foreshadowingImportanceAuditor, clicheDetector,
                relationshipEvolutionTracker, passageEditor, plotMotifTracker, expositionDetector);
        orchestrator.setStoryFactRepository(storyFactRepository);
        orchestrator.setFactHarvester(factHarvester);
        orchestrator.setArchiveCompactor(archiveCompactor);
        orchestrator.setCostBudgetMonitor(costBudgetMonitor);
        orchestrator.setRelevanceGateway(relevanceGateway);
        orchestrator.setRepairArbiter(repairArbiter);
        orchestrator.setConceiveGate(conceiveGate);
        orchestrator.setPlanGate(planGate);
        orchestrator.setQualityCouncil(qualityCouncil);
        orchestrator.setArbiter(arbiter);
        // p20-04: 后处理阶段账本——重启续跑只补未完成阶段
        orchestrator.setPostProcessLedger(postProcessLedger);
        return orchestrator;
    }

    /**
     * p20-04: 启动清扫——进程崩溃后残留的 POST_PROCESSING 章节，依账本续跑未完成阶段。
     */
    @Bean
    public org.springframework.boot.ApplicationRunner postProcessRecoveryRunner(NovelOrchestrator orchestrator) {
        return args -> {
            try {
                orchestrator.resumeAllInterruptedPostProcessing();
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger(OrchestratorConfig.class)
                        .warn("启动后处理清扫失败: {}", e.getMessage());
            }
        };
    }

    @Bean
    public com.writing.novel.agent.FactHarvesterAgent factHarvesterAgent(
            com.writing.novel.llm.LlmGateway llmGateway,
            com.writing.novel.persistence.sqlite.StoryFactRepository storyFactRepository,
            com.writing.novel.llm.prompt.PromptTemplateService promptTemplateService,
            EntityGraphService entityGraphService,
            CanonPatchService canonPatchService) {
        com.writing.novel.agent.FactHarvesterAgent factHarvester =
                new com.writing.novel.agent.FactHarvesterAgent(llmGateway, storyFactRepository, promptTemplateService);
        factHarvester.setEntityGraphService(entityGraphService); // p19-02: subject 别名规范化
        factHarvester.setCanonPatchService(canonPatchService); // p20-07: HUMAN_PATCH 自动冲突保护
        return factHarvester;
    }
}
