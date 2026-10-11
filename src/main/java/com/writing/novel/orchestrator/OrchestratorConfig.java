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
    public ProjectService projectService(NovelRepository repo, PresetRegistry presetRegistry,
                                         GenreRuleProvider genreRuleProvider) {
        return new ProjectService(repo, presetRegistry, genreRuleProvider);
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
                                                 com.writing.novel.spi.StoryCanon storyCanon,
                                                 com.writing.novel.agent.FactHarvesterAgent factHarvester,
                                                 com.writing.novel.service.ArchiveCompactor archiveCompactor,
                                                 com.writing.novel.telemetry.CostBudgetMonitor costBudgetMonitor,
                                                 com.writing.novel.memory.RelevanceGateway relevanceGateway,
                                                 com.writing.novel.service.RepairArbiter repairArbiter,
                                                 com.writing.novel.service.ConceiveGate conceiveGate,
                                                 com.writing.novel.service.PlanGate planGate,
                                                  com.writing.novel.service.QualityCouncil qualityCouncil,
                                                  com.writing.novel.service.Arbiter arbiter,
                                                  com.writing.novel.persistence.sqlite.PostProcessLedgerRepository postProcessLedger,
                                                  com.writing.novel.agent.DirectiveEvidenceAgent directiveEvidenceAgent,
                                                  com.writing.novel.service.WriteLeaseService writeLease,
                                                  com.writing.novel.persistence.sqlite.CheckResultRepository checkResultRepository,
                                                  com.writing.novel.service.VolumeReadbackService volumeReadbackService,
                                                  com.writing.novel.service.AestheticProfileService aestheticProfileService,
                                                  com.writing.novel.service.ScenePlanService scenePlanService,
                                                  com.writing.novel.persistence.sqlite.NarrativePolicyRepository narrativePolicyRepository,
                                                  com.writing.novel.persistence.sqlite.ConnectionProvider connectionProvider,
                                                  com.writing.novel.service.VolumePacingController volumePacingController) {
        NovelOrchestrator orchestrator = new NovelOrchestrator(repo, projectService, worldBuilder, characterDesigner,
                plotter, chapterWriter, continuityChecker, styleGuardian, memoryManager,
                summaryService, contextAssembler, qualityEvaluationAgent, entityGraphService,
                characterProfileUpgrader, climaxDiversityTracker, plotProgressionDetector,
                arcUpdater, foreshadowingExtractor, genreDetector, genreRuleProvider,
                creativeDiversityService, narrativeThreadExtractor, readerReviewAgent,
                foreshadowingResolutionDetector, sceneClassifier, diversityTracker, arcScheduler,
                consistencyGuard, consistencyGate, foreshadowingImportanceAuditor, clicheDetector,
                relationshipEvolutionTracker, passageEditor, plotMotifTracker, expositionDetector,
                // p22-05: 生命周期策略组合——生产组合显式给全，缺失即启动失败并指名能力
                new OrchestratorPolicies(
                        requirePolicy(storyCanon, "StoryCanon"),
                        requirePolicy(relevanceGateway, "RelevanceGateway"),
                        requirePolicy(repairArbiter, "RepairArbiter"),
                        requirePolicy(conceiveGate, "ConceiveGate"),
                        requirePolicy(planGate, "PlanGate"),
                        requirePolicy(qualityCouncil, "QualityCouncil"),
                        requirePolicy(arbiter, "Arbiter"),
                        requirePolicy(factHarvester, "FactHarvesterAgent"),
                        requirePolicy(archiveCompactor, "ArchiveCompactor"),
                        requirePolicy(costBudgetMonitor, "CostBudgetMonitor"),
                        requirePolicy(postProcessLedger, "PostProcessLedgerRepository")));
        // NCS-123: 检测器产出趋势门禁（卷级聚合回流）
        orchestrator.setDetectorTrendService(
                new com.writing.novel.service.DetectorTrendService(repo));
        // p37-16: 章节完成后作者要求逐章核对
        orchestrator.setDirectiveEvidenceService(new com.writing.novel.service.DirectiveEvidenceService(repo, directiveEvidenceAgent));
        // NCS-001: 写入门禁（epoch 租约）——后处理等生产模块经此读取
        orchestrator.setWriteLeaseService(writeLease);
        // NCS-007: 检查结果信封——成书门禁"尚未查清"阻塞
        orchestrator.setCheckResultRepository(checkResultRepository);
        // NCS-020: 卷末回读——未确认阻断下一卷自动续写
        orchestrator.setVolumeReadbackService(volumeReadbackService);
        // NCS-022: 审美档案条件注入
        orchestrator.setAestheticInjection(aestheticProfileService::buildInjection);
        // NCS-193: 写前场景规划（产出落梗概；写作消费归 NCS-194）
        orchestrator.setScenePlanService(scenePlanService);
        // NCS-195: 双向质感门禁——解析叙事策略（宽松策略门禁自动关闭）
        orchestrator.setTexturePolicyService(new com.writing.novel.service.NarrativePolicyService(
                repo, narrativePolicyRepository));
        // NCS-196: 质感单项定点扩写编辑器
        orchestrator.setTexturePassageEditor(passageEditor);
        // NCS-200: 卷级节奏闭环（单例 bean，MemoryConfig 的 assembler 同源注入）
        orchestrator.setVolumePacingController(volumePacingController);
        // NCS-184: 回报预算器（兑付缺口 → PLOT HIGH → 重写；读者评审钩子摘要）
        orchestrator.production().setPayoffLedger(
                new com.writing.novel.service.PayoffLedger(repo));
        return orchestrator;
    }

    /** NCS-200: 卷级节奏闭环控制器（单例——EventLog 纹理缓冲全局唯一） */
    @Bean
    public com.writing.novel.service.VolumePacingController volumePacingController(
            com.writing.novel.persistence.sqlite.ConnectionProvider connectionProvider,
            NovelRepository repo,
            com.writing.novel.service.VolumeReadbackService volumeReadbackService) {
        return new com.writing.novel.service.VolumePacingController(
                new com.writing.novel.persistence.sqlite.VolumePacingDirectiveRepository(connectionProvider),
                repo, volumeReadbackService);
    }

    /** p22-05: 必需策略校验——缺失能力让 Spring 启动失败并指名（fail-loud 组合） */
    static <T> T requirePolicy(T policy, String capability) {
        if (policy == null) {
            throw new IllegalStateException("生命周期策略缺失: " + capability
                    + " ——生产组合必须显式提供该能力（p22-05）");
        }
        return policy;
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
            com.writing.novel.spi.StoryCanon storyCanon,
            com.writing.novel.llm.prompt.PromptTemplateService promptTemplateService,
            EntityGraphService entityGraphService,
            com.writing.novel.memory.RecallFeedbackService recallFeedbackService,
            com.writing.novel.persistence.sqlite.InjectionManifestRepository injectionManifestRepository) {
        com.writing.novel.agent.FactHarvesterAgent factHarvester =
                new com.writing.novel.agent.FactHarvesterAgent(llmGateway, storyCanon, promptTemplateService);
        factHarvester.setEntityGraphService(entityGraphService); // p19-02: subject 别名规范化
        // NCS-114: 反馈回路——收割结果对注入清单的命中率统计
        factHarvester.setRecallFeedback(recallFeedbackService, injectionManifestRepository);
        // p20-07 的 HUMAN_PATCH 保护已内置于 StoryCanon.harvest（p22-01/02 迁移）
        return factHarvester;
    }

    @Bean
    public com.writing.novel.memory.RecallFeedbackService recallFeedbackService(
            com.writing.novel.persistence.sqlite.RecallFeedbackRepository repository) {
        return new com.writing.novel.memory.RecallFeedbackService(repository);
    }
}
