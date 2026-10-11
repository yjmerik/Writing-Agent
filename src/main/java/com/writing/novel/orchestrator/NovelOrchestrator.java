package com.writing.novel.orchestrator;

import com.writing.novel.agent.*;
import com.writing.novel.domain.*;
import com.writing.novel.memory.ContextAssembler;
import com.writing.novel.memory.SummaryService;
import com.writing.novel.service.ClimaxDiversityTracker;
import com.writing.novel.service.ChapterRevisionService;
import com.writing.novel.service.CreativeDiversityService;
import com.writing.novel.service.EntityGraphService;
import com.writing.novel.service.GenreDetector;
import com.writing.novel.service.GenreRuleProvider;
import com.writing.novel.service.PlotProgressionDetector;
import com.writing.novel.spi.NovelRepository;
import com.writing.novel.telemetry.EventContext;
import com.writing.novel.telemetry.EventLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * 小说编排器——系统大脑。驱动状态机，调度 7 个 Agent。
 */
public class NovelOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(NovelOrchestrator.class);
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();
    private static final int INITIAL_SETUP_SUMMARY_LIMIT = 300;
    private static final java.util.concurrent.ExecutorService POST_PROCESS =
            java.util.concurrent.Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "chapter-postprocess");
                t.setDaemon(true);
                return t;
            });

    private final NovelRepository repository;
    /** NCS-154: 人物卡修订预览-确认链（内存态；web 层经访问器使用同一实例） */
    private final com.writing.novel.service.CharacterRevisionPreviewService characterRevisionPreviews;
    private final ProjectService projectService;
    private final WorldBuilderAgent worldBuilder;
    private final CharacterDesignerAgent characterDesigner;
    private final PlotterAgent plotter;
    private final ChapterWriterAgent chapterWriter;
    private final ContinuityCheckerAgent continuityChecker;
    private final StyleGuardianAgent styleGuardian;
    private final MemoryManagerAgent memoryManager;
    private final SummaryService summaryService;
    private final ContextAssembler contextAssembler;
    private final com.writing.novel.agent.QualityEvaluationAgent qualityEvaluationAgent;
    private final EntityGraphService entityGraphService;
    private final CharacterProfileUpgrader characterProfileUpgrader;
    private final ClimaxDiversityTracker climaxDiversityTracker;
    private final PlotProgressionDetector plotProgressionDetector;
    private final ArcUpdater arcUpdater;
    private final ForeshadowingExtractor foreshadowingExtractor;
    private final GenreDetector genreDetector;
    private final GenreRuleProvider genreRuleProvider;
    private final CreativeDiversityService creativeDiversityService;
    private final NarrativeThreadExtractor narrativeThreadExtractor;
    private final ReaderReviewAgent readerReviewAgent;
    private final ForeshadowingResolutionDetector foreshadowingResolutionDetector;
    /** NCS-123: 检测器产出趋势门禁（可选；null 跳过；生产经 setDetectorTrendService 注入） */
    private com.writing.novel.service.DetectorTrendService detectorTrendService;

    /** NCS-123: 趋势门禁注入 */
    public void setDetectorTrendService(com.writing.novel.service.DetectorTrendService service) {
        this.detectorTrendService = service;
    }
    private final SceneClassifier sceneClassifier;
    private final com.writing.novel.service.DiversityTracker diversityTracker;
    private final com.writing.novel.service.ArcScheduler arcScheduler;
    private final ConsistencyGuard consistencyGuard;
    private final com.writing.novel.service.ConsistencyGate consistencyGate;
    private final ForeshadowingImportanceAuditor foreshadowingImportanceAuditor;
    private final com.writing.novel.service.ClicheDetector clicheDetector;
    private final RelationshipEvolutionTracker relationshipEvolutionTracker;
    private final PassageEditor passageEditor;
    private final PlotMotifTracker plotMotifTracker;
    private final com.writing.novel.service.ExpositionDetector expositionDetector;
    /** p10-w2-02/p22-02: 故事真典端口——null 时跳过播种/收割（测试降级组合） */
    private final com.writing.novel.spi.StoryCanon storyCanon;
    private final com.writing.novel.memory.RelevanceGateway relevanceGateway; // p11-q0-01: 检索收窄门面
    private final com.writing.novel.service.RepairArbiter repairArbiter; // p11-q0-04: 闭环三分类仲裁
    private final com.writing.novel.service.ConceiveGate conceiveGate; // p11-q1-01: 构思期质量门禁
    private final com.writing.novel.service.PlanGate planGate; // p11-q1-04: 章节梗概预检
    private final com.writing.novel.service.QualityCouncil qualityCouncil; // p11-q2-03: 质检委员会合并重写
    private final com.writing.novel.service.Arbiter arbiter;
    /** p10-w2-03: 事实收割 Agent——null 时跳过（测试降级组合） */
    private final com.writing.novel.agent.FactHarvesterAgent factHarvester;
    /** p20-02: 章节后处理模块 */
    private final ChapterPostProcessor postProcessor;
    /** p22-04: 章节生产模块（Context→Write→Quality→Rewrite） */
    private final ChapterProduction production;
    /** p10-w3-03: 归档衰减器——null 时跳过（测试降级组合） */
    private final com.writing.novel.service.ArchiveCompactor archiveCompactor;
    /** p10-w3-09: 成本预算监控器——null 时跳过成本回执（测试降级组合） */
    private final com.writing.novel.telemetry.CostBudgetMonitor costBudgetMonitor;
    /** p26-12: all chapter content replacements persist the pre-change immutable version first. */
    private final ChapterRevisionService chapterRevisionService;

    // p37-16: 章节完成后作者要求证据服务（可选注入；未注入不调用额外模型）
    private volatile com.writing.novel.service.DirectiveEvidenceService directiveEvidenceService;

    // NCS-001: 写入门禁（epoch 租约）——可选注入；未注入不设防（测试装配）
    private volatile com.writing.novel.service.WriteLeaseService writeLease;

    /** NCS-001: 注入写入门禁（生产装配用）。 */
    public void setWriteLeaseService(com.writing.novel.service.WriteLeaseService s) {
        this.writeLease = s;
    }

    /** NCS-001: 后处理等生产模块读取写入门禁。 */
    com.writing.novel.service.WriteLeaseService writeLeaseRef() { return writeLease; }

    // NCS-007: 检查结果信封（成书门禁"尚未查清"阻塞）；可选注入
    private volatile com.writing.novel.persistence.sqlite.CheckResultRepository checkResultRepository;

    // NCS-020: 卷末回读（未确认阻断下一卷自动续写）；可选注入
    private volatile com.writing.novel.service.VolumeReadbackService volumeReadbackService;

    /** NCS-020: 注入卷末回读服务（生产装配用）。 */
    public void setVolumeReadbackService(com.writing.novel.service.VolumeReadbackService s) {
        this.volumeReadbackService = s;
    }

    /** NCS-022: 注入审美档案文本函数（生产装配转发到生产模块）。 */
    public void setAestheticInjection(java.util.function.Function<String, String> injection) {
        if (this.production != null) {
            this.production.setAestheticInjection(injection);
        }
    }

    /** NCS-193: 注入写前场景规划服务（转发到生产模块；null=能力关闭）。 */
    public void setScenePlanService(com.writing.novel.service.ScenePlanService service) {
        if (this.production != null) {
            this.production.setScenePlanService(service);
        }
    }

    /** NCS-195: 注入叙事策略解析（质感门禁用，转发到生产模块；null=门禁关闭）。 */
    public void setTexturePolicyService(com.writing.novel.service.NarrativePolicyService service) {
        if (this.production != null) {
            this.production.setTexturePolicyService(service);
        }
    }

    /** NCS-195/196: 注入质感单项扩写通道（NCS-196 定点扩写实现；null=事件化降级）。 */
    public void setTextureExpansionChannel(
            java.util.function.BiConsumer<String, java.util.List<String>> channel) {
        if (this.production != null) {
            this.production.setTextureExpansionChannel(channel);
        }
    }

    /** NCS-007: 注入检查结果信封仓储（生产装配用）。 */
    public void setCheckResultRepository(com.writing.novel.persistence.sqlite.CheckResultRepository repo) {
        this.checkResultRepository = repo;
    }

    /** p37-16: 注入意见证据服务（生产装配用）。 */
    public void setDirectiveEvidenceService(com.writing.novel.service.DirectiveEvidenceService s) {
        this.directiveEvidenceService = s;
    }

    /** p37-16: 当前稿产出并完成检查后核对作者要求；失败仅留事件，要求保持未验证。 */
    public void captureDirectiveEvidenceAfterGeneration(String projectId, int chapterIndex) {
        if (directiveEvidenceService == null) return;
        try {
            directiveEvidenceService.captureEvidence(projectId, chapterIndex);
        } catch (Exception e) {
            log.warn("意见证据核对失败（要求保持未验证）: projectId={}, ch={}, err={}",
                    projectId, chapterIndex, e.getMessage());
            com.writing.novel.telemetry.EventLog.emit("DIRECTIVE_EVIDENCE_FAILED", java.util.Map.of(
                    "projectId", projectId,
                    "chapterIndex", chapterIndex,
                    "errorClass", e.getClass().getSimpleName(),
                    "message", String.valueOf(e.getMessage())));
        }
    }

    /** NCS-154: 人物卡修订预览-确认链访问器（web 层 confirm/discard 入口） */
    public com.writing.novel.service.CharacterRevisionPreviewService characterRevisionPreviews() {
        return characterRevisionPreviews;
    }

    public NovelOrchestrator(NovelRepository repository,
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
                              com.writing.novel.agent.QualityEvaluationAgent qualityEvaluationAgent,
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
                              OrchestratorPolicies policies) {
        this.repository = repository;
        this.characterRevisionPreviews = new com.writing.novel.service.CharacterRevisionPreviewService(repository);
        this.projectService = projectService;
        this.worldBuilder = worldBuilder;
        this.characterDesigner = characterDesigner;
        this.plotter = plotter;
        this.chapterWriter = chapterWriter;
        this.continuityChecker = continuityChecker;
        this.styleGuardian = styleGuardian;
        this.memoryManager = memoryManager;
        this.summaryService = summaryService;
        this.contextAssembler = contextAssembler;
        this.qualityEvaluationAgent = qualityEvaluationAgent;
        this.entityGraphService = entityGraphService;
        this.characterProfileUpgrader = characterProfileUpgrader;
        this.climaxDiversityTracker = climaxDiversityTracker;
        this.plotProgressionDetector = plotProgressionDetector;
        this.arcUpdater = arcUpdater;
        this.foreshadowingExtractor = foreshadowingExtractor;
        this.genreDetector = genreDetector;
        this.genreRuleProvider = genreRuleProvider;
        this.creativeDiversityService = creativeDiversityService;
        this.narrativeThreadExtractor = narrativeThreadExtractor;
        this.readerReviewAgent = readerReviewAgent;
        this.foreshadowingResolutionDetector = foreshadowingResolutionDetector;
        this.sceneClassifier = sceneClassifier;
        this.diversityTracker = diversityTracker;
        this.arcScheduler = arcScheduler;
        this.consistencyGuard = consistencyGuard;
        this.consistencyGate = consistencyGate;
        this.foreshadowingImportanceAuditor = foreshadowingImportanceAuditor;
        this.clicheDetector = clicheDetector;
        this.relationshipEvolutionTracker = relationshipEvolutionTracker;
        this.passageEditor = passageEditor;
        this.plotMotifTracker = plotMotifTracker;
        this.expositionDetector = expositionDetector;
        // p22-05: 生命周期策略包——生产组合显式给全（null 包仅测试降级）
        OrchestratorPolicies pol = policies != null ? policies : OrchestratorPolicies.none();
        this.storyCanon = pol.storyCanon();
        this.relevanceGateway = pol.relevanceGateway();
        this.repairArbiter = pol.repairArbiter();
        this.conceiveGate = pol.conceiveGate();
        this.planGate = pol.planGate();
        this.qualityCouncil = pol.qualityCouncil();
        this.arbiter = pol.arbiter();
        this.factHarvester = pol.factHarvester();
        this.archiveCompactor = pol.archiveCompactor();
        this.costBudgetMonitor = pol.costBudgetMonitor();
        this.chapterRevisionService = new ChapterRevisionService(repository);
        // p22-04: 章节生产模块——写前装配/正文生成/质量链/纠偏重写经此模块
        this.production = new ChapterProduction(this, repository, chapterWriter,
                continuityChecker, readerReviewAgent, consistencyGuard, consistencyGate,
                expositionDetector, styleGuardian, foreshadowingResolutionDetector,
                arcScheduler, plotMotifTracker, planGate);
        // p20-02: 后处理模块——正文落库后的全部加工阶段经此模块编排
        this.postProcessor = new ChapterPostProcessor(this, repository, chapterWriter,
                entityGraphService, narrativeThreadExtractor, readerReviewAgent,
                foreshadowingExtractor, climaxDiversityTracker, diversityTracker,
                sceneClassifier, arcScheduler, relationshipEvolutionTracker, plotMotifTracker,
                plotProgressionDetector, characterProfileUpgrader, arcUpdater,
                // NCS-122: 关系双轨交叉校验（canon 为 null 的测试装配跳过）
                storyCanon == null ? null
                        : new com.writing.novel.service.RelationshipConsistencyService(repository, storyCanon),
                pol.postProcessLedger());
    }

    /** p20-02: 后处理模块（正文落库后阶段编排） */
    ChapterPostProcessor postProcessor() {
        return postProcessor;
    }

    /**
     * p20-04: 恢复中断的后处理——找出 status=POST_PROCESSING 的章节，
     * 依账本只补跑未完成阶段，终态裁决为 DONE（CONTINUITY 续跑标记的
     * NEEDS_MANUAL_REVIEW 不被覆盖）。返回恢复的章节数。
     */
    public int resumeInterruptedPostProcessing(String projectId) {
        var interrupted = repository.findChaptersByProject(projectId).stream()
                .filter(c -> c.getStatus() == Chapter.Status.POST_PROCESSING)
                .toList();
        if (interrupted.isEmpty()) return 0;
        Outline outline = repository.findOutlineByProject(projectId).orElse(null);
        if (outline == null) return 0;
        for (Chapter ch : interrupted) {
            var synopsis = findSynopsis(outline, ch.getChapterIndex());
            postProcessor.resume(projectId, ch, ch.getChapterIndex(), synopsis, outline);
            if (ch.getStatus() == Chapter.Status.POST_PROCESSING) {
                ch.setStatus(Chapter.Status.DONE);
                repository.saveChapter(ch);
                log.info("章{}中断后处理已续跑完成 → DONE", ch.getChapterIndex());
            }
        }
        return interrupted.size();
    }

    /** p20-04: 启动清扫——全部项目的后处理续跑（进程崩溃后残留 POST_PROCESSING 章节） */
    public int resumeAllInterruptedPostProcessing() {
        int resumed = 0;
        for (Project p : repository.findAllProjects()) {
            try {
                resumed += resumeInterruptedPostProcessing(p.getId());
            } catch (Exception e) {
                log.warn("项目{}后处理续跑失败: {}", p.getId(), e.getMessage());
            }
        }
        if (resumed > 0) {
            log.info("后处理续跑完成: {} 章", resumed);
        }
        return resumed;
    }

    /** p20-02: 模块读取 late-set 的收割 Agent */
    com.writing.novel.agent.FactHarvesterAgent factHarvester() {
        return factHarvester;
    }

    /** p20-02: 模块读取仲裁器存在性（arbiter 接管时 reader review 互斥跳过） */
    boolean hasArbiter() {
        return arbiter != null;
    }

    /** p22-04: 生产模块访问器（postProcessor CONTINUITY 阶段调用质量链） */
    ChapterProduction production() {
        return production;
    }

    // --- p22-04: 生产模块读取 late-set 策略依赖（方法与字段同名在 Java 中合法） ---
    com.writing.novel.spi.StoryCanon storyCanon() { return storyCanon; }
    com.writing.novel.service.RepairArbiter repairArbiter() { return repairArbiter; }
    com.writing.novel.service.QualityCouncil qualityCouncil() { return qualityCouncil; }
    com.writing.novel.service.Arbiter arbiterRef() { return arbiter; }
    com.writing.novel.memory.RelevanceGateway relevanceGateway() { return relevanceGateway; }

    /** p10-w3-03: 注入归档衰减器（@Bean 装配用）；不调则卷末跳过归档 */

    /** p10-w3-09: 注入成本预算监控器（@Bean 装配用）；不调则门禁跳过成本回执 */

    /** 构思阶段：生成世界观 + 人物 + 大纲 */
    public void runConceiving(String projectId) {
        EventContext.setProjectId(projectId);
        try {
            doConceiving(projectId);
            seedStoryBible(projectId); // p10-w2-02: 构思完成后从世界观/人物/时代锚点播种真典
        } finally {
            EventContext.clear();
        }
    }

    /**
     * p10-w2-02: 故事真典播种——从世界观条目 + 初始人物卡 + 时代锚点抽取初始事实入典。
     * 确定性规则抽取（不调 LLM），为长程记忆提供结构化真相基线；后续 FactHarvester 每章增量更新。
     * storyCanon==null（旧测试/未注入）时安全跳过。
     */
    private void seedStoryBible(String projectId) {
        if (storyCanon == null) return;
        try {
            var project = repository.findProject(projectId).orElse(null);
            if (project == null) return;
            int seeded = 0;

            // 1. 世界观条目 → WORLD_RULE（POWER_SYSTEM/RULE/FACTION/HISTORY/CUSTOM）/ OBJECT_STATE（ITEM）
            for (var entry : repository.findKnowledgeEntriesByProject(projectId)) {
                StoryFact.FactType type = storyFactType(entry);
                if (type != null) {
                    seeded += saveFactIfAbsent(projectId, type, entry.getName(),
                            entry.getName() + "：" + entry.getContent(),
                            type == StoryFact.FactType.WORLD_RULE ? 4 : 3);
                }
            }

            // 2. 主角 → CHARACTER_STATE（初始状态，importance=5）
            for (var c : repository.findCharactersByProject(projectId)) {
                String role = c.getRole();
                if (role != null && (role.contains("主角") || role.contains("男主") || role.contains("女主"))) {
                    StringBuilder assertion = new StringBuilder(c.getName() + "（" + role + "）");
                    if (c.getAbility() != null) assertion.append("，初始能力：").append(c.getAbility());
                    if (c.getMotivation() != null) assertion.append("，动机：").append(c.getMotivation());
                    seeded += saveFactIfAbsent(projectId, StoryFact.FactType.CHARACTER_STATE,
                            c.getName(), assertion.toString(), 5);
                }
            }

            // 3. 时代锚点 → WORLD_RULE 铁律（EraAnchor 规则推导，防朝代年号混用）
            var entries = repository.findKnowledgeEntriesByProject(projectId);
            String era = com.writing.novel.service.EraAnchorService.deriveEraAnchor(entries, project.getConcept());
            if (era != null && !era.isBlank() && !era.startsWith("⚠️")) {
                seeded += saveFactIfAbsent(projectId, StoryFact.FactType.WORLD_RULE, "时代锚点",
                        "时代一致性铁律：" + era + "（正文禁止其他朝代年号/器物/官制）", 5);
            }

            log.info("故事真典播种完成(项目{}): {} 条初始事实", projectId, seeded);
        } catch (Exception e) {
            log.warn("故事真典播种失败(项目{}): {}", projectId, e.getMessage());
        }
    }

    /**
     * p10-w2-02: 幂等保存——若已有同 factType+subject 的 ACTIVE 事实则跳过（重复 runConceiving 不重复入库）。
     * 返回 1（新增）或 0（已存在）。
     */
    private int saveFactIfAbsent(String projectId, StoryFact.FactType type, String subject,
                                 String assertion, int importance) {
        StoryFact f = new StoryFact(projectId, type, subject, assertion, 1);
        f.setImportance(importance);
        return storyCanon.saveIfAbsent(f) ? 1 : 0;
    }

    private StoryFact.FactType storyFactType(KnowledgeEntry entry) {
        return switch (entry.getType() == null ? "" : entry.getType()) {
            case "POWER_SYSTEM", "RULE", "FACTION", "HISTORY", "CUSTOM" -> StoryFact.FactType.WORLD_RULE;
            case "ITEM" -> StoryFact.FactType.OBJECT_STATE;
            default -> null;
        };
    }

    private void doConceiving(String projectId) {
        Project p = loadProject(projectId);
        if (p.getState() != NovelState.CONCEIVING && p.getState() != NovelState.PAUSED_AT_CHECKPOINT) {
            throw new IllegalStateException("项目不在构思阶段: " + p.getState());
        }

        // p5-d1: 题材推断（幂等：已有则跳过）
        if (p.getGenre() == null && genreDetector != null) {
            var genre = genreDetector.detect(p.getTags(), p.getConcept());
            p.setGenre(genre);
            repository.saveProject(p);
            log.info("题材推断: {} → {}", p.getTags(), genre.getChineseName());

            // p10.1-e3: 根据题材设置 maxBeatsPerChapter 默认值
            if (genreRuleProvider != null) {
                int maxBeats = genreRuleProvider.getDefaultMaxBeats(genre);
                var prefs = repository.findWritingPreferences(projectId).orElse(new WritingPreferences(projectId));
                prefs.setMaxBeatsPerChapter(maxBeats);
                repository.saveWritingPreferences(prefs);
                log.info("题材密度设置: {} → maxBeats={}", genre.getChineseName(), maxBeats);
            }
        }

        // p8-1: 历史感知——注入 diversityGuide
        String wbDiversityGuide = creativeDiversityService != null
                ? creativeDiversityService.getDiversityGuide(projectId, "WORLDBUILDER") : "";
        worldBuilder.build(projectId, p.getConcept(), p.getTags(), wbDiversityGuide);

        // p11-q1-01: WorldBuilder 门禁——力量体系/势力不重复/完整性校验，不过关带反馈重生成（最多 2 次）
        if (conceiveGate != null) {
            boolean wbPassed = false;
            String wbFeedback = "";
            for (int wbRetry = 0; wbRetry < 2; wbRetry++) {
                var wbEntries = repository.findKnowledgeEntriesByProject(projectId);
                var wbGate = conceiveGate.checkWorldbuilding(wbEntries, p.getTags(), p.getGenre());
                if (wbGate.pass()) { wbPassed = true; break; }
                wbFeedback = wbGate.feedback();
                log.warn("WorldBuilder 门禁未过(重试{}/2): {}", wbRetry + 1, wbFeedback);
                repository.deleteKnowledgeEntriesByProject(projectId); // 清旧避免重生成累积
                worldBuilder.build(projectId, p.getConcept(), p.getTags(),
                        (wbDiversityGuide == null ? "" : wbDiversityGuide + "\n")
                                + "【质量门禁反馈】" + wbFeedback);
            }
            // p11-q1-03: 软门禁——重试上限后仍不过，标记 quality_risk（不阻塞）
            if (!wbPassed) markQualityRisk(projectId, "WorldBuilder", wbFeedback);
        }

        // 记录世界观生成
        if (creativeDiversityService != null) {
            var wbEntries = repository.findKnowledgeEntriesByProject(projectId);
            String wbSummary = wbEntries.stream()
                    .map(e -> e.getType() + ":" + e.getName())
                    .reduce((a, b) -> a + ", " + b).orElse("无");
            creativeDiversityService.record(projectId, "WORLDBUILDER",
                    "世界观维度：" + wbSummary,
                    wbEntries.stream().map(e -> e.getType() + ":" + e.getName())
                            .reduce((a, b) -> a + ";" + b).orElse("[]"), 0);
        }

        // 风格基线前置：在世界观生成后立即建立全书风格基线（幂等：已有则跳过）
        if (repository.findStyleProfileByProject(projectId).isEmpty()) {
            styleGuardian.establishProfile(projectId,
                    "世界观构思：" + p.getConcept() + "\n类型标签：" + p.getTags());
        }

        // 人物设计（幂等：已有主角则跳过）
        if (repository.findCharactersByProject(projectId).isEmpty()) {
            String worldSummary = collectWorldbuildingSummary(projectId);
            String charRequirement = "主角。故事构思：" + p.getConcept()
                    + "；类型标签：" + p.getTags()
                    + (worldSummary.isEmpty() ? "" : "\n已生成的世界观设定：\n" + worldSummary);
            String charDiversity = creativeDiversityService != null
                    ? creativeDiversityService.getDiversityGuide(projectId, "CHARACTER") : "";
            characterDesigner.design(projectId, charRequirement, List.of(), charDiversity);

            // p9-3: 配角群像设计——至少设计 1 个女性角色 + 1 个对手/反派
            var existingChars = repository.findCharactersByProject(projectId);
            final String protagonistSummary = existingChars.stream()
                    .map(c -> c.getName() + "（" + c.getRole() + "）：性格" + (c.getPersonality() != null ? c.getPersonality().substring(0, Math.min(30, c.getPersonality().length())) : "待定"))
                    .reduce((a, b) -> a + "；" + b).orElse("");

            // 设计关键对手/反派
            String antagonistReq = "重要配角/对手或反派。与主角形成对立或互补关系。"
                    + "已有人物：" + protagonistSummary
                    + "。故事构思：" + p.getConcept();
            characterDesigner.design(projectId, antagonistReq, existingChars, charDiversity);
            var charsAfterAntag = repository.findCharactersByProject(projectId);

            // 设计关键女性角色（与主角有复杂关系）
            String femaleCharReq = "重要女性配角。必须有独立动机和成长弧，不能只是工具人或符号。"
                    + "与主角的关系可以是盟友/对手/恋人/血亲。"
                    + "已有人物：" + protagonistSummary + "；"
                    + charsAfterAntag.stream()
                            .filter(c -> !charsAfterAntag.get(0).getName().equals(c.getName()))
                            .map(c -> c.getName() + "（" + c.getRole() + "）")
                            .reduce((a, b) -> a + "、" + b).orElse("无");
            characterDesigner.design(projectId, femaleCharReq, charsAfterAntag, charDiversity);

            // 记录角色生成
            if (creativeDiversityService != null) {
                var chars = repository.findCharactersByProject(projectId);
                String charSummary2 = chars.stream()
                        .map(c -> c.getName() + "(" + c.getRole() + ")")
                        .reduce((a, b) -> a + ", " + b).orElse("无");
                creativeDiversityService.record(projectId, "CHARACTER",
                        "角色设定：" + charSummary2,
                        chars.stream().map(c -> c.getName() + ":" + c.getRole())
                                .reduce((a, b) -> a + ";" + b).orElse("[]"), 0);
            }
            // p11-q1-02: CharacterDesigner 门禁——人物重名/主角存在/能力设定校验
            if (conceiveGate != null) {
                var charGate = conceiveGate.checkCharacters(repository.findCharactersByProject(projectId));
                if (!charGate.pass()) {
                    markQualityRisk(projectId, "CharacterDesigner", charGate.feedback());
                }
            }
        }

        // NCS-167: 大纲生成抽为共享方法——构思期与 PLANNING 断头路恢复（线程死亡在大纲前）
        // 都走这一条幂等路径（无大纲/fallback 大纲才生成）
        ensureOutlineGenerated(p);

        // 伏笔抽取：从大纲文本中提取伏笔条目（p3-03）
        extractForeshadowingsFromOutline(projectId);

        java.util.List<String> missing = new java.util.ArrayList<>();
        if (repository.findKnowledgeEntriesByProject(projectId).isEmpty()) missing.add("世界观");
        if (repository.findCharactersByProject(projectId).isEmpty()) missing.add("人物");
        if (repository.findOutlineByProject(projectId).isEmpty()) missing.add("大纲");
        if (!missing.isEmpty()) {
            p.setState(NovelState.CONCEIVING);
            repository.saveProject(p);
            throw new IllegalStateException("构思未完成，缺少: " + String.join("、", missing));
        }

        if (p.getMode() == ProjectMode.INTERACTIVE) {
            String payload = buildInitialSetupCheckpointPayload(projectId, p);
            p.setState(NovelState.PAUSED_AT_CHECKPOINT);
            Checkpoint checkpoint = new Checkpoint(projectId,
                    Checkpoint.Type.AFTER_INITIAL_SETUP, "初始设定生成完成，待审核");
            checkpoint.setPayload(payload);
            repository.saveCheckpoint(checkpoint);
        } else {
            p.setState(NovelState.PLANNING);
        }
        repository.saveProject(p);
    }


    /** 初始设定审核摘要只读取已落库内容，避免审核卡展示生成过程中的临时候选。 */
    String buildInitialSetupCheckpointPayload(String projectId, Project project) {
        String worldSummary = boundedInitialSetupSummary(collectWorldbuildingSummary(projectId));
        String characterSummary = boundedInitialSetupSummary(collectCharacterSummary(projectId));
        Outline outline = repository.findOutlineByProject(projectId).orElse(null);
        String outlinePremise = outline == null ? "" : normalizeInitialSetupSummary(outline.getPremise());
        String projectConcept = normalizeInitialSetupSummary(project.getConcept());
        String premiseSummary = boundedInitialSetupSummary(projectConcept, outlinePremise);
        if (premiseSummary.isBlank()) {
            premiseSummary = boundedInitialSetupSummary(collectOutlineReviewFallback(outline));
        }
        if (worldSummary.isBlank() || characterSummary.isBlank() || premiseSummary.isBlank()) {
            throw new IllegalStateException("构思未完成，缺少可审核的世界观、人物或原旨/大纲摘要");
        }

        var root = JSON.createObjectNode();
        root.put("worldSummary", worldSummary);
        root.put("characterSummary", characterSummary);
        root.put("premiseSummary", premiseSummary);

        var links = root.putArray("links");
        links.addObject().put("label", "查看世界观与人物").put("target", "world");
        links.addObject().put("label", "查看大纲").put("target", "outline");
        return root.toString();
    }

    private static String collectOutlineReviewFallback(Outline outline) {
        if (outline == null) return "";
        return outline.getVolumes().stream()
                .flatMap(volume -> java.util.stream.Stream.of(volume.getTitle(), volume.getSummary()))
                .map(NovelOrchestrator::normalizeInitialSetupSummary)
                .filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static String boundedInitialSetupSummary(String first, String second) {
        first = normalizeInitialSetupSummary(first);
        second = normalizeInitialSetupSummary(second);
        if (first.isBlank()) return boundedInitialSetupSummary(second);
        if (second.isBlank()) return boundedInitialSetupSummary(first);

        int available = INITIAL_SETUP_SUMMARY_LIMIT - 1;
        int firstLength = first.codePointCount(0, first.length());
        int secondLength = second.codePointCount(0, second.length());
        int firstLimit = Math.min(firstLength, (available + 1) / 2);
        int secondLimit = Math.min(secondLength, available / 2);
        int remaining = available - firstLimit - secondLimit;
        int firstExtra = Math.min(remaining, firstLength - firstLimit);
        firstLimit += firstExtra;
        remaining -= firstExtra;
        secondLimit += Math.min(remaining, secondLength - secondLimit);
        return boundedInitialSetupSummary(first, firstLimit)
                + "\n" + boundedInitialSetupSummary(second, secondLimit);
    }

    private static String boundedInitialSetupSummary(String value) {
        return boundedInitialSetupSummary(value, INITIAL_SETUP_SUMMARY_LIMIT);
    }

    private static String boundedInitialSetupSummary(String value, int limit) {
        String summary = normalizeInitialSetupSummary(value);
        int codePoints = summary.codePointCount(0, summary.length());
        if (codePoints <= limit) return summary;
        int end = summary.offsetByCodePoints(0, limit);
        return summary.substring(0, end);
    }

    private static String normalizeInitialSetupSummary(String value) {
        return value == null ? "" : value.strip();
    }


    /** p18-02: AFTER_CHAPTER checkpoint 的质量 payload（JSON）——审批面板直接展示，不再只有一句话 */
    String buildChapterCheckpointPayload(String projectId, Chapter chapter) {
        try {
            String content = chapter.getContent() != null ? chapter.getContent() : "";
            String preview = content.length() > 200 ? content.substring(0, 200) : content;
            var m = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
            m.put("chapterIndex", chapter.getChapterIndex());
            m.put("title", chapter.getTitle() != null ? chapter.getTitle() : "");
            m.put("wordCount", chapter.getWordCount() > 0 ? chapter.getWordCount() : content.length());
            m.put("preview", preview);
            // NCS-151（旅程三 O-5）：卷末章携带卷小结，作者在检查点即见"第 N 卷完"
            try {
                repository.findOutlineByProject(projectId).ifPresent(outline ->
                        VolumeBoundarySupport.appendSummaryIfVolumeEnd(repository, outline,
                                chapter.getChapterIndex(), m));
            } catch (Exception e) {
                // NCS-159: 卷小结失败不影响检查点主流程，但须留可观测痕迹（DEBUG）
                log.debug("卷小结生成失败（不影响检查点主流程）: projectId={}, ch={}, err={}",
                        projectId, chapter.getChapterIndex(), e.getMessage());
            }
            return m.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** p18-02: 读者评审完成后富集 pending checkpoint 的 payload（score/unresolvedHigh/summary） */
    void enrichChapterCheckpointPayload(String projectId, int chapterIndex) {
        try {
            var pending = repository.findPendingCheckpoints(projectId).stream()
                    .filter(c -> c.getStatus() == Checkpoint.Status.PENDING)
                    .filter(c -> c.getType() == Checkpoint.Type.AFTER_CHAPTER)
                    .filter(c -> c.getDescription() != null && c.getDescription().startsWith("第" + chapterIndex + "章"))
                    .findFirst();
            if (pending.isEmpty()) return;
            Checkpoint cp = pending.get();
            var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                    cp.getPayload() != null ? cp.getPayload() : "{}");
            var node = (com.fasterxml.jackson.databind.node.ObjectNode) root;
            repository.findChapterReview(projectId, chapterIndex).ifPresent(r ->
                    node.put("score", r.getOverallScore()));
            long unresolvedHigh = repository.findUnresolvedIssues(projectId).stream()
                    .filter(i -> i.getDetectedAtChapter() == chapterIndex)
                    .filter(i -> i.getSeverity() == ContinuityIssue.Severity.HIGH).count();
            node.put("unresolvedHigh", unresolvedHigh);
            cp.setPayload(node.toString());
            repository.saveCheckpoint(cp);
        } catch (Exception e) {
            log.debug("checkpoint payload 富集失败(章{}): {}", chapterIndex, e.getMessage());
        }
    }

    private boolean shouldPauseAfterChapter(String projectId, int chapterIndex) {
        int span = repository.findWritingPreferences(projectId)
                .map(com.writing.novel.domain.WritingPreferences::getCheckpointSpan)
                .orElse(1);
        if (span == 0) return false;
        if (span < 0) {
            return repository.findOutlineByProject(projectId)
                    .map(outline -> outline.getVolumes().stream()
                            .anyMatch(volume -> {
                                var synopses = volume.getSynopses();
                                return !synopses.isEmpty()
                                        && synopses.get(synopses.size() - 1).getChapterIndex() == chapterIndex;
                            }))
                    .orElse(false);
        }
        return chapterIndex % span == 0;
    }

    public Outline replanRemaining(String projectId) {
        Project project = loadProject(projectId);
        replanRemainingOutline(projectId, project.getCurrentChapterIndex());
        return repository.findOutlineByProject(projectId)
                .orElseThrow(() -> new IllegalArgumentException("大纲不存在: " + projectId));
    }

    public com.writing.novel.domain.Character expandCharacters(String projectId, String instruction) {
        var existing = repository.findCharactersByProject(projectId);
        String req = (instruction == null || instruction.isBlank())
                ? "补充一名有独立动机的配角，姓名不得与已有人物重复。"
                : instruction;
        com.writing.novel.domain.Character created = characterDesigner.design(projectId, req, existing, "");
        if (created == null) {
            throw new IllegalStateException("未能生成新人物");
        }
        return created;
    }

    public java.util.List<KnowledgeEntry> expandWorld(String projectId, String instruction) {
        Project p = loadProject(projectId);
        String extra = instruction == null ? "" : instruction;
        worldBuilder.build(projectId,
                (p.getConcept() == null ? "" : p.getConcept()) + "\n【增量追加，禁止覆盖已有名称】" + extra,
                p.getTags() != null ? p.getTags() : "",
                "【增量】只新增条目，不要重复已有设定名称。");
        return repository.findKnowledgeEntriesByProject(projectId);
    }

    public com.writing.novel.domain.Character promoteEntity(String projectId, String entityId) {
        Entity e = repository.findEntity(entityId)
                .orElseThrow(() -> new IllegalArgumentException("实体不存在: " + entityId));
        for (com.writing.novel.domain.Character c : repository.findCharactersByProject(projectId)) {
            if (e.getName() != null && e.getName().equals(c.getName())) {
                return c;
            }
        }
        com.writing.novel.domain.Character created = new com.writing.novel.domain.Character(projectId, e.getName(), "配角");
        repository.saveCharacter(created);
        return created;
    }

    public void approveCheckpoint(String projectId, String checkpointId) {
        Checkpoint cp = repository.findCheckpoint(checkpointId)
                .orElseThrow(() -> new IllegalArgumentException("Checkpoint 不存在: " + checkpointId));
        cp.approve();
        repository.saveCheckpoint(cp);
        if (!repository.findPendingCheckpoints(projectId).isEmpty()) {
            Project p = loadProject(projectId);
            p.setState(NovelState.PAUSED_AT_CHECKPOINT);
            repository.saveProject(p);
            return;
        }
        Project p = loadProject(projectId);
        NovelState target = switch (cp.getType()) {
            case AFTER_INITIAL_SETUP, AFTER_VOLUME_OUTLINE -> NovelState.PLANNING;
            case AFTER_CHAPTER, ON_CONTINUITY_FIX -> NovelState.WRITING;
        };
        p.setState(target);
        repository.saveProject(p);
    }

    /** Result returned to checkpoint review clients after the target content has been replaced.
     *  NCS-154: characterRevisions 携带点名卡的待确认修订预览（无则 null）。 */
    public record CheckpointRevisionResult(String targetType, String beforeSummary, String afterSummary,
                                           int beforeVersion, int afterVersion,
                                           List<com.writing.novel.service.CharacterRevisionPreviewService.CardRevision> characterRevisions) {}

    /**
     * Route a pending checkpoint to its real revision target. This method deliberately does not
     * change checkpoint status; the controller handles it only after content persistence succeeds.
     */
    public CheckpointRevisionResult reviseCheckpoint(String projectId, Checkpoint checkpoint,
                                                     String instructions) {
        if (checkpoint == null || !projectId.equals(checkpoint.getProjectId())) {
            throw new IllegalStateException("Checkpoint 不属于当前项目");
        }
        if (checkpoint.getStatus() != Checkpoint.Status.PENDING) {
            throw new IllegalStateException("Checkpoint 已处理");
        }
        if (instructions == null || instructions.isBlank()) {
            throw new IllegalArgumentException("修改意见不能为空");
        }
        return switch (checkpoint.getType()) {
            case AFTER_INITIAL_SETUP -> reviseInitialSetup(projectId, instructions);
            case AFTER_VOLUME_OUTLINE -> reviseVolumeOutline(projectId, checkpoint, instructions);
            case AFTER_CHAPTER -> reviseCheckpointChapter(projectId, instructions);
            case ON_CONTINUITY_FIX -> throw new IllegalArgumentException("当前 checkpoint 不支持内容改写");
        };
    }

    private CheckpointRevisionResult reviseInitialSetup(String projectId, String instructions) {
        if (worldBuilder == null) throw new IllegalStateException("WorldBuilder 未配置");
        Project project = loadProject(projectId);
        List<KnowledgeEntry> beforeEntries = repository.findKnowledgeEntriesByProject(projectId);
        String before = canonicalWorld(beforeEntries);

        Worldbuilding candidate = worldBuilder.propose(projectId,
                project.getConcept() != null ? project.getConcept() : "",
                project.getTags() != null ? project.getTags() : "", "", instructions);
        validateWorldCandidate(projectId, candidate);
        String after = canonicalWorld(candidate.getEntries());

        // NCS-090（第二轮旅程 A-1）：人物层同步——补章纲新配角卡、点名零出场卡。
        // 此前本方法只重写世界观，作者的人物反馈被静默丢弃。
        // 意图门控：仅当修改意见涉及人物/配角/角色时触发人物层调用（避免搭车改动）
        boolean characterIntent = instructions != null
                && (instructions.contains("人物") || instructions.contains("配角")
                    || instructions.contains("角色"));
        // NCS-144：意见点名既有卡 → 定向修订预览（NCS-154：不再直接落库，待作者确认）；与补卡同步并行
        com.writing.novel.service.CharacterRevisionPreviewService.PendingRevisions namedRevisions =
                characterIntent ? reviseNamedExistingCharacters(projectId, instructions) : null;
        String namedSync = namedRevisions == null ? "" : namedRevisionSummary(namedRevisions);
        String characterSync = characterIntent
                ? syncCharactersFromOutline(projectId, instructions) : "";
        if (!namedSync.isBlank()) {
            characterSync = characterSync.isBlank() ? namedSync : namedSync + " " + characterSync;
        }

        boolean worldChanged = !before.equals(after);
        if (!worldChanged && characterSync.isBlank()) {
            throw new IllegalStateException("世界观候选未产生有效变化");
        }
        if (worldChanged) {
            worldBuilder.replaceEntries(projectId, List.copyOf(candidate.getEntries()));
            replaceWorldCanon(projectId, beforeEntries, candidate.getEntries(),
                    project.getCurrentChapterIndex());
        }
        if (contextAssembler != null) contextAssembler.invalidateProject(projectId);
        String afterFull = (worldChanged ? after : before)
                + (characterSync.isBlank() ? "" : "\n【人物层同步】" + characterSync);
        return revisionResult("INITIAL_SETUP", before, afterFull,
                namedRevisions == null ? null : namedRevisions.cards());
    }

    /**
     * NCS-090：按大纲同步人物层（确定性规则 + CharacterDesigner）。
     * 候选名 = 卷梗概「新配角：」约定（顿号分隔、名取括号前）中尚无卡者；
     * 零出场既有卡不静默删除，仅在返回摘要中点名，处置留给作者。
     * 单个补卡失败不回滚整体，逐条记录并继续。
     */
    /**
     * NCS-144（旅程三 F-2，NCS-090 续）：修改意见点名既有卡 → 定向修订。
     * 旅程实测：意见要求"合并殷素更/裴无秤、骨更去记账腔"，执行后原卡逐字未变。
     * 语义：意见文本包含既有卡姓名（2-8 字）即视为点名该卡；design 出候选卡后
     * 阶段化为待确认预览（NCS-154：作者确认前不落库），未点名卡零变动。
     * 返回 null = 未点名或候选全部无效。
     */
    private com.writing.novel.service.CharacterRevisionPreviewService.PendingRevisions
            reviseNamedExistingCharacters(String projectId, String instructions) {
        if (characterDesigner == null) return null;
        List<com.writing.novel.domain.Character> existing =
                repository.findCharactersByProject(projectId);
        List<com.writing.novel.domain.Character> named = existing.stream()
                .filter(c -> c.getName() != null && c.getName().length() >= 2
                        && c.getName().length() <= 8 && instructions.contains(c.getName()))
                .toList();
        if (named.isEmpty()) return null;
        List<com.writing.novel.domain.Character> originals = new java.util.ArrayList<>();
        List<com.writing.novel.domain.Character> candidates = new java.util.ArrayList<>();
        for (com.writing.novel.domain.Character original : named) {
            try {
                String requirement = "修订人物卡：" + original.getName()
                        + "。原卡设定：" + com.writing.novel.service.CharacterRevisionPreviewService
                                .describeCard(original)
                        + "。作者要求：" + instructions
                        + "。保持姓名「" + original.getName() + "」与主线定位不变，仅按作者要求修订对应字段。";
                com.writing.novel.domain.Character candidate =
                        characterDesigner.design(projectId, requirement, existing);
                if (candidate == null) continue;
                originals.add(original);
                candidates.add(candidate);
            } catch (Exception e) {
                log.warn("人物卡修订失败({}): {}", original.getName(), e.getMessage());
            }
        }
        if (originals.isEmpty()) return null;
        try {
            return characterRevisionPreviews.stage(projectId, originals, candidates);
        } catch (IllegalStateException e) {
            // 候选与原卡逐字段相同：无有效变化，视同未点名
            return null;
        }
    }

    /** NCS-154：点名卡预览的摘要文案（进入人物层同步拼接与 afterSummary） */
    private static String namedRevisionSummary(
            com.writing.novel.service.CharacterRevisionPreviewService.PendingRevisions revisions) {
        String names = revisions.cards().stream()
                .map(com.writing.novel.service.CharacterRevisionPreviewService.CardRevision::name)
                .collect(java.util.stream.Collectors.joining("、"));
        int fieldCount = revisions.cards().stream()
                .mapToInt(c -> c.changes().size()).sum();
        return "已为人物卡「" + names + "」生成修订预览（" + fieldCount + " 处字段变化），待作者确认后生效";
    }

    private String syncCharactersFromOutline(String projectId, String instructions) {
        if (characterDesigner == null) return "";
        Outline outline = repository.findOutlineByProject(projectId).orElse(null);
        if (outline == null) return "";
        StringBuilder outlineText = new StringBuilder();
        for (Volume v : outline.getVolumes()) {
            if (v.getSummary() != null) outlineText.append(v.getSummary()).append('\n');
            for (ChapterSynopsis s : v.getSynopses()) {
                if (s.getSynopsis() != null) outlineText.append(s.getSynopsis()).append('\n');
            }
        }
        String text = outlineText.toString();
        List<com.writing.novel.domain.Character> existing = repository.findCharactersByProject(projectId);
        java.util.Set<String> knownNames = existing.stream()
                .map(com.writing.novel.domain.Character::getName).collect(java.util.stream.Collectors.toSet());

        List<String> candidates = new java.util.ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("新配角：([^。\\n]+)").matcher(text);
        while (m.find()) {
            for (String part : m.group(1).split("[、，,]")) {
                String name = part.trim().split("[（(]")[0].trim();
                if (name.length() >= 2 && name.length() <= 8
                        && !knownNames.contains(name) && !candidates.contains(name)) {
                    candidates.add(name);
                }
            }
        }

        List<String> createdNames = new java.util.ArrayList<>();
        for (String name : candidates) {
            try {
                int at = text.indexOf(name);
                String context = text.substring(Math.max(0, at - 20),
                        Math.min(text.length(), at + 60));
                String requirement = "设计人物卡：" + name
                        + "。该人物在大纲中的出场与设定：" + context
                        + "。作者要求：" + (instructions == null ? "" : instructions)
                        + "。姓名必须为「" + name + "」。";
                com.writing.novel.domain.Character card =
                        characterDesigner.design(projectId, requirement, existing);
                if (card == null) continue;
                card.setName(name);
                repository.saveCharacter(card);
                createdNames.add(name);
                existing = repository.findCharactersByProject(projectId);
            } catch (Exception e) {
                log.warn("人物层同步-补卡失败({}): {}", name, e.getMessage());
            }
        }

        List<String> zeroAppearance = existing.stream()
                .filter(c -> c.getName() != null && c.getName().length() >= 2)
                .filter(c -> c.getRole() == null || !c.getRole().contains("主角"))
                .map(com.writing.novel.domain.Character::getName)
                .filter(name -> !text.contains(name))
                .toList();

        StringBuilder summary = new StringBuilder();
        if (!createdNames.isEmpty()) {
            summary.append("已为章纲新配角补卡 ").append(createdNames.size()).append(" 人（")
                    .append(String.join("、", createdNames)).append("）。");
        }
        if (!zeroAppearance.isEmpty()) {
            summary.append("以下人物卡在章纲中零出场，请作者处置：")
                    .append(String.join("、", zeroAppearance)).append("。");
        }
        return summary.toString();
    }

    private void replaceWorldCanon(String projectId, List<KnowledgeEntry> oldEntries,
                                   List<KnowledgeEntry> candidates, int currentChapterIndex) {
        if (storyCanon == null) return;
        java.util.Set<String> oldKeys = oldEntries.stream()
                .filter(this::isReplaceableWorldCanonEntry)
                .map(this::storyCanonKey)
                .collect(java.util.stream.Collectors.toSet());
        java.util.Set<String> candidateKeys = candidates.stream()
                .filter(this::isReplaceableWorldCanonEntry)
                .map(this::storyCanonKey)
                .collect(java.util.stream.Collectors.toSet());

        candidates.stream()
                .filter(this::isReplaceableWorldCanonEntry)
                .filter(candidate -> oldKeys.contains(storyCanonKey(candidate)))
                .forEach(candidate -> applyWorldCanonPatch(projectId, candidate, currentChapterIndex));
        for (KnowledgeEntry oldEntry : oldEntries) {
            if (!isReplaceableWorldCanonEntry(oldEntry)
                    || candidateKeys.contains(storyCanonKey(oldEntry))) continue;
            StoryFact.FactType type = storyFactType(oldEntry);
            storyCanon.recallBySubject(projectId, oldEntry.getName()).stream()
                    .filter(fact -> fact.getFactType() == type)
                    .toList()
                    .forEach(fact -> storyCanon.retire(projectId, fact.getId()));
        }
        candidates.stream()
                .filter(this::isReplaceableWorldCanonEntry)
                .filter(candidate -> !oldKeys.contains(storyCanonKey(candidate)))
                .forEach(candidate -> applyWorldCanonPatch(projectId, candidate, currentChapterIndex));
    }

    private boolean isReplaceableWorldCanonEntry(KnowledgeEntry entry) {
        return entry != null && !"时代锚点".equals(entry.getName()) && storyFactType(entry) != null;
    }

    private String storyCanonKey(KnowledgeEntry entry) {
        return storyFactType(entry) + "\u0000" + entry.getName();
    }

    private void applyWorldCanonPatch(String projectId, KnowledgeEntry candidate, int currentChapterIndex) {
        StoryFact.FactType type = storyFactType(candidate);
        if (type == null) return;
        storyCanon.applyHumanPatch(projectId, type, candidate.getName(),
                candidate.getName() + "：" + candidate.getContent(), currentChapterIndex,
                type == StoryFact.FactType.WORLD_RULE ? 4 : 3);
    }

    private CheckpointRevisionResult reviseVolumeOutline(String projectId, Checkpoint checkpoint,
                                                         String instructions) {
        if (plotter == null) throw new IllegalStateException("Plotter 未配置");
        Project project = loadProject(projectId);
        Outline outline = repository.findOutlineByProject(projectId)
                .orElseThrow(() -> new IllegalStateException("无大纲"));
        Volume target = findCheckpointVolume(outline, checkpoint, project.getCurrentChapterIndex());
        String before = canonicalVolume(target);

        Volume candidate = plotter.proposeVolumeRevision(projectId, outline.getPremise(), target,
                project.getCurrentChapterIndex(), instructions);
        // NCS-078（听雪刀 P0）：空候选 = 合法的「没有更多内容」信号——移除目标卷全部未写章，
        // 大纲因此耗尽时直接走成书门禁。此前这里抛「Plotter 返回空卷大纲候选」409，
        // 封死了作者拒绝后续卷并完结的唯一出口。
        if (candidate == null || candidate.getSynopses().isEmpty()) {
            boolean removedAny = target.getSynopses().removeIf(
                    s -> s.getChapterIndex() > project.getCurrentChapterIndex());
            if (target.getSynopses().isEmpty()) {
                outline.getVolumes().remove(target);
            }
            repository.saveOutline(outline);
            if (contextAssembler != null) contextAssembler.invalidateProject(projectId);
            boolean outlineExhausted = outline.getVolumes().stream()
                    .flatMap(v -> v.getSynopses().stream())
                    .noneMatch(s -> s.getChapterIndex() > project.getCurrentChapterIndex());
            if (removedAny && outlineExhausted && repository.findPendingCheckpoints(projectId).stream()
                    .allMatch(pending -> pending.getId().equals(checkpoint.getId()))) {
                finishBookWithGate(projectId, project, 0);
            }
            String after = outline.getVolumes().contains(target) ? canonicalVolume(target) : "（卷已移除）";
            return new CheckpointRevisionResult("VOLUME_OUTLINE", displaySummary(before),
                    displaySummary(after), contentVersion(before), contentVersion(before) + 1, null);
        }
        validateVolumeCandidate(target, candidate, project.getCurrentChapterIndex());

        target.getSynopses().removeIf(s -> s.getChapterIndex() > project.getCurrentChapterIndex());
        target.getSynopses().addAll(candidate.getSynopses());
        target.getSynopses().sort(java.util.Comparator.comparingInt(ChapterSynopsis::getChapterIndex));
        String after = canonicalVolume(target);
        requireChanged(before, after, "卷大纲候选未产生有效变化");

        repository.saveOutline(outline);
        if (contextAssembler != null) contextAssembler.invalidateProject(projectId);
        return revisionResult("VOLUME_OUTLINE", before, after);
    }

    private CheckpointRevisionResult reviseCheckpointChapter(String projectId, String instructions) {
        Project project = loadProject(projectId);
        Chapter beforeChapter = repository.findChapterByIndex(projectId, project.getCurrentChapterIndex())
                .orElseThrow(() -> new IllegalArgumentException("章节不存在: " + project.getCurrentChapterIndex()));
        String before = canonicalChapter(beforeChapter);
        int beforeVersion = beforeChapter.getVersion();

        rewriteChapter(projectId, project.getCurrentChapterIndex(), instructions);
        Chapter afterChapter = repository.findChapterByIndex(projectId, project.getCurrentChapterIndex())
                .orElseThrow(() -> new IllegalStateException("章节重写后不存在"));
        String after = canonicalChapter(afterChapter);
        if (before.equals(after) || afterChapter.getVersion() <= beforeVersion) {
            throw new IllegalStateException("章节改写未产生有效变化");
        }
        return new CheckpointRevisionResult("CHAPTER", displaySummary(before), displaySummary(after),
                beforeVersion, afterChapter.getVersion(), null);
    }

    private Volume findCheckpointVolume(Outline outline, Checkpoint checkpoint, int writtenBoundary) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("卷\\s*(\\d+)")
                .matcher(checkpoint.getDescription() != null ? checkpoint.getDescription() : "");
        if (matcher.find()) {
            int volumeIndex = Integer.parseInt(matcher.group(1));
            return outline.getVolumes().stream()
                    .filter(volume -> volume.getVolumeIndex() == volumeIndex)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("大纲中无审核目标卷: " + volumeIndex));
        }
        return outline.getVolumes().stream()
                .filter(volume -> volume.getSynopses().stream()
                        .anyMatch(synopsis -> synopsis.getChapterIndex() > writtenBoundary))
                .max(java.util.Comparator.comparingInt(Volume::getVolumeIndex))
                .orElseThrow(() -> new IllegalStateException("无可修订的未写卷大纲"));
    }

    private void validateWorldCandidate(String projectId, Worldbuilding candidate) {
        if (candidate == null || candidate.getEntries().isEmpty()) {
            throw new IllegalStateException("WorldBuilder 返回空候选");
        }
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (KnowledgeEntry entry : candidate.getEntries()) {
            if (entry == null || !projectId.equals(entry.getProjectId())
                    || isBlank(entry.getType()) || isBlank(entry.getName()) || isBlank(entry.getContent())) {
                throw new IllegalStateException("WorldBuilder 返回非法候选");
            }
            if (!keys.add(entry.getType() + "\u0000" + entry.getName())) {
                throw new IllegalStateException("WorldBuilder 候选含重复条目: " + entry.getName());
            }
        }
    }

    private void validateVolumeCandidate(Volume source, Volume candidate, int writtenBoundary) {
        if (candidate == null || candidate.getSynopses().isEmpty()) {
            throw new IllegalStateException("Plotter 返回空卷大纲候选");
        }
        java.util.Set<Integer> expected = source.getSynopses().stream()
                .map(ChapterSynopsis::getChapterIndex)
                .filter(index -> index > writtenBoundary)
                .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
        java.util.Set<Integer> actual = new java.util.TreeSet<>();
        for (ChapterSynopsis synopsis : candidate.getSynopses()) {
            if (synopsis == null || synopsis.getChapterIndex() <= writtenBoundary
                    || isBlank(synopsis.getTitle()) || isBlank(synopsis.getSynopsis())
                    || !actual.add(synopsis.getChapterIndex())) {
                throw new IllegalStateException("Plotter 返回非法卷大纲候选");
            }
        }
        if (expected.isEmpty() || !expected.equals(actual)) {
            throw new IllegalStateException("Plotter 候选章号必须完整匹配未写范围: " + expected);
        }
    }

    private CheckpointRevisionResult revisionResult(String targetType, String before, String after) {
        return revisionResult(targetType, before, after, null);
    }

    /** NCS-154: 带 待确认人物卡预览 的修订结果 */
    private CheckpointRevisionResult revisionResult(String targetType, String before, String after,
            List<com.writing.novel.service.CharacterRevisionPreviewService.CardRevision> characterRevisions) {
        return new CheckpointRevisionResult(targetType, displaySummary(before), displaySummary(after),
                contentVersion(before), contentVersion(after), characterRevisions);
    }

    private String canonicalWorld(List<KnowledgeEntry> entries) {
        return entries.stream()
                .sorted(java.util.Comparator.comparing(KnowledgeEntry::getType)
                        .thenComparing(KnowledgeEntry::getName)
                        .thenComparing(KnowledgeEntry::getContent))
                .map(entry -> entry.getType() + "|" + entry.getName() + "|" + entry.getContent())
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private String canonicalVolume(Volume volume) {
        String chapters = volume.getSynopses().stream()
                .sorted(java.util.Comparator.comparingInt(ChapterSynopsis::getChapterIndex))
                .map(s -> s.getChapterIndex() + "|" + s.getTitle() + "|" + s.getSynopsis()
                        + "|" + safe(s.getWritingNotes()) + "|" + safe(s.getBeats())
                        + "|" + safe(s.getClimax()) + "|" + safe(s.getCast())
                        + "|" + safe(s.getPlotPurpose()) + "|" + s.getTensionLevel())
                .collect(java.util.stream.Collectors.joining("\n"));
        return volume.getVolumeIndex() + "|" + safe(volume.getTitle()) + "|"
                + safe(volume.getSummary()) + "\n" + chapters;
    }

    private String canonicalChapter(Chapter chapter) {
        return chapter.getChapterIndex() + "|" + safe(chapter.getTitle()) + "|" + safe(chapter.getContent());
    }

    private void requireChanged(String before, String after, String message) {
        if (before.equals(after)) throw new IllegalStateException(message);
    }

    private String displaySummary(String canonical) {
        int limit = 600;
        String text = canonical.length() <= limit ? canonical : canonical.substring(0, limit) + "…";
        return text + " [v" + Integer.toUnsignedString(contentVersion(canonical)) + "]";
    }

    private int contentVersion(String canonical) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return (int) crc.getValue();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    /** 从当前状态推进一步 */
    public void step(String projectId) {
        Project p = loadProject(projectId);
        switch (p.getState()) {
            case CONCEIVING -> runConceiving(projectId);
            case PAUSED_AT_CHECKPOINT -> resumeFromCheckpoint(projectId);
            case PLANNING -> runPlanning(projectId);
            case WRITING -> runWritingOneChapter(projectId);
            case VOLUME_DONE -> startNextVolumeOrFinish(projectId);
            default -> throw new IllegalStateException("无法从状态推进: " + p.getState());
        }
    }

    /**
     * Advance the project for a successfully revised checkpoint without handling that checkpoint.
     * This lets the controller persist MODIFIED only after project advancement succeeds.
     */
    public void resumeAfterCheckpointRevision(String projectId, Checkpoint checkpoint) {
        if (checkpoint == null || !projectId.equals(checkpoint.getProjectId())
                || checkpoint.getStatus() != Checkpoint.Status.PENDING) {
            throw new IllegalStateException("Checkpoint 不是当前项目的待处理项");
        }
        Project project = loadProject(projectId);
        boolean hasOtherPending = repository.findPendingCheckpoints(projectId).stream()
                .anyMatch(pending -> !pending.getId().equals(checkpoint.getId()));
        NovelState target = hasOtherPending ? NovelState.PAUSED_AT_CHECKPOINT : switch (checkpoint.getType()) {
            case AFTER_INITIAL_SETUP, AFTER_VOLUME_OUTLINE -> NovelState.PLANNING;
            case AFTER_CHAPTER, ON_CONTINUITY_FIX -> NovelState.WRITING;
        };
        project.setState(target);
        repository.saveProject(project);
    }

    public void pause(String projectId) {
        Project p = loadProject(projectId);
        if (p.getState() == NovelState.FINAL || p.getState() == NovelState.NEEDS_REVISION) {
            throw new IllegalStateException("已完结项目不能暂停: " + p.getState());
        }
        p.setState(NovelState.PAUSED_AT_CHECKPOINT);
        repository.saveProject(p);
        repository.saveCheckpoint(new Checkpoint(projectId,
                Checkpoint.Type.ON_CONTINUITY_FIX, "用户暂停，可修改设定后继续"));
    }

    private void resumeFromCheckpoint(String projectId) {
        Project p = loadProject(projectId);
        // p7-1: 智能恢复——根据最近的 PENDING checkpoint 类型决定下一步状态
        var pending = repository.findLatestPendingCheckpoint(projectId);
        if (pending.isPresent()) {
            var cp = pending.get();
            cp.approve();
            repository.saveCheckpoint(cp);

            NovelState targetState = switch (cp.getType()) {
                case AFTER_INITIAL_SETUP, AFTER_VOLUME_OUTLINE -> NovelState.PLANNING;
                case AFTER_CHAPTER, ON_CONTINUITY_FIX -> NovelState.WRITING;
            };
            p.setState(targetState);
        } else {
            // 无 pending checkpoint，默认进入 PLANNING
            p.setState(NovelState.PLANNING);
        }
        repository.saveProject(p);
    }

    /**
     * NCS-168: 修订后重开检查点——reject 把旧检查点置 REJECTED（终态），若不生成
     * 新一轮 PENDING，作者每轮只有一次修订机会（走查实测第二轮 reject 报「已处理」）。
     * 在 PAUSED_AT_CHECKPOINT 下按原类型生成携带修订后 payload 的新 PENDING 检查点。
     */
    public Checkpoint reopenCheckpointAfterRevision(String projectId, Checkpoint previous) {
        Project p = loadProject(projectId);
        if (p.getState() != NovelState.PAUSED_AT_CHECKPOINT) return null;
        Checkpoint next = new Checkpoint(projectId, previous.getType(),
                "修订后待审核（上一轮已拒绝）");
        next.setPayload(buildCheckpointPayload(projectId, p, previous.getType()));
        repository.saveCheckpoint(next);
        return next;
    }

    /** NCS-168: 按类型构造修订后 payload（供重开检查点携带最新内容快照） */
    private String buildCheckpointPayload(String projectId, Project project, Checkpoint.Type type) {
        try {
            return switch (type) {
                case AFTER_INITIAL_SETUP -> buildInitialSetupCheckpointPayload(projectId, project);
                case AFTER_CHAPTER -> repository
                        .findChapterByIndex(projectId, project.getCurrentChapterIndex())
                        .map(ch -> buildChapterCheckpointPayload(projectId, ch))
                        .orElse(null);
                case AFTER_VOLUME_OUTLINE -> null; // 卷纲修订 payload 暂无既有构造器，留空
                case ON_CONTINUITY_FIX -> null;
            };
        } catch (Exception e) {
            log.warn("重开检查点 payload 构造失败（不阻断 reopen）: projectId={}, type={}",
                    projectId, type, e);
            return null;
        }
    }

    /**
     * NCS-167: 大纲生成（幂等）——无大纲或 fallback 大纲时补跑 Plotter（含门禁重试与多样性记录）。
     * 原 runConceiving 内联段；PLANNING 断头路（构思线程在大纲前死亡）经 runPlanning 复用恢复。
     */
    private void ensureOutlineGenerated(Project p) {
        String projectId = p.getId();
        if (repository.findOutlineByProject(projectId).isEmpty()
                || isFallbackOutline(projectId)) {
            String protagonistName = "";
            var chars = repository.findCharactersByProject(projectId);
            for (var c : chars) {
                if (c.getRole() != null && c.getRole().contains("主角")) {
                    protagonistName = c.getName();
                    break;
                }
            }
            String worldSummary = collectWorldbuildingSummary(projectId);
            String charSummary = collectCharacterSummary(projectId);
            String theme = (protagonistName.isEmpty() ? "" : "主角名为「" + protagonistName + "」。")
                    + "主题：" + p.getConcept()
                    + (worldSummary.isEmpty() ? "" : "\n世界观设定：\n" + worldSummary)
                    + (charSummary.isEmpty() ? "" : "\n已有人物：\n" + charSummary);
            String genreRules = genreRuleProvider != null
                    ? genreRuleProvider.getPlottingRules(p.getGenre()) : "";
            String climaxSuggestions = genreRuleProvider != null
                    ? genreRuleProvider.getClimaxSuggestions(p.getGenre()) : "";
            if (!climaxSuggestions.isBlank()) {
                genreRules = genreRules + "\n本题材推荐爽点类型：" + climaxSuggestions;
            }
            String plotDiversity = creativeDiversityService != null
                    ? creativeDiversityService.getGlobalDiversityGuide(projectId) : "";

            // p12-K1: 从 prefs 读取目标章数（默认 24，百万字可设 240）
            int targetChapters = repository.findWritingPreferences(projectId)
                    .map(prefs -> prefs.getTargetChapters()).orElse(24);
            boolean useLayered = repository.findWritingPreferences(projectId)
                    .map(prefs -> prefs.isLayeredOutline()).orElse(false);

            if (useLayered && targetChapters > 40) {
                // p12-K1: 分层大纲模式——先生成总纲，第一卷详细大纲
                String wbSummaryForOutline = collectWorldbuildingSummary(projectId);
                // cn-01: 把主角名 + 人物摘要传给 master outline，避免大纲自创名字漂移
                String masterOutline = plotter.planMasterOutline(
                        projectId, p.getConcept(), targetChapters, wbSummaryForOutline, genreRules,
                        protagonistName, charSummary);
                // 存储总纲到 outline.premise（临时存储，后续按卷扩展）
                Outline masterOutlineObj = new Outline(projectId, masterOutline);
                repository.saveOutline(masterOutlineObj);
                log.info("分层大纲总纲生成完成: {} 卷规划", Math.max(2, targetChapters / 20));
            } else {
                // 传统模式——一次性生成全部大纲（≤40章适用）
                plotter.plan(projectId, p.getConcept(), theme, targetChapters, theme, genreRules, plotDiversity);
            }

            // p11-q1-02: Outline 门禁——总纲/分卷校验，不过关重 plan 一次（传统模式）
            if (conceiveGate != null) {
                var outlineOpt = repository.findOutlineByProject(projectId);
                if (outlineOpt.isPresent()) {
                    var outlineGate = conceiveGate.checkOutline(outlineOpt.get());
                    if (!outlineGate.pass()) {
                        markQualityRisk(projectId, "Outline", outlineGate.feedback());
                        if (!useLayered || targetChapters <= 40) {
                            plotter.plan(projectId, p.getConcept(), theme, targetChapters, theme,
                                    genreRules + "【门禁反馈】" + outlineGate.feedback(), plotDiversity);
                        }
                    }
                }
            }

            // 记录大纲生成
            if (creativeDiversityService != null) {
                var outline = repository.findOutlineByProject(projectId);
                if (outline.isPresent()) {
                    String plotSummary = outline.get().getVolumes().stream()
                            .map(v -> "卷" + v.getVolumeIndex() + ":" + v.getTitle())
                            .reduce((a, b) -> a + ", " + b).orElse("无");
                    creativeDiversityService.record(projectId, "PLOTTER",
                            "剧情结构：" + plotSummary,
                            outline.get().getVolumes().stream()
                                    .map(v -> "卷" + v.getVolumeIndex() + ":" + v.getTitle())
                                    .reduce((a, b) -> a + ";" + b).orElse("[]"), 0);
                }
            }

            // p11-H1: 角色群像扩展——根据篇幅动态设计更多配角
            expandCharacterEnsemble(projectId, p);
        }
    }

    private void runPlanning(String projectId) {
        Project p = loadProject(projectId);
        // NCS-167: PLANNING 可能是断头路（构思线程在大纲生成前死亡，项目停 PLANNING 无大纲）——
        // 幂等补跑大纲生成；恢复后照常推进 WRITING，run/resume 不再被终态门禁卡死
        ensureOutlineGenerated(p);
        p.setState(NovelState.WRITING);
        repository.saveProject(p);
    }

    /** 写一章 */
    public Chapter runWritingOneChapter(String projectId) {
        return runWritingOneChapter(projectId, null);
    }

    /** p18-03: 带作者指令的单章写作——指令前置于本章 writingNotes（【作者指令】段） */
    public Chapter runWritingOneChapter(String projectId, String instruction) {
        EventContext.setProjectId(projectId);
        try {
            return doWriteOneChapter(projectId, instruction);
        } finally {
            EventContext.clear();
        }
    }

    /** p18-03: 作者指令注入 writingNotes（空指令原样返回）；静态便于单测 */
    public static String prependAuthorInstruction(String notes, String instruction) {
        if (instruction == null || instruction.isBlank()) return notes;
        String base = notes != null ? notes : "";
        return "【作者指令】" + instruction.trim() + (base.isEmpty() ? "" : "\n" + base);
    }

    private Chapter doWriteOneChapter(String projectId) {
        return doWriteOneChapter(projectId, null);
    }

    private Chapter doWriteOneChapter(String projectId, String instruction) {
        Project p = loadProject(projectId);
        // p21-02: 终态命令门禁——FINAL/NEEDS_REVISION 拒绝写新章（显式重审走 retryFinalAudit）
        if (p.getState() == NovelState.FINAL) {
            throw new IllegalStateException("全书已完结（FINAL），不能继续写作");
        }
        if (p.getState() == NovelState.NEEDS_REVISION) {
            throw new IllegalStateException("成书门禁未通过（NEEDS_REVISION），请修订设定/正文后重试成书门禁");
        }
        Outline outline = repository.findOutlineByProject(projectId).orElseThrow(
                () -> new IllegalStateException("无大纲，无法写作"));

        int chapterIndex = p.getCurrentChapterIndex() + 1;

        // p9-fix: 大纲索引可能有间隙（如跳过第1章从第2章开始），跳到下一个有效章节
        ChapterSynopsis synopsis = findSynopsis(outline, chapterIndex);
        if (synopsis == null) {
            // 当前索引无对应大纲，找下一个有大纲的章节
            for (int i = chapterIndex + 1; i <= chapterIndex + 10; i++) {
                ChapterSynopsis next = findSynopsis(outline, i);
                if (next != null) {
                    chapterIndex = i;
                    synopsis = next;
                    break;
                }
            }
        }

        // 用于 lambda 的 final 副本
        final int finalChapterIndex = chapterIndex;

        // p20-01: 前一章待人工复核时禁止继续写下一章——复核通过（NEEDS_MANUAL_REVIEW→DONE）前写作冻结
        var previous = repository.findChapterByIndex(projectId, p.getCurrentChapterIndex());
        if (previous.isPresent()
                && previous.get().getStatus() == Chapter.Status.NEEDS_MANUAL_REVIEW) {
            throw new IllegalStateException("第" + p.getCurrentChapterIndex()
                    + "章待人工复核，处理后再继续写作");
        }

        // 幂等检查：该章已存在且内容有效则跳过（断点续写时避免重复）
        var existing = repository.findChapterByIndex(projectId, chapterIndex);
        int idempotentMinLen = repository.findWritingPreferences(projectId).map(pref -> pref.getMinChapterLength()).orElse(2000);
        if (existing.isPresent() && existing.get().getStatus() == Chapter.Status.DRAFTING) {
            throw new IllegalStateException("第" + chapterIndex + "章有未完成的作者草稿，请完成编辑后继续");
        }
        if (existing.isPresent() && (existing.get().getWordCount() >= idempotentMinLen
                || (existing.get().getStatus() == Chapter.Status.DONE && existing.get().getWordCount() >= 500))) {
            // p10-w1-06: 后处理未完成的章节（POST_PROCESSING）标记可观测，便于识别需重跑的章节
            if (existing.get().getStatus() == com.writing.novel.domain.Chapter.Status.POST_PROCESSING) {
                log.warn("第{}章后处理未完成（status=POST_PROCESSING），可能需重跑后处理", chapterIndex);
            }
            // 已有有效章节，推进索引并返回
            p.setCurrentChapterIndex(chapterIndex);
            repository.saveProject(p);
            return existing.get();
        }

        if (synopsis == null) {
            // p12-fix: 大纲耗尽——尝试分层大纲延迟扩展
            boolean extended = tryExtendLayeredOutline(projectId, p);
            if (extended) {
                // 重新查找大纲和 synopsis
                outline = repository.findOutlineByProject(projectId).orElse(null);
                if (outline != null) {
                    synopsis = findSynopsis(outline, chapterIndex);
                    if (synopsis == null) {
                        // gap-skip: 找下一个有 synopsis 的章节
                        for (int i = chapterIndex + 1; i <= chapterIndex + 20; i++) {
                            ChapterSynopsis next = findSynopsis(outline, i);
                            if (next != null) {
                                chapterIndex = i;
                                synopsis = next;
                                break;
                            }
                        }
                    }
                }
            }
            if (synopsis == null) {
                p.setState(NovelState.VOLUME_DONE);
                repository.saveProject(p);
                return null;
            }
        }

        // p22-04: 写前上下文装配与正文生成经章节生产模块（prepare/write 阶段）
        String enhancedNotes = production.prepare(projectId, chapterIndex, synopsis, instruction);
        Chapter chapter = production.write(projectId, chapterIndex, synopsis, enhancedNotes);

        // 内容校验：不达标不推进索引（chapterWriter.write 已保证 ≥500 字或抛异常）
        p = loadProject(projectId); // 重新加载最新状态
        p.setCurrentChapterIndex(chapterIndex);
        repository.saveProject(p);

        // p10-w1-06: 正文已落库，进入后处理阶段——status=POST_PROCESSING 持久化
        // 后处理若中途失败，重启后可识别 POST_PROCESSING 章节（而非误判为完成）
        // p32-04: 记录本章生成时点采用的计划出处（卷号+梗概指纹），作者可核对偏离来源
        chapter.setPlanRef(planRefFor(outline, chapterIndex));
        chapter.setStatus(com.writing.novel.domain.Chapter.Status.POST_PROCESSING);
        repository.saveChapter(chapter);

        if (p.getMode() == ProjectMode.INTERACTIVE && shouldPauseAfterChapter(projectId, chapterIndex)) {
            p.setState(NovelState.PAUSED_AT_CHECKPOINT);
            repository.saveProject(p);
            Checkpoint chapterCp = new Checkpoint(projectId,
                    Checkpoint.Type.AFTER_CHAPTER,
                    "第" + chapterIndex + "章「" + chapter.getTitle() + "」已完成，待审核");
            chapterCp.setPayload(buildChapterCheckpointPayload(projectId, chapter));
            repository.saveCheckpoint(chapterCp);
        }

        // p20-02: 后处理阶段统一经 ChapterPostProcessor 模块按固定顺序执行
        chapter = postProcessor.process(projectId, chapter, chapterIndex, synopsis, outline);

        // p12-K3: 向量库 flush 已在 ChapterWriterAgent.indexChapterInChunks 后完成

        // p10-w1-06: 全部后处理完成 → status=DONE（章节真正就绪）
        // p20-01: 仅从 POST_PROCESSING 推进到 DONE——applyRepairClassification 标记的
        // NEEDS_MANUAL_REVIEW 是人工复核裁决，不得被后处理完成覆盖
        if (chapter.getStatus() == com.writing.novel.domain.Chapter.Status.POST_PROCESSING) {
            chapter.setStatus(com.writing.novel.domain.Chapter.Status.DONE);
            repository.saveChapter(chapter);
        }
        // p37-16: 当前稿产出并完成检查后核对作者要求（无适用要求不调用额外模型）
        captureDirectiveEvidenceAfterGeneration(projectId, chapterIndex);
        return chapter;
    }

    /** 检查当前章是否是某卷最后一章，如果是则生成卷级摘要 */
    void checkAndGenerateVolumeSummary(String projectId, Outline outline, int chapterIndex) {
        for (Volume v : outline.getVolumes()) {
            if (v.getSynopses().isEmpty()) continue;
            int lastChapter = v.getSynopses().stream()
                    .mapToInt(ChapterSynopsis::getChapterIndex).max().orElse(0);
            int firstChapter = v.getSynopses().stream()
                    .mapToInt(ChapterSynopsis::getChapterIndex).min().orElse(0);
            if (chapterIndex == lastChapter) {
                // 这是卷末，生成卷级摘要
                try {
                    generateVolumeSummary(projectId, v, firstChapter, lastChapter);
                } catch (Exception e) {
                    log.warn("卷摘要生成失败: {}", e.getMessage());
                }
                // p10-w3-03: 卷末归档衰减——清理过期边 + 旧问题（根因 B：图表只增不减）
                if (archiveCompactor != null) {
                    try { archiveCompactor.compact(projectId, chapterIndex); }
                    catch (Exception e) { log.warn("卷末归档失败: {}", e.getMessage()); }
                }
                // p10-w3-01: 每完成 5 卷触发总纲中段重规划——基于已写真典事实重规划剩余未写卷
                if (v.getVolumeIndex() > 0 && v.getVolumeIndex() % 5 == 0) {
                    try { replanRemainingOutline(projectId, chapterIndex); }
                    catch (Exception e) { log.warn("总纲重规划失败: {}", e.getMessage()); }
                }
                break;
            }
        }
    }

    /** 生成卷级摘要：汇总本卷所有章节摘要为一条全局摘要 */
    private void generateVolumeSummary(String projectId, Volume volume, int fromChapter, int toChapter) {
        // 收集本卷的细粒度摘要
        var summaries = summaryService.findCovering(projectId, fromChapter);
        if (summaries.isEmpty()) return;
        StringBuilder combined = new StringBuilder();
        for (var s : summaries) {
            if (s.getFromChapter() >= fromChapter && s.getToChapter() <= toChapter) {
                combined.append(s.getContent()).append("\n");
            }
        }
        if (combined.isEmpty()) return;
        // 用 MemoryManager 压缩为卷级摘要
        memoryManager.summarize(projectId, fromChapter, toChapter,
                "卷" + volume.getVolumeIndex() + "「" + volume.getTitle() + "」的详细内容：\n" + combined);
        // so-fix: 卷级摘要生成后，删除被其覆盖的细粒度摘要（避免范围重叠致前情摘要重复）
        removeSupersededSummaries(projectId, fromChapter, toChapter);
        // C1: master outline 演进——把已写卷摘要追加到 outline.premise，让后续 planVolume 知道前文发生了什么
        updateMasterOutlineWithWrittenDigest(projectId);
        // C2: 全书摘要——用"上一版全书摘要 + 本卷摘要"重新压缩为 ≤3 句
        updateBookSummary(projectId, fromChapter, toChapter);
        // p19-03: 全书结构化快照——主线进度/阵营/人物状态/时间线的长程状态板
        try {
            String volumeSummary = repository.findSummariesByProject(projectId).stream()
                    .filter(s -> s.getToChapter() == toChapter && s.getFromChapter() == fromChapter)
                    .map(s -> s.getContent()).findFirst().orElse("");
            updateBookSnapshot(projectId, toChapter, volumeSummary);
        } catch (Exception e) {
            log.debug("全书快照更新调度失败: {}", e.getMessage());
        }
        // p3-04: 卷末触发质量评价（反馈闭环）
        try {
            var report = qualityEvaluationAgent.evaluate(projectId);
            // 持久化评价记录（供下一卷 ContextAssembler 读取），包含全部 7 个维度
            var scoresMap = new java.util.HashMap<String, Integer>();
            scoresMap.put("consistency", report.consistency);
            scoresMap.put("style", report.style);
            scoresMap.put("plot", report.plot);
            scoresMap.put("characters", report.characters);
            scoresMap.put("worldbuilding", report.worldbuilding);
            scoresMap.put("dialogue", report.dialogue);
            scoresMap.put("pattern_diversity", report.patternDiversity);
            var record = new EvaluationRecord(projectId, volume.getVolumeIndex(),
                    scoresMap,
                    report.overallScore, report.summary,
                    buildImprovementNotes(report));
            repository.saveEvaluationRecord(record);
            if (report.overallScore < 6) {
                // 低分记录为 PLOT 类型 issue（之前误用 WORLDBUILDING）
                repository.saveContinuityIssue(new ContinuityIssue(
                        projectId, toChapter,
                        ContinuityIssue.Type.PLOT,
                        ContinuityIssue.Severity.MEDIUM,
                        "卷" + volume.getVolumeIndex() + "质量评分" + report.overallScore
                                + "/10：" + report.summary));
            }
        } catch (Exception e) {
            log.warn("质量评价失败: {}", e.getMessage());
        }

        // p5-c3: ChekhovAudit——卷末检查未回收伏笔
        try {
            chekhovAudit(projectId, volume.getVolumeIndex(), fromChapter, toChapter);
            // NCS-123: 检测器产出趋势门禁（卷级聚合，非逐章阻断）
            if (detectorTrendService != null) {
                String trendWarning = detectorTrendService.volumeGate(projectId, fromChapter, toChapter);
                if (!trendWarning.isEmpty()) {
                    EventLog.emit("VOLUME_TREND_WARNING", Map.of(
                            "projectId", projectId, "fromChapter", fromChapter,
                            "toChapter", toChapter,
                            "warning", EventLog.truncate(trendWarning, 200)));
                }
            }
        } catch (Exception e) {
            log.warn("伏笔审计失败: {}", e.getMessage());
        }

        // p11-H2: 卷末批量升级角色——把本卷出现但无档案的实体升级为角色
        try {
            var upgraded = characterProfileUpgrader.upgrade(projectId, toChapter);
            if (!upgraded.isEmpty()) {
                log.info("卷末角色升级(卷{}): {} → {}", volume.getVolumeIndex(),
                        upgraded.size(), String.join("、", upgraded));
            }
        } catch (Exception e) {
            log.warn("卷末角色升级失败: {}", e.getMessage());
        }

        // p6-4: 卷末动态更新 StyleProfile（从最近5章提取风格趋势）
        try {
            updateStyleProfile(projectId, fromChapter, toChapter);
        } catch (Exception e) {
            log.warn("风格基线更新失败: {}", e.getMessage());
        }
    }

    /** p6-4: 卷末从最近章节重新评估 StyleProfile */
    private void updateStyleProfile(String projectId, int fromChapter, int toChapter) {
        // B2: 只加载最近 5 章，不全表加载
        var chapters = repository.findChaptersByIndexRange(projectId, toChapter - 4, toChapter);
        var recentText = chapters.stream()
                .map(c -> {
                    String content = c.getContent();
                    return content != null ? content.substring(0, Math.min(300, content.length())) : "";
                })
                .reduce((a, b) -> a + "\n---\n" + b)
                .orElse("");
        if (recentText.length() < 100) return;
        styleGuardian.updateProfile(projectId, recentText);
        log.info("风格基线已更新（卷末 ch{}）", toChapter);
    }

    /** p5-c3: 卷末伏笔审计——检查本卷埋下但未回收的伏笔 */
    private void chekhovAudit(String projectId, int volumeIndex, int fromChapter, int toChapter) {
        var allForeshadowings = repository.findForeshadowingsByProject(projectId);
        // 本卷埋下的伏笔
        var plantedInVolume = allForeshadowings.stream()
                .filter(f -> f.getPlantedAtChapter() >= fromChapter && f.getPlantedAtChapter() <= toChapter)
                .toList();
        // 尚未到预计回收章的跨卷规划不属于本卷遗漏；未定期限的只作提醒。
        var unresolved = plantedInVolume.stream()
                .filter(f -> !f.isResolved())
                .filter(f -> f.getExpectedResolveChapter() == null || f.getExpectedResolveChapter() <= toChapter)
                .toList();
        long dueCount = unresolved.stream()
                .filter(f -> f.getExpectedResolveChapter() != null)
                .count();

        if (!unresolved.isEmpty()) {
            StringBuilder desc = new StringBuilder();
            desc.append("卷").append(volumeIndex).append("有 ")
                .append(unresolved.size()).append(" 条未回收伏笔：\n");
            for (var f : unresolved) {
                desc.append("- ").append(f.getDescription())
                    .append("（埋于第").append(f.getPlantedAtChapter()).append("章）\n");
            }
            desc.append("其中 ").append(dueCount).append(" 条已到预计回收章；未设期限的条目仅作提醒。");
            desc.append("建议在后续章节中回收这些伏笔。");
            // 保留 > 5 条的 HIGH 门槛，但只统计已有明确到期依据的条目。
            var severity = dueCount > 5
                    ? ContinuityIssue.Severity.HIGH
                    : ContinuityIssue.Severity.MEDIUM;
            repository.saveContinuityIssue(new ContinuityIssue(
                    projectId, toChapter,
                    ContinuityIssue.Type.FORESHADOWING,
                    severity,
                    desc.toString()));
            log.info("ChekhovAudit(卷{}): {} 条未回收伏笔 [{}]", volumeIndex, unresolved.size(), severity);
        }
    }







    /**
     * p11-q1-03: 软门禁——门禁重试上限后仍不过，标记 quality_risk（记 ContinuityIssue，不阻塞推进）。
     * INTERACTIVE 模式下，doConceiving 末尾的 PAUSED_AT_CHECKPOINT 会让人工看到这些 quality_risk 并介入。
     */
    void markQualityRisk(String projectId, String stage, String feedback) {
        try {
            repository.saveContinuityIssue(new ContinuityIssue(projectId, 0,
                    ContinuityIssue.Type.STRUCTURE, ContinuityIssue.Severity.MEDIUM,
                    "[quality_risk]" + stage + "门禁未过：" + feedback));
            log.warn("quality_risk 标记({}): {}", stage, feedback);
            // p11-q3-01: 质量事件遥测——门禁失败可观测
            EventLog.emit("QUALITY_GATE", Map.of(
                    "stage", stage, "feedback", EventLog.truncate(feedback, 200)));
        } catch (Exception e) {
            log.warn("quality_risk 标记失败: {}", e.getMessage());
        }
    }

    /**
     * p11-H1: 角色群像扩展——根据篇幅动态设计更多配角。
     * 初始设计只有 3 个角色（主角+对手+女性配角），长篇需要更多。
     * 在大纲生成后，根据卷数计算需要的配角数量，追加设计。
     */
    private void expandCharacterEnsemble(String projectId, Project p) {
        var existingChars = repository.findCharactersByProject(projectId);
        var outline = repository.findOutlineByProject(projectId).orElse(null);
        if (outline == null) return;

        int totalChapters = outline.getVolumes().stream()
                .mapToInt(v -> v.getSynopses().size()).sum();
        // 目标：每 3-4 章至少 1 个有名角色，至少 8 个（长篇基线）
        int targetCount = Math.max(8, totalChapters / 4);
        int need = targetCount - existingChars.size();
        if (need <= 0) return;

        log.info("角色群像扩展: 当前{}个角色，目标{}个，追加设计{}个配角",
                existingChars.size(), targetCount, need);

        String worldSummary = collectWorldbuildingSummary(projectId);
        String charDiversity = creativeDiversityService != null
                ? creativeDiversityService.getDiversityGuide(projectId, "CHARACTER") : "";

        // 从大纲提取角色需求线索（出场人物、势力等）
        String outlineContext = outline.getVolumes().stream()
                .map(v -> v.getTitle() + "：" + (v.getSummary() != null ? v.getSummary().substring(0, Math.min(60, v.getSummary().length())) : ""))
                .reduce((a, b) -> a + "\n" + b).orElse("");

        for (int i = 0; i < need; i++) {
            try {
                var currentChars = repository.findCharactersByProject(projectId);
                String charList = currentChars.stream()
                        .map(c -> c.getName() + "（" + c.getRole() + "）")
                        .reduce((a, b) -> a + "、" + b).orElse("无");

                // 根据已有角色分布，设计不同类型的配角
                String roleType = determineNeededRoleType(currentChars, i);
                String req = "配角设计（第" + (i + 1) + "批）。角色类型：" + roleType
                        + "\n故事构思：" + p.getConcept()
                        + "\n大纲脉络：\n" + outlineContext
                        + "\n已有人物：" + charList
                        + "\n要求：这个新角色必须与已有角色形成新的关系（盟友/对手/利用/暗恋/血亲），丰富人物关系网。";

                characterDesigner.design(projectId, req, currentChars, charDiversity);
                log.info("配角群像设计完成(第{}批)", i + 1);
            } catch (Exception e) {
                log.warn("配角设计失败(第{}批): {}", i + 1, e.getMessage());
                break;
            }
        }
    }

    /** 根据已有角色分布，确定下一个需要什么类型的角色 */
    private String determineNeededRoleType(java.util.List<com.writing.novel.domain.Character> existing, int batch) {
        long antagonists = existing.stream().filter(c -> c.getRole() != null &&
                (c.getRole().contains("反派") || c.getRole().contains("对手"))).count();
        long allies = existing.stream().filter(c -> c.getRole() != null &&
                (c.getRole().contains("盟友") || c.getRole().contains("导师"))).count();
        long females = existing.stream().filter(c -> c.getRole() != null &&
                c.getRole().contains("女")).count();

        return switch (batch % 5) {
            case 0 -> antagonists < 2 ? "新的对手/反派（与主角形成不同维度的对立）" : "中间派/骑墙角色";
            case 1 -> "新的盟友/助手（有自己的秘密和动机）";
            case 2 -> females < 2 ? "新的女性角色（独立动机，非工具人）" : "势力代表/组织人物";
            case 3 -> "亦敌亦友的复杂角色";
            default -> "次要配角（丰富世界感的日常角色）";
        };
    }

    /**
     * 从章节梗概抽取伏笔规划（p3-03）。
     *
     * 规划必须保留来源章和预计回收章；大纲中的“回收”行只是对已有规划的
     * 引用，不能再创建一条新的未回收记录。总纲、卷摘要没有可靠的章节来源，
     * 因而不再被伪装成第 1 章写入台账。
     */
    private void extractForeshadowingsFromOutline(String projectId) {
        try {
            if (!repository.findForeshadowingsByProject(projectId).isEmpty()) return;
            var outlineOpt = repository.findOutlineByProject(projectId);
            if (outlineOpt.isEmpty()) return;

            java.util.Map<String, OutlineForeshadowPlan> plants = new java.util.LinkedHashMap<>();
            java.util.List<OutlineForeshadowReference> resolves = new java.util.ArrayList<>();
            var synopses = outlineOpt.get().getVolumes().stream()
                    .flatMap(v -> v.getSynopses().stream())
                    .sorted(java.util.Comparator.comparingInt(ChapterSynopsis::getChapterIndex))
                    .toList();
            for (ChapterSynopsis synopsis : synopses) {
                for (String item : outlineForeshadowItems(synopsis.getSynopsis())) {
                    String trimmed = item.trim();
                    if (trimmed.isEmpty() || "无".equals(trimmed)) continue;
                    if (startsWithForeshadowResolve(trimmed)) {
                        resolves.add(new OutlineForeshadowReference(
                                foreshadowKey(trimmed), synopsis.getChapterIndex()));
                        continue;
                    }
                    String description = stripForeshadowPrefix(trimmed);
                    if (description.length() < 2) continue;
                    String key = foreshadowKey(description);
                    if (key.length() < 2) continue;
                    OutlineForeshadowPlan existing = plants.get(key);
                    Integer expected = parseExpectedResolveChapter(trimmed);
                    if (existing == null) {
                        plants.put(key, new OutlineForeshadowPlan(
                                description, synopsis.getChapterIndex(), expected, trimmed));
                    } else if (existing.expectedResolveChapter() == null && expected != null) {
                        plants.put(key, existing.withExpectedResolveChapter(expected));
                    }
                }
            }

            // “回收-xxx” 只补充规划的回收节点；它不代表正文已经完成回收。
            for (OutlineForeshadowReference reference : resolves) {
                plants.values().stream()
                        .filter(plan -> foreshadowKeysMatch(plan.key(), reference.key()))
                        .findFirst()
                        .ifPresent(plan -> {
                            if (plan.expectedResolveChapter() == null) {
                                plants.put(plan.key(), plan.withExpectedResolveChapter(reference.chapterIndex()));
                            }
                        });
            }

            int count = 0;
            for (OutlineForeshadowPlan plan : plants.values()) {
                if (count >= 20) break;
                Foreshadowing saved = new Foreshadowing(
                        projectId, plan.description(), "从第" + plan.plantedAtChapter() + "章大纲自动抽取",
                        plan.plantedAtChapter());
                saved.setExpectedResolveChapter(plan.expectedResolveChapter());
                saved.setPriority(parseForeshadowPriority(plan.raw()));
                saved.setMustResolve(saved.getPriority() == Foreshadowing.Priority.HIGH);
                repository.saveForeshadowing(saved);
                count++;
            }
        } catch (Exception e) {
            log.warn("伏笔抽取失败: {}", e.getMessage());
        }
    }

    private record OutlineForeshadowPlan(String description, int plantedAtChapter,
                                         Integer expectedResolveChapter, String raw) {
        String key() { return foreshadowKey(description); }
        OutlineForeshadowPlan withExpectedResolveChapter(Integer chapter) {
            return new OutlineForeshadowPlan(description, plantedAtChapter, chapter, raw);
        }
    }

    private record OutlineForeshadowReference(String key, int chapterIndex) {}

    private static List<String> outlineForeshadowItems(String synopsis) {
        if (synopsis == null || synopsis.isBlank()) return List.of();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "伏笔[：:]\\s*(.+?)(?=(?:\\n|。\\s*)(?:场景节拍|爽点|人物|目的|张力|伏笔)[：:]|$)",
                java.util.regex.Pattern.DOTALL).matcher(synopsis);
        if (!matcher.find()) return List.of();
        // 括号中的 HIGH；埋第N章→预计第M章 是同一条目的元数据。
        var items = new java.util.ArrayList<String>();
        String text = matcher.group(1);
        int depth = 0;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '(' || ch == '（') depth++;
            else if (ch == ')' || ch == '）') depth = Math.max(0, depth - 1);
            else if (depth == 0 && (ch == ';' || ch == '；' || ch == '\n')) {
                String item = text.substring(start, i).trim();
                if (!item.isEmpty()) items.add(item);
                start = i + 1;
            }
        }
        String last = text.substring(start).trim();
        if (!last.isEmpty()) items.add(last);
        return items;
    }

    private static boolean startsWithForeshadowResolve(String value) {
        return value.startsWith("回收") || value.startsWith("兑现") || value.startsWith("揭示");
    }

    private static String stripForeshadowPrefix(String value) {
        return value.replaceFirst("^(?:埋设|埋下|伏笔)[：:\\-—]?\\s*", "").trim();
    }

    private static String foreshadowKey(String value) {
        if (value == null) return "";
        String key = value.replaceFirst("^(?:回收|兑现|揭示)[：:\\-—]?\\s*", "")
                .replaceFirst("^(?:埋设|埋下|伏笔)[：:\\-—]?\\s*", "")
                .replaceAll("[（(].*?[）)]", "")
                .replaceAll("(?:预计|计划)第?\\s*\\d+章(?:回收|揭示|兑现)?", "")
                .replaceAll("[。；;：:]", "")
                .trim();
        return key;
    }

    private static boolean foreshadowKeysMatch(String left, String right) {
        if (left == null || right == null || left.isBlank() || right.isBlank()) return false;
        return left.equals(right) || (left.length() >= 4 && right.length() >= 4
                && (left.contains(right) || right.contains(left)));
    }

    private static Integer parseExpectedResolveChapter(String value) {
        if (value == null) return null;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "(?:预计|计划)\\s*第?\\s*(\\d+)\\s*章|第\\s*(\\d+)\\s*章\\s*(?:回收|揭示|兑现)").matcher(value);
        if (!matcher.find()) return null;
        return Integer.valueOf(matcher.group(1) != null ? matcher.group(1) : matcher.group(2));
    }

    private static Foreshadowing.Priority parseForeshadowPriority(String value) {
        if (value != null && value.contains("HIGH")) return Foreshadowing.Priority.HIGH;
        if (value != null && value.contains("LOW")) return Foreshadowing.Priority.LOW;
        return Foreshadowing.Priority.MEDIUM;
    }

    /** 收集世界观摘要（供 CharacterDesigner/Plotter 注入用） */
    private String collectWorldbuildingSummary(String projectId) {
        var entries = repository.findKnowledgeEntriesByProject(projectId);
        if (entries.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (var e : entries) {
            sb.append("【").append(e.getName()).append("】").append(e.getContent()).append("\n");
        }
        return sb.toString().trim();
    }

    /** 收集人物卡摘要（供 Plotter 注入用） */
    private String collectCharacterSummary(String projectId) {
        var chars = repository.findCharactersByProject(projectId);
        if (chars.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (var c : chars) {
            sb.append(c.getName()).append("（").append(c.getRole()).append("）");
            if (c.getPersonality() != null) sb.append(" 性格：").append(c.getPersonality());
            if (c.getAbility() != null) sb.append(" 能力：").append(c.getAbility());
            sb.append("\n");
        }
        return sb.toString().trim();
    }


    /** p11-q0-01: 解析 synopsis.cast（逗号/顿号/空格分隔）为名字列表，供收窄 */
    static List<String> parseCast(String cast) {
        if (cast == null || cast.isBlank()) return List.of();
        return java.util.Arrays.stream(cast.split("[,，、/\\s]+"))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /**
     * 连续写作：从当前章节一直写到大纲结束，或达到 maxChapters 上限。
     * 返回本次新写的章节数。
     */
    public int runWritingLoop(String projectId, int maxChapters) {
        return runWritingLoop(projectId, maxChapters, () -> false);
    }

    /**
     * 连续写作：从当前章节一直写到大纲结束，或达到 maxChapters 上限。
     * p13-mc-p0-01: 支持取消信号检查——每章前调 cancelChecker，true 则退出。
     * @param cancelChecker 返回 true 表示用户请求取消，循环在当前章完成后退出
     * @return 本次新写的章节数
     */
    public int runWritingLoop(String projectId, int maxChapters, java.util.function.BooleanSupplier cancelChecker) {
        return runWritingLoop(projectId, maxChapters, cancelChecker, null);
    }

    /** p18-03: 带作者指令的连续写作——指令逐章沿用（用户对本次批写的定向要求） */
    public int runWritingLoop(String projectId, int maxChapters, java.util.function.BooleanSupplier cancelChecker, String instruction) {
        // p10-w3-06: 从持久化恢复多样性追踪状态（重启后不丢失）
        try { diversityTracker.restore(projectId, repository); } catch (Exception ignored) {}

        int written = 0;
        int consecutiveFailures = 0;
        int consecutiveHighIssues = 0;

        while (written < maxChapters) {
            // p13-mc-p0-01: 每章前检查取消信号
            if (cancelChecker.getAsBoolean()) {
                log.info("写作循环被取消（用户请求），已写 {} 章", written);
                break;
            }
            // p33-01（体验 #18）：每章前检查人审屏障——任一 ACTIVE 屏障存在即停，
            // 修复标记后循环绕过停点继续写下一章
            if (!repository.findActiveReviewBarriers(projectId).isEmpty()) {
                log.info("写作循环遇人审屏障暂停，已写 {} 章", written);
                break;
            }
            Project p = loadProject(projectId);
            if (p.getState() == NovelState.PAUSED_AT_CHECKPOINT) {
                log.info("写作循环遇暂停，已写 {} 章", written);
                break;
            }
            // p21-02: 终态命令门禁——循环入口拒绝 FINAL/NEEDS_REVISION
            if (p.getState() == NovelState.FINAL || p.getState() == NovelState.NEEDS_REVISION) {
                throw new IllegalStateException("项目处于终态 " + p.getState() + "，拒绝写作命令");
            }
            Outline outline = repository.findOutlineByProject(projectId).orElse(null);
            if (outline == null) break;

            // p12-fix: 分层大纲延迟生成——大纲耗尽时尝试生成下一卷
            if (!hasNextChapter(outline, p.getCurrentChapterIndex())) {
                boolean extended = tryExtendLayeredOutline(projectId, p);
                if (extended) {
                    // 重新加载大纲
                    outline = repository.findOutlineByProject(projectId).orElse(null);
                }
                if (outline == null || !hasNextChapter(outline, p.getCurrentChapterIndex())) {
                    // p16-02: 大纲真正耗尽——mustResolve 伏笔未回收时先追加回收卷（有界一次），
                    // 回收指令写进新章梗概 writingNotes（原路径写 ContinuityIssue 后直接 break，指令无人消费）
                    if (written < maxChapters
                            && appendForeshadowingRecoveryVolumeIfNeeded(projectId, p)) {
                        continue; // 回收卷已追加，写作循环继续写回收章
                    }
                    // NCS-082（听雪刀 P1-4）：交互模式大纲耗尽且关键伏笔未回收时不自动完结——
                    // 停在 VOLUME_DONE 等作者决策（显式追加回收卷 / 台账结清 / 宣告完结 NCS-079）
                    if (p.getMode() == ProjectMode.INTERACTIVE && hasUnresolvedMustResolve(projectId)) {
                        p.setState(NovelState.VOLUME_DONE);
                        repository.saveProject(p);
                        EventLog.emit("FORESHADOW_RECOVERY_DECISION", Map.of(
                                "projectId", projectId,
                                "currentChapter", p.getCurrentChapterIndex(),
                                "action", "await_author"));
                        log.info("交互模式大纲耗尽且关键伏笔未回收，停在决策点(项目{}, 章{})",
                                projectId, p.getCurrentChapterIndex());
                        break;
                    }
                    finishBookWithGate(projectId, p, written);
                    break;
                }
            }

            if (p.getState() == NovelState.VOLUME_DONE) {
                p.setState(NovelState.WRITING);
                repository.saveProject(p);
            }

            // 异常容错：单章失败不崩溃整个 loop
            try {
                Chapter ch = runWritingOneChapter(projectId, instruction);
                if (ch == null) break;
                written++;
                consecutiveFailures = 0; // 成功则重置失败计数

                // p20-01: 本章被标记待人工复核 → 循环停下，复核通过前不写下一章
                if (ch.getStatus() == Chapter.Status.NEEDS_MANUAL_REVIEW) {
                    log.info("章{}标记待人工复核，写作循环暂停（已写 {} 章）", ch.getChapterIndex(), written);
                    EventLog.emit("WRITING_PAUSED", Map.of(
                            "projectId", projectId,
                            "chapterIndex", ch.getChapterIndex(),
                            "reason", "NEEDS_MANUAL_REVIEW"));
                    break;
                }

                // 质量熔断：检查本章是否有 HIGH 级连续性问题
                // p12-fix: 放宽阈值 3→10（百万字写作中 ConsistencyGuard 检测敏感，
                // 能力越界/凭空证据在武侠/玄幻题材中高频出现，不应因此中断写作）
                boolean hasHighIssue = !repository.findUnresolvedIssues(projectId).stream()
                        .filter(i -> i.getDetectedAtChapter() == ch.getChapterIndex())
                        .filter(i -> i.getSeverity() == ContinuityIssue.Severity.HIGH)
                        .toList().isEmpty();
                if (hasHighIssue) {
                    consecutiveHighIssues++;
                    // p11-q2-05: 熔断阈值可配置（回收 3→10 权宜放宽），从 WritingPreferences 读
                    int cbThreshold = repository.findWritingPreferences(projectId)
                            .map(pr -> pr.getCircuitBreakerThreshold()).orElse(10);
                    if (consecutiveHighIssues >= cbThreshold) {
                        // 连续 HIGH 问题达阈值，暂停写作
                        log.warn("连续 {} 章 HIGH 问题（阈值 {}），暂停写作", consecutiveHighIssues, cbThreshold);
                        // p11-q3-01: 质量事件遥测——质量熔断可观测
                        EventLog.emit("QUALITY_CIRCUIT_BREAKER", Map.of(
                                "consecutiveHighIssues", consecutiveHighIssues, "threshold", cbThreshold));
                        break;
                    }
                } else {
                    consecutiveHighIssues = 0;
                }
            } catch (Exception e) {
                consecutiveFailures++;
                EventLog.emit("CHAPTER_FAILED", Map.of(
                        "projectId", projectId,
                        "chapterIndex", p.getCurrentChapterIndex() + 1,
                        "errorClass", e.getClass().getSimpleName(),
                        "message", EventLog.truncate(e.getMessage(), 200),
                        "consecutiveFailures", consecutiveFailures));
                // 连续 3 次失败才中止循环（不跳章，保留剧情连续性）
                if (consecutiveFailures >= 3) {
                    break;
                }
                // 退避后重试同一章（绝不推进索引跳章）
                try { Thread.sleep(5000L * consecutiveFailures); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt(); break;
                }
            }
        }
        return written;
    }

    /** p16-02: 回收卷标题标记——用于有界判定（已追加过则不再追加，重启安全） */
    private static final String RECOVERY_VOLUME_MARKER = "伏笔回收";

    /** NCS-082: 是否存在未回收的必须回收伏笔（交互模式决策点判定）。 */
    private boolean hasUnresolvedMustResolve(String projectId) {
        return !repository.findUnresolvedForeshadowings(projectId).stream()
                .filter(Foreshadowing::isMustResolve)
                .toList().isEmpty();
    }

    /**
     * p16-02: 大纲耗尽且 mustResolve 伏笔未回收时，追加『尾声·伏笔回收卷』。
     * 回收指令写进新章梗概 writingNotes（ChapterWriter 的确定性输入通道），替代原
     * 写 ContinuityIssue 后直接 break 的死路。有界：回收卷已存在时返回 false。
     * 包级可见供测试。
     */
    boolean appendForeshadowingRecoveryVolumeIfNeeded(String projectId, Project p) {
        try {
            // NCS-082（听雪刀 P1-4）：交互模式不自动追加尾声回收卷——书不得被系统自动加长，
            // 由作者显式决策（追加回收卷 / 台账结清 / 宣告完结）
            if (p.getMode() == ProjectMode.INTERACTIVE) return false;
            return appendForeshadowingRecoveryVolumeInternal(projectId, p);
        } catch (Exception e) {
            log.warn("追加伏笔回收卷失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * NCS-082: 作者显式追加尾声回收卷（交互模式的确认制入口，有界一次）。
     * @return 是否实际追加（已存在回收卷返回 false）
     */
    public boolean appendForeshadowingRecoveryVolume(String projectId) {
        Project p = loadProject(projectId);
        try {
            return appendForeshadowingRecoveryVolumeInternal(projectId, p);
        } catch (Exception e) {
            log.warn("追加伏笔回收卷失败: {}", e.getMessage());
            return false;
        }
    }

    private boolean appendForeshadowingRecoveryVolumeInternal(String projectId, Project p) {
        try {
            // p10.1-e1: 回收前先做重要性审计——被低估的关键伏笔升级后才进入回收名单
            if (foreshadowingImportanceAuditor != null) {
                try {
                    int upgraded = foreshadowingImportanceAuditor.audit(projectId);
                    if (upgraded > 0) log.info("FINAL前伏笔审计: {} 条升级为HIGH", upgraded);
                } catch (Exception e) {
                    log.warn("伏笔审计失败: {}", e.getMessage());
                }
            }
            var criticalUnresolved = repository.findUnresolvedForeshadowings(projectId).stream()
                    .filter(Foreshadowing::isMustResolve)
                    .toList();
            if (criticalUnresolved.isEmpty()) return false;
            Outline outline = repository.findOutlineByProject(projectId).orElse(null);
            if (outline == null) return false;
            if (outline.getVolumes().stream()
                    .anyMatch(v -> v.getTitle() != null && v.getTitle().contains(RECOVERY_VOLUME_MARKER))) {
                return false; // 有界：回收卷已追加过
            }

            int startChapter = p.getCurrentChapterIndex() + 1;
            int chapterCount = Math.min(8, Math.max(3, criticalUnresolved.size() * 2));
            int volumeIndex = outline.getVolumes().stream()
                    .mapToInt(Volume::getVolumeIndex).max().orElse(0) + 1;
            Volume recovery = new Volume(projectId, volumeIndex,
                    "尾声·" + RECOVERY_VOLUME_MARKER + "卷",
                    "全书尾声：集中揭晓并回收关键伏笔，收束主线。");
            for (int i = 0; i < chapterCount; i++) {
                int chapterIndex = startChapter + i;
                ChapterSynopsis s = new ChapterSynopsis(projectId, chapterIndex,
                        "第" + chapterIndex + "章·伏笔回收",
                        "全书尾声：集中揭晓并回收关键伏笔，收束主线，走向结局。");
                StringBuilder notes = new StringBuilder("【最终回收章】必须在正文中明确揭晓/解答以下关键悬念：\n");
                boolean any = false;
                for (int j = i; j < criticalUnresolved.size(); j += chapterCount) {
                    var f = criticalUnresolved.get(j);
                    notes.append("- ").append(f.getDescription())
                            .append("（埋于第").append(f.getPlantedAtChapter()).append("章）\n");
                    any = true;
                }
                s.setWritingNotes(any ? notes.toString() : "收束主线，为全书作结；不引入新悬念。");
                recovery.getSynopses().add(s);
            }
            outline.getVolumes().add(recovery);
            repository.saveOutline(outline);
            for (var f : criticalUnresolved) {
                if (f.getExpectedResolveChapter() == null) {
                    f.setExpectedResolveChapter(startChapter + chapterCount - 1);
                    repository.saveForeshadowing(f);
                }
            }
            log.warn("mustResolve 伏笔 {} 条未回收，已追加尾声回收卷（第{}-{}章）",
                    criticalUnresolved.size(), startChapter, startChapter + chapterCount - 1);
            // NCS-089：追加回收卷意味着作者选择"写作回收章"——若正停在 NEEDS_REVISION，
            // 回到 WRITING 让 WRITE_CHAPTER/WRITE_ALL 恢复可用（否则回收卷永远写不了）
            if (p.getState() == NovelState.NEEDS_REVISION) {
                p.setState(NovelState.WRITING);
                repository.saveProject(p);
                EventLog.emit("RECOVERY_RESUMED", Map.of(
                        "projectId", projectId,
                        "chapterIndex", p.getCurrentChapterIndex(),
                        "state", NovelState.WRITING.name()));
            }
            return true;
        } catch (Exception e) {
            log.warn("追加伏笔回收卷失败: {}", e.getMessage());
            return false;
        }
    }

    /** p16-02: 完结流程——非关键伏笔警告 + 成书门禁，统一设置 FINAL/NEEDS_REVISION */
    private void finishBookWithGate(String projectId, Project p, int written) {
        var unresolved = repository.findUnresolvedForeshadowings(projectId);
        // 非关键伏笔：记录但不阻塞
        if (unresolved.size() > 3) {
            log.warn("全书未回收伏笔 {} 条 > 3，记录完整性警告", unresolved.size());
            repository.saveContinuityIssue(new ContinuityIssue(
                    projectId, p.getCurrentChapterIndex(),
                    ContinuityIssue.Type.FORESHADOWING,
                    ContinuityIssue.Severity.HIGH,
                    "全书完结时有 " + unresolved.size() + " 条伏笔未回收"));
        }
        // mc-p3-02: 成书质量门禁——检查通过才 FINAL，否则 NEEDS_REVISION
        boolean passed = runFinalAuditGate(projectId, p);
        p.setState(passed ? NovelState.FINAL : NovelState.NEEDS_REVISION);
        repository.saveProject(p);
        log.info("成书门禁: {} → {}", passed ? "PASS" : "FAIL", passed ? "FINAL" : "NEEDS_REVISION");
    }

    /**
     * p21-02: 显式成书门禁重试——仅 NEEDS_REVISION 可触发（人工修订后）。
     * 通过 → FINAL；不通过 → 保持 NEEDS_REVISION。其他状态拒绝。
     */
    public boolean retryFinalAudit(String projectId) {
        Project p = loadProject(projectId);
        if (p.getState() != NovelState.NEEDS_REVISION) {
            throw new IllegalStateException("仅 NEEDS_REVISION 状态可重试成书门禁，当前: " + p.getState());
        }
        boolean passed = runFinalAuditGate(projectId, p);
        p.setState(passed ? NovelState.FINAL : NovelState.NEEDS_REVISION);
        repository.saveProject(p);
        EventLog.emit("FINAL_GATE_RETRY", Map.of("projectId", projectId, "passed", passed));
        log.info("成书门禁重试: {} → {}", projectId, passed ? "FINAL" : "NEEDS_REVISION");
        return passed;
    }

    /**
     * NCS-079（听雪刀 P0）：作者宣告完结——从写作态显式触发行成书门禁。
     * 待审检查点随完结一并拒绝关闭（内容不再等待审核）；门禁通过 → FINAL，
     * 失败 → NEEDS_REVISION（修订后可经 retryFinalAudit 重试）。
     * @return 门禁是否通过
     */
    public boolean declareFinish(String projectId) {
        Project p = loadProject(projectId);
        if (p.getState() == NovelState.FINAL) {
            throw new IllegalStateException("全书已完结（FINAL），无需再次宣告完结");
        }
        if (p.getCurrentChapterIndex() < 1) {
            throw new IllegalStateException("尚未写作任何章节，不能宣告完结");
        }
        for (Checkpoint pending : repository.findPendingCheckpoints(projectId)) {
            pending.reject();
            repository.saveCheckpoint(pending);
        }
        finishBookWithGate(projectId, p, 0);
        boolean passed = p.getState() == NovelState.FINAL;
        EventLog.emit("BOOK_FINISH_DECLARED", Map.of(
                "projectId", projectId,
                "passed", passed,
                "state", p.getState().name()));
        log.info("作者宣告完结(项目{}): 门禁后状态 {}", projectId, p.getState());
        return passed;
    }

    /** 统计项目总字数 */
    public int getTotalWordCount(String projectId) {
        // p12-K3: 使用 SUM 聚合，不全量加载正文
        return repository.getTotalWordCount(projectId);
    }

    void generateSummary(String projectId, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i <= to; i++) {
            repository.findChapterByIndex(projectId, i).ifPresent(c ->
                    sb.append(c.getContent()).append("\n"));
        }
        if (!sb.isEmpty()) {
            Summary fresh = memoryManager.summarize(projectId, from, to, sb.toString());
            // mc-p2-01: 记录派生来源版本 + 清 dirty（重算后内容已是最新）
            int srcVersion = repository.findChapterByIndex(projectId, to)
                    .map(com.writing.novel.domain.Chapter::getVersion).orElse(0);
            fresh.setSourceChapterVersion(srcVersion);
            fresh.setDirty(false);
            repository.saveSummary(fresh);
            // so-fix: 细粒度摘要生成后也清理被覆盖的更细粒度旧摘要
            removeSupersededSummaries(projectId, from, to, fresh.getId());
        }
    }

    /**
     * p10-w1-02: 原地重算一条已有摘要（用于卷级 dirty 重算）。
     * 与 generateSummary 的区别：更新现有 Summary（保留 id、不清细粒度摘要），
     * 避免重写一章触发 removeSupersededSummaries 误删该卷细粒度摘要。
     * 修复 GAP#10：卷级 dirty 摘要此前永不重算 → 该卷剧情在后续章节上下文中静默消失。
     */
    private void recomputeExistingSummary(String projectId, Summary existing) {
        StringBuilder sb = new StringBuilder();
        for (int i = existing.getFromChapter(); i <= existing.getToChapter(); i++) {
            repository.findChapterByIndex(projectId, i)
                    .ifPresent(c -> sb.append(c.getContent()).append("\n"));
        }
        if (sb.isEmpty()) {
            // 无正文可重算——至少清 dirty 避免永久悬挂（保留旧内容）
            existing.setDirty(false);
            repository.saveSummary(existing);
            return;
        }
        Summary fresh = memoryManager.summarize(projectId,
                existing.getFromChapter(), existing.getToChapter(), sb.toString());
        int srcVersion = repository.findChapterByIndex(projectId, existing.getToChapter())
                .map(com.writing.novel.domain.Chapter::getVersion).orElse(0);
        existing.setContent(fresh.getContent());
        existing.setSourceChapterVersion(srcVersion);
        existing.setDirty(false);
        repository.saveSummary(existing);
        // summarize 会落库一条临时 fresh——删除避免与现有卷摘要重复（同 updateBookSummary 模式）
        repository.deleteSummary(fresh.getId());
        log.info("卷级摘要已重算(范围{}-{}): {}",
                existing.getFromChapter(), existing.getToChapter(),
                existing.getContent().length() > 40 ? existing.getContent().substring(0, 40) + "..." : existing.getContent());
    }

    /**
     * so-fix: 删除被 [fromChapter, toChapter] 完全覆盖的更细粒度摘要，
     * 避免范围重叠导致前情摘要内容重复（10章评估发现的 consistency 降分根因）。
     * 保留刚生成的摘要自身（exceptId）和比当前范围更粗的摘要。
     */
    private void removeSupersededSummaries(String projectId, int fromChapter, int toChapter) {
        removeSupersededSummaries(projectId, fromChapter, toChapter, null);
    }

    private void removeSupersededSummaries(String projectId, int fromChapter, int toChapter, String exceptId) {
        int currentSpan = toChapter - fromChapter + 1;
        for (var s : repository.findSummariesByProject(projectId)) {
            if (exceptId != null && exceptId.equals(s.getId())) continue;
            int span = s.getToChapter() - s.getFromChapter() + 1;
            // 只删除比当前摘要更细粒度且被完全覆盖的（防止误删卷级摘要）
            if (span < currentSpan
                    && s.getFromChapter() >= fromChapter
                    && s.getToChapter() <= toChapter) {
                log.info("清理被覆盖的细粒度摘要: [{}-{}] (被 [{}-{}] 覆盖)",
                        s.getFromChapter(), s.getToChapter(), fromChapter, toChapter);
                repository.deleteSummary(s.getId());
            }
        }
    }

    /**
     * C1: master outline 演进——卷末后把已写卷摘要追加到 outline.premise，
     * 让后续 planVolume 知道前文实际发生了什么（而非只看原始静态总纲）。
     */
    private void updateMasterOutlineWithWrittenDigest(String projectId) {
        try {
            var outlineOpt = repository.findOutlineByProject(projectId);
            if (outlineOpt.isEmpty()) return;
            var outline = outlineOpt.get();
            String premise = outline.getPremise();
            if (premise == null) premise = "";

            // 从卷级摘要重建"已写卷摘要"段
            var volumeSums = repository.findSummariesByProject(projectId).stream()
                    .filter(s -> s.getToChapter() - s.getFromChapter() + 1 > 3)
                    .sorted(java.util.Comparator.comparingInt(s -> s.getFromChapter()))
                    .toList();
            if (volumeSums.isEmpty()) return;

            StringBuilder digest = new StringBuilder("\n\n【已写卷摘要（实际已发生剧情）】\n");
            int count = 0;
            for (var s : volumeSums) {
                count++;
                String c = s.getContent();
                if (c == null) continue;
                // 超过 10 卷时，早期卷合并
                if (count <= volumeSums.size() - 10) continue; // 跳过早期卷
                digest.append("卷").append(count).append("(ch").append(s.getFromChapter()).append("-")
                        .append(s.getToChapter()).append("): ")
                        .append(c.length() > 80 ? c.substring(0, 80) + "..." : c).append("\n");
            }

            // 删除旧的【已写卷摘要】段（如果有），追加新的
            int marker = premise.indexOf("【已写卷摘要");
            String base = marker >= 0 ? premise.substring(0, marker).trim() : premise.trim();
            outline.setPremise(base + digest.toString());
            repository.saveOutline(outline);
            // p10-w1-01: premise 变更后使稳定段缓存失效，后续章节组装能读到新总纲
            contextAssembler.invalidateProject(projectId);
            log.info("master outline 已更新已写卷摘要（{} 卷）", volumeSums.size());
        } catch (Exception e) {
            log.warn("更新 master outline 已写卷摘要失败: {}", e.getMessage());
        }
    }

    /**
     * C2: 更新全书摘要——卷末把"上一版全书摘要 + 本卷摘要"压缩为≤3句全书梗概，
     * 存入 project.book_summary，由 ContextAssembler 注入上下文最顶部（修复记忆地平线坍塌）。
     */
    /**
     * p19-03: 全书结构化快照——卷末用上一版快照+本卷摘要+关键真典增量更新。
     * 失败/非 JSON 输出保留旧版，不阻塞写作；包级可见供测试。
     */
    /**
     * NCS-116: 快照 JSON 提取（含 mainlineProgress 校验；无效返回 null）。
     * 旧逻辑 readTree 异常直接进外层 catch（与其它失败混在一起无事件）。
     */
    public static String extractSnapshotJson(String content) {
        if (content == null || content.isBlank()) return null;
        var parsed = com.writing.novel.llm.LlmJson.parse(content);
        if (!parsed.ok() || !parsed.node().isObject()
                || parsed.node().path("mainlineProgress").isMissingNode()) {
            return null;
        }
        return parsed.node().toString();
    }

    void updateBookSnapshot(String projectId, int toChapter, String volumeSummary) {
        try {
            Project p = loadProject(projectId);
            String previous = p.getBookSnapshot() != null ? p.getBookSnapshot() : "（暂无，首版）";
            String keyFacts = "";
            if (storyCanon != null) {
                keyFacts = storyCanon.recall(projectId).stream()
                        .filter(f -> f.getImportance() >= 4)
                        .sorted(java.util.Comparator.comparingInt(
                                f -> -f.getImportance()))
                        .limit(15)
                        .map(f -> "- " + f.getSubject() + "：" + f.getAssertion())
                        .collect(java.util.stream.Collectors.joining("\n"));
                if (keyFacts.isEmpty()) keyFacts = "（无）";
            }
            var mm = memoryManager;
            if (mm == null) return;
            // NCS-116: 专用 book-snapshot 模板（旧路径复用纯文本摘要模板，JSON 期望靠运气）
            String content = mm.draftSnapshot(previous, volumeSummary, keyFacts);
            String json = extractSnapshotJson(content);
            if (json == null) {
                EventLog.emit("BOOK_SNAPSHOT_FAILED", Map.of(
                        "projectId", projectId, "chapterIndex", toChapter,
                        "reason", content == null || content.isBlank() ? "blank"
                                : EventLog.truncate(content, 120)));
                return;
            }
            p.setBookSnapshot(json);
            repository.saveProject(p);
            log.info("全书快照已更新(ch{}): {}",
                    toChapter, EventLog.truncate(json, 120));
        } catch (Exception e) {
            log.warn("全书快照更新失败(保留旧版): {}", e.getMessage());
        }
    }

    private void updateBookSummary(String projectId, int fromChapter, int toChapter) {
        try {
            var project = repository.findProject(projectId).orElse(null);
            if (project == null) return;
            String prevBookSummary = project.getBookSummary();
            // 取本卷摘要
            var volSummary = repository.findSummariesByProject(projectId).stream()
                    .filter(s -> s.getFromChapter() == fromChapter && s.getToChapter() == toChapter)
                    .map(s -> s.getContent()).findFirst().orElse("");
            if (volSummary.isBlank()) return;

            String input = (prevBookSummary != null && !prevBookSummary.isBlank()
                    ? "当前全书摘要：\n" + prevBookSummary + "\n\n" : "")
                    + "新完成的卷（第" + fromChapter + "-" + toChapter + "章）摘要：\n" + volSummary;
            // C2: 用 memoryManager 压缩，但删除产生的临时 Summary 记录（只取内容存入 project.book_summary）
            var tempSummary = memoryManager.summarize(projectId, 1, toChapter,
                    "请将以下内容压缩为不超过3句话的全书整体梗概（包含主角、核心冲突、当前进展）：\n" + input);
            repository.deleteSummary(tempSummary.getId()); // 删除临时摘要，避免与卷级摘要重复
            project.setBookSummary(tempSummary.getContent());
            repository.saveProject(project);
            log.info("全书摘要已更新(ch{}): {}", toChapter,
                    tempSummary.getContent().length() > 60
                            ? tempSummary.getContent().substring(0, 60) + "..." : tempSummary.getContent());
        } catch (Exception e) {
            log.warn("更新全书摘要失败: {}", e.getMessage());
        }
    }

    private void startNextVolumeOrFinish(String projectId) {
        Project p = loadProject(projectId);
        Outline outline = repository.findOutlineByProject(projectId).orElse(null);
        if (outline != null && hasNextChapter(outline, p.getCurrentChapterIndex())) {
            // NCS-020/J08: 未确认的卷末回读阻断下一卷自动续写（显式确认后继续）
            if (volumeReadbackService != null && volumeReadbackService.hasPending(projectId)) {
                EventLog.emit("VOLUME_READBACK_PENDING", Map.of(
                        "projectId", projectId,
                        "currentChapter", p.getCurrentChapterIndex()));
                log.info("卷末回读待确认，暂停下一卷自动续写(项目{}, 章{})", projectId, p.getCurrentChapterIndex());
                return; // 保持 VOLUME_DONE
            }
            p.setState(NovelState.WRITING);
        } else {
            // mc-p3-02: 统一走门禁——不能绕过直接置 FINAL
            boolean passed = runFinalAuditGate(projectId, p);
            p.setState(passed ? NovelState.FINAL : NovelState.NEEDS_REVISION);
            log.info("卷末成书门禁: {} → {}", passed ? "PASS" : "FAIL", passed ? "FINAL" : "NEEDS_REVISION");
        }
        repository.saveProject(p);
    }

    /**
     * p10-w3-01: 总纲中段重规划——每完成 N 卷基于已写真典事实 + 已写章节摘要重规划剩余未写卷。
     * 实际剧情一旦偏离原总纲，重规划让后续卷的大纲随剧情演进调整，而非机械按原总纲生成。
     */
    private void replanRemainingOutline(String projectId, int currentChapter) {
        try {
            var outlineOpt = repository.findOutlineByProject(projectId);
            if (outlineOpt.isEmpty()) return;
            var outline = outlineOpt.get();
            String premise = outline.getPremise();
            if (premise == null) premise = "";

            // 收集已写卷摘要（最近 3 卷，每卷截 200 字，加上 bible facts）
            StringBuilder writtenSummary = new StringBuilder("【已写剧情摘要】\n");
            var volumeSums = repository.findSummariesByProject(projectId).stream()
                    .filter(s -> s.getToChapter() - s.getFromChapter() + 1 > 3)
                    .sorted((a, b) -> Integer.compare(b.getToChapter(), a.getToChapter()))
                    .limit(3)
                    .sorted(java.util.Comparator.comparingInt(s -> s.getFromChapter()))
                    .toList();
            for (var s : volumeSums) {
                String c = s.getContent();
                if (c != null) {
                    writtenSummary.append("卷(ch").append(s.getFromChapter()).append("-")
                            .append(s.getToChapter()).append("): ")
                            .append(c.length() > 200 ? c.substring(0, 200) : c).append("\n");
                }
            }

            // 收集真典事实（importance≥3 的 ACTIVE 事实）
            if (storyCanon != null) {
                var facts = storyCanon.recall(projectId);
                var importantFacts = facts.stream()
                        .filter(f -> f.getImportance() >= 3).limit(15).toList();
                if (!importantFacts.isEmpty()) {
                    writtenSummary.append("【已确立的关键事实（后续章节必须遵守）】\n");
                    for (var f : importantFacts) {
                        writtenSummary.append("■ ").append(f.getSubject())
                                .append("：").append(f.getAssertion()).append("\n");
                    }
                }
            }

            // 收集未回收 mustResolve 伏笔（约束不可丢弃）
            var unresolvedForeshadowings = repository.findUnresolvedForeshadowings(projectId).stream()
                    .filter(com.writing.novel.domain.Foreshadowing::isMustResolve)
                    .limit(10).toList();
            if (!unresolvedForeshadowings.isEmpty()) {
                writtenSummary.append("【未回收关键伏笔（重规划时不可丢弃）】\n");
                for (var f : unresolvedForeshadowings) {
                    writtenSummary.append("■ ").append(f.getDescription()).append("\n");
                }
            }

            // NCS-021: 已确认回读结论注入再规划输入（按正文更新计划的语义）
            if (volumeReadbackService != null) {
                String readbackContext = volumeReadbackService.buildReadbackContext(projectId);
                if (!readbackContext.isBlank()) {
                    writtenSummary.append("\n").append(readbackContext);
                }
            }

            if (plotter != null) {
                // NCS-077（听雪刀 P0）：中段重规划必须尊重目标章数——已写满目标即不再生成后续卷
                // （卷卷相生根因：卷5 尾声回收卷完成即自动生成卷6=第19-26章，锁死在检查点）
                // 无偏好记录的旧项目按领域默认目标（WritingPreferences 默认 24）约束
                int targetChapters = repository.findWritingPreferences(projectId)
                        .map(com.writing.novel.domain.WritingPreferences::getTargetChapters)
                        .orElse(24);
                int nextStart = currentChapter + 1;
                if (nextStart > targetChapters) {
                    log.info("总纲中段重规划跳过(项目{}): 已写{}章 >= 目标{}章，不生成后续卷",
                            projectId, currentChapter, targetChapters);
                    EventLog.emit("REPLAN_SKIPPED_AT_TARGET", Map.of(
                            "projectId", projectId,
                            "currentChapter", currentChapter,
                            "targetChapters", targetChapters));
                    return;
                }
                int nextEnd = Math.min(nextStart + 7, targetChapters);
                var replanGenre = repository.findProject(projectId)
                        .map(Project::getGenre).orElse(null);
                String generated = plotter.planVolume(
                        outline.getPremise(), writtenSummary.toString(),
                        "后续卷", "根据已写剧情重规划未写卷",
                        nextStart, nextEnd, "", "", replanGenre, projectId);
                Outline scratch = new Outline(projectId, outline.getPremise());
                int nextVol = outline.getVolumes().stream().mapToInt(Volume::getVolumeIndex).max().orElse(0) + 1;
                // p32-03: 用与 planVolume 请求一致的区间校验解析结果
                int parsed = parseAndAppendVolume(scratch, generated, projectId, nextVol, nextStart, nextEnd);
                // NCS-077: 解析 0 章（格式漂移/截断）时保留现有未写卷——空替换会把原计划吞掉
                if (parsed > 0) {
                    new com.writing.novel.service.OutlineReplanner()
                            .replaceUnwritten(outline, currentChapter, scratch.getVolumes());
                }
            }
            repository.saveOutline(outline);
            if (contextAssembler != null) contextAssembler.invalidateProject(projectId);
            log.info("总纲中段重规划完成(项目{}, 当前章{}): 未写卷已替换", projectId, currentChapter);
        } catch (Exception e) {
            log.warn("总纲中段重规划失败(项目{}): {}", projectId, e.getMessage());
        }
    }

    /**
     * mc-p1-02/mc-p2-01: 重写章节后失效覆盖该章的摘要。
     * 所有覆盖该章的摘要先置 dirty（组装时被 ContextAssembler 过滤，不注入旧内容）；
     * 细粒度摘要（span≤3）立即重算并清 dirty；卷级摘要（span>3）保持 dirty，
     * 待卷末/FINAL 门禁重算后自动清 dirty。
     */
    private void invalidateSummariesForChapter(String projectId, int chapterIndex) {
        markSummariesDirtyForChapter(projectId, chapterIndex);
        recomputeSummariesForChapter(projectId, chapterIndex);
    }

    private void markSummariesDirtyForChapter(String projectId, int chapterIndex) {
        var summaries = repository.findSummariesByProject(projectId);
        for (var s : summaries) {
            if (chapterIndex >= s.getFromChapter() && chapterIndex <= s.getToChapter()) {
                s.setDirty(true);
                repository.saveSummary(s);
            }
        }
    }

    private void recomputeSummariesForChapter(String projectId, int chapterIndex) {
        var summaries = repository.findSummariesByProject(projectId);
        for (var s : summaries) {
            if (s.isDirty() && chapterIndex >= s.getFromChapter() && chapterIndex <= s.getToChapter()) {
                int span = s.getToChapter() - s.getFromChapter() + 1;
                if (span <= 3) {
                    // 细粒度摘要——立即重算（重算后 dirty 清 false）
                    log.info("重算覆盖第{}章的摘要(范围{}-{})", chapterIndex, s.getFromChapter(), s.getToChapter());
                    generateSummary(projectId, s.getFromChapter(), s.getToChapter());
                } else {
                    // p10-w1-02: 卷级摘要也立即重算（原地更新、不清细粒度），
                    // 修复"dirty 卷摘要永不重算 → 该卷剧情在后续章节上下文静默消失"
                    recomputeExistingSummary(projectId, s);
                }
            }
        }
    }

    /** Passage summaries compute outside the mutation lock and persist only if their source is current. */
    private void recomputeSummariesForChapter(
            String projectId, int chapterIndex,
            com.writing.novel.service.ChapterRevisionService.DerivedWriteBoundary boundary) {
        if (memoryManager == null) return;
        for (var existing : repository.findSummariesByProject(projectId)) {
            if (!existing.isDirty() || !existing.covers(chapterIndex)) continue;
            StringBuilder chapters = new StringBuilder();
            for (int i = existing.getFromChapter(); i <= existing.getToChapter(); i++) {
                repository.findChapterByIndex(projectId, i)
                        .ifPresent(c -> chapters.append(c.getContent()).append("\n"));
            }
            if (chapters.isEmpty()) continue;

            Summary fresh;
            try {
                fresh = memoryManager.draftSummary(projectId, existing.getFromChapter(),
                        existing.getToChapter(), chapters.toString());
            } catch (Exception e) {
                // p32-13: 异步派生失败不再静默——保持跳过语义但发出可观测事件
                log.warn("异步摘要重算失败(项目{}, 章{}-{}): {}", projectId,
                        existing.getFromChapter(), existing.getToChapter(), e.getMessage());
                EventLog.emit("CHAPTER_REFRESH_FAILED", java.util.Map.of(
                        "projectId", projectId,
                        "chapterIndex", chapterIndex,
                        "async", true,
                        "stage", "summary_recompute",
                        "errorClass", e.getClass().getSimpleName(),
                        "message", String.valueOf(e.getMessage())));
                continue;
            }
            boundary.apply(() -> {
                int sourceVersion = repository.findChapterByIndex(projectId, existing.getToChapter())
                        .map(Chapter::getVersion).orElse(0);
                int span = existing.getToChapter() - existing.getFromChapter() + 1;
                if (span <= 3) {
                    fresh.setSourceChapterVersion(sourceVersion);
                    fresh.setDirty(false);
                    repository.saveSummary(fresh);
                    removeSupersededSummaries(projectId, existing.getFromChapter(),
                            existing.getToChapter(), fresh.getId());
                } else {
                    existing.setContent(fresh.getContent());
                    existing.setSourceChapterVersion(sourceVersion);
                    existing.setDirty(false);
                    repository.saveSummary(existing);
                }
                return true;
            }, false);
        }
    }

    /**
     * mc-p3-02: 成书质量门禁——FINAL 前的全局质量检查。
     * 硬性门禁（不通过→NEEDS_REVISION）：
     * - mustResolve 伏笔未回收数 > 3
     * - 未解决 HIGH 级 ContinuityIssue > 10
     * 软性门禁（只警告不阻塞）：
     * - 总字数 < targetChapters * 2000（篇幅不足）
     */
    private boolean runFinalAuditGate(String projectId, Project p) {
        try {
            // NCS-007/R09: 必查项"尚未查清"（最新状态 UNKNOWN/INVALID）阻塞成书——
            // 未执行、解析失败、截断不能自动成为 PASS
            if (checkResultRepository != null
                    && checkResultRepository.unknownCount(projectId) > 0) {
                long unknown = checkResultRepository.unknownCount(projectId);
                log.warn("成书门禁 FAIL: {} 项必查检查尚未查清（UNKNOWN/INVALID）", unknown);
                EventLog.emit("FINAL_GATE", Map.of("passed", false, "reason", "unknown_mandatory_check", "count", unknown));
                return false;
            }
            // 硬性：mustResolve 伏笔
            var unresolved = repository.findUnresolvedForeshadowings(projectId);
            var criticalUnresolved = unresolved.stream()
                    .filter(Foreshadowing::isMustResolve).toList();
            if (criticalUnresolved.size() > 3) {
                log.warn("成书门禁 FAIL: {} 条 mustResolve 伏笔未回收", criticalUnresolved.size());
                EventLog.emit("FINAL_GATE", Map.of("passed", false, "reason", "mustResolve_foreshadowing", "count", criticalUnresolved.size()));
                return false;
            }

            // 硬性：未解决 HIGH issues——p16-01 只统计近 60 章窗口（历史 HIGH 由卷末老化归档，
            // 若问题仍在复发会产生新 issue 行落在窗口内；全量口径会让 400 章长跑永远到不了 FINAL）
            int recentWindowStart = p.getCurrentChapterIndex() - 60;
            long highIssues = repository.findUnresolvedIssues(projectId).stream()
                    .filter(i -> i.getSeverity() == ContinuityIssue.Severity.HIGH)
                    .filter(i -> i.getDetectedAtChapter() > recentWindowStart).count();
            if (highIssues > 10) {
                log.warn("成书门禁 FAIL: {} 条未解决 HIGH 级问题", highIssues);
                return false;
            }

            // 软性：篇幅
            int totalWords = repository.getTotalWordCount(projectId);
            int targetChapters = repository.findWritingPreferences(projectId)
                    .map(prefs -> prefs.getTargetChapters()).orElse(80);
            int minWords = targetChapters * 2000;
            if (totalWords < minWords) {
                log.warn("成书门禁 SOFT WARN: 总字数 {} < 目标 {}", totalWords, minWords);
                // 软性不阻塞
            }

            // p10-w3-09: 真典一致性——检查是否有过多 SUPERSEDED 事实（剧变频繁，质量警告）
            if (storyCanon != null) {
                var allFacts = storyCanon.findAll(projectId);
                long superseded = allFacts.stream()
                        .filter(f -> f.getStatus() == com.writing.novel.domain.StoryFact.Status.SUPERSEDED).count();
                if (superseded > 20) {
                    log.warn("成书门禁 SOFT WARN: 真典 {} 条事实被取代（剧情设定频繁变更，建议审查一致性）", superseded);
                }
            }

            // p10-w3-09: 成本回执
            if (costBudgetMonitor != null) {
                double totalCost = costBudgetMonitor.total(projectId);
                log.info("成书门禁成本回执: 项目{} 累计 LLM 成本约 {} 元", projectId, Math.round(totalCost * 100) / 100.0);
            }

            log.info("成书门禁 PASS: 伏笔={}, HIGH issues={}, 字数={}",
                    criticalUnresolved.size(), highIssues, totalWords);
            EventLog.emit("FINAL_GATE", Map.of("passed", true, "mustResolve", criticalUnresolved.size(), "highIssues", highIssues));
            return true;
        } catch (Exception e) {
            // A1: 门禁异常时 fail-closed——宁可误拦不可放行劣质书
            log.error("成书门禁异常，fail-closed 默认不通过: {}", e.getMessage(), e);
            return false;
        }
    }

    ChapterSynopsis findSynopsis(Outline outline, int chapterIndex) {
        for (Volume v : outline.getVolumes()) {
            for (ChapterSynopsis s : v.getSynopses()) {
                if (s.getChapterIndex() == chapterIndex) return s;
            }
        }
        return null;
    }

    private boolean hasNextChapter(Outline outline, int currentIndex) {
        return outline.getVolumes().stream()
                .flatMap(v -> v.getSynopses().stream())
                .anyMatch(s -> s.getChapterIndex() > currentIndex);
    }

    /**
     * p12-fix: 分层大纲延迟生成——尝试用 planVolume 生成下一卷的详细章节大纲。
     * @return true 如果成功扩展了大纲（新增了章节梗概）
     */
    private boolean tryExtendLayeredOutline(String projectId, Project p) {
        try {
            log.info("tryExtendLayeredOutline 触发: projectId={}, currentChapter={}", projectId, p.getCurrentChapterIndex());
            var prefs = repository.findWritingPreferences(projectId).orElse(null);
            if (prefs == null) {
                log.warn("tryExtendLayeredOutline: prefs 为 null");
                return false;
            }
            if (!prefs.isLayeredOutline()) {
                log.warn("tryExtendLayeredOutline: layeredOutline=false");
                return false;
            }

            int targetChapters = prefs.getTargetChapters();
            int currentChapter = p.getCurrentChapterIndex();
            if (currentChapter >= targetChapters) return false;

            // 从 outline.premise 中解析总纲，找到下一卷信息
            var outlineOpt = repository.findOutlineByProject(projectId);
            if (outlineOpt.isEmpty()) return false;
            String masterOutline = outlineOpt.get().getPremise();
            if (masterOutline == null || masterOutline.isBlank()) return false;

            // 计算下一卷的范围
            int nextStartChapter = currentChapter + 1;
            // mc-p3-01 修复: 每卷 8 章——单次 planVolume 输出控制在模型输出上限内
            // （原 max(15, target/…)=20 章 + 总纲注入导致 completion 触顶 8192 被截断 → 解析 0 章）
            int chaptersPerVolume = 8;
            int nextEndChapter = Math.min(nextStartChapter + chaptersPerVolume - 1, targetChapters);
            int volumeNumber = (nextStartChapter - 1) / chaptersPerVolume + 1;

            log.info("分层大纲延迟生成: 卷{}, 第{}-{}章", volumeNumber, nextStartChapter, nextEndChapter);

            // C1: previousSummary 聚合最近 3 个卷级摘要（不再只取最后一条记录）
            String previousSummary = "";
            var volumeSums = repository.findSummariesByProject(projectId).stream()
                    .filter(s -> s.getToChapter() - s.getFromChapter() + 1 > 3) // 卷级
                    .sorted((a, b) -> Integer.compare(b.getFromChapter(), a.getFromChapter()))
                    .limit(3)
                    .sorted(java.util.Comparator.comparingInt(s -> s.getFromChapter()))
                    .toList();
            if (!volumeSums.isEmpty()) {
                StringBuilder psum = new StringBuilder();
                for (var s : volumeSums) {
                    String c = s.getContent();
                    if (c != null) psum.append("卷(").append(s.getFromChapter()).append("-")
                            .append(s.getToChapter()).append("): ")
                            .append(c.length() > 100 ? c.substring(0, 100) + "..." : c).append("\n");
                }
                previousSummary = psum.toString();
            }

            // 获取已有角色列表
            String existingCharacters = repository.findCharactersByProject(projectId).stream()
                    .map(c -> c.getName() + "(" + c.getRole() + ")")
                    .reduce((a, b) -> a + ", " + b).orElse("无");

            // cn-02: 取主角名锚定卷大纲
            String protagonistName = repository.findCharactersByProject(projectId).stream()
                    .filter(c -> c.getRole() != null && c.getRole().contains("主角"))
                    .map(c -> c.getName()).findFirst().orElse("");

            // 调用 planVolume 生成下一卷（p17-03: 传入题材——卷大纲带题材规则，长篇不断链）
            // NCS-083（听雪刀 P1-3）：本卷包含全书末章时明确传入收束指令——
            // 压缩后旧总纲残留的多卷结构曾把末章规划成"新篇开头"
            boolean containsFinalChapter = nextEndChapter >= targetChapters;
            String volumeSummary = containsFinalChapter
                    ? "（延迟生成，根据总纲规划）本卷为全书最后阶段（至第" + nextEndChapter
                      + "章全书完结）：必须收束主线、集中回收未回收伏笔、走向终局；不得开启新篇、不得埋设新悬念"
                    : "（延迟生成，根据总纲规划）";
            String volumeText = plotter.planVolume(
                    masterOutline, previousSummary,
                    "卷" + volumeNumber, volumeSummary,
                    nextStartChapter, nextEndChapter, existingCharacters, protagonistName,
                    p.getGenre(), projectId);

            // 解析并追加到 outline
            Outline outline = outlineOpt.get();
            // p32-03: 用 planVolume 请求区间校验解析结果，越界章号拒绝入库
            int parsed = parseAndAppendVolume(outline, volumeText, projectId, volumeNumber,
                    nextStartChapter, nextEndChapter);
            if (parsed == 0) {
                // mc-p3-01: LLM 输出被截断/格式漂移导致解析 0 章时，用卷梗概占位生成该卷章节，
                // 保证写作循环不空转（宁可先写出来，再由大纲延迟扩展补齐细节）
                log.warn("分层大纲延迟生成: 卷{} 解析出0章（输出可能被截断），改用占位兜底生成 {} 章",
                        volumeNumber, nextEndChapter - nextStartChapter + 1);
                parsed = createPlaceholderVolume(outline, projectId, volumeNumber, nextEndChapter);
                if (parsed == 0) return false;
            }
            repository.saveOutline(outline);

            log.info("分层大纲延迟生成完成: 卷{}, 新增 {} 章梗概", volumeNumber, parsed);
            return true;
        } catch (Exception e) {
            log.warn("分层大纲延迟生成失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * mc-p3-01: 分层大纲占位兜底——LLM 卷大纲解析失败时，为卷 volumeNumber
     * 生成 startChapter..endChapter 的占位章节梗概（以卷梗概/总纲为主题），
     * 让写作循环得以继续。后续 tryExtendLayeredOutline 会持续扩展后续卷。
     * @return 生成的章节数
     */
    private int createPlaceholderVolume(Outline outline, String projectId, int volumeNumber, int endChapter) {
        var existing = outline.getVolumes();
        Volume volume = existing.isEmpty() || existing.get(existing.size() - 1).getVolumeIndex() != volumeNumber
                ? new Volume(projectId, volumeNumber, "卷" + volumeNumber, "（占位卷，待细化）")
                : existing.get(existing.size() - 1);
        if (!outline.getVolumes().contains(volume)) {
            outline.addVolume(volume);
        }
        int startChapter = (volumeNumber - 1) * 8 + 1;
        int created = 0;
        String premise = outline.getPremise() != null ? outline.getPremise() : "";
        String synopsis = (premise.length() > 80 ? premise.substring(0, 80) : premise) + "——本卷主线推进";
        for (int i = startChapter; i <= endChapter; i++) {
            final int chIndex = i;
            boolean exists = outline.getVolumes().stream()
                    .flatMap(v -> v.getSynopses().stream())
                    .anyMatch(s -> s.getChapterIndex() == chIndex);
            if (exists) continue;
            ChapterSynopsis syn = new ChapterSynopsis(projectId, i, "第" + i + "章", synopsis);
            syn.setBeats("开场→推进→冲突→收尾");
            volume.addSynopsis(syn);
            created++;
        }
        return created;
    }

    /**
     * p12-fix: 解析 planVolume 的输出并追加到已有 outline。
     * mc-p3-01: 正则改为行首锚定——避免与正文中的"预计第N章回收"等
     * 行内章号引用冲突（旧正则的 (?=第\d+章) lookahead 会被截断，
     * 导致 8 章大纲误解析为 5 章、章节错乱）。
     * p32-03（体验报告 #1/P1）: 启用请求区间 [startChapter, endChapter] 校验——
     * LLM 照抄总纲中其它卷的"章节范围"会产出 41-48 这类错误章号（首卷 0 正文时曾实测发生），
     * 区间外条目拒绝入库并发 PLAN_VOLUME_RANGE_REJECTED 事件，由调用方占位兜底。
     * @return 解析出的章节数（0 表示解析失败）
     */
    /** NCS-188: 项目节拍上限（无偏好=0 不限制） */
    private int maxBeatsLimit(String projectId) {
        return repository.findWritingPreferences(projectId)
                .map(com.writing.novel.domain.WritingPreferences::getMaxBeatsPerChapter)
                .orElse(0);
    }

    private int parseAndAppendVolume(Outline outline, String text, String projectId,
                                       int volumeNumber, int startChapter, int endChapter) {
        // 创建新卷
        Volume volume = new Volume(projectId, volumeNumber, "卷" + volumeNumber, "延迟生成");
        outline.addVolume(volume);

        // mc-p3-01: 行首锚定 + 兼容 markdown 加粗(**第N章 标题**)；
        // 标题非空、遇 ：/: / 换行即止；行内"预计第N章回收"不触发截断
        // p12-01: 同时剥离 markdown 标记（** 加粗 + # 标题）——行首锚定正则 ^第N章 不匹配 "## 第1章"，导致解析 0 章走占位卷（标题丢失/beats 缺失）
        String cleanText = text == null ? "" : text.replace("**", "").replace("#", "");
        java.util.regex.Pattern chapterPattern = java.util.regex.Pattern.compile(
                "(?m)^\\s*第(\\d+)章\\s*(.+?)(?:[：:]|\\n|$)(.*?)(?=^\\s*第\\d+章|\\z)",
                java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher m = chapterPattern.matcher(cleanText);
        int parsed = 0;
        List<Integer> rejectedChapters = new java.util.ArrayList<>();
        while (m.find()) {
            try {
                int chIdx = Integer.parseInt(m.group(1));
                // p32-03: 章号区间门——错误范围的计划绝不进入权威 outline
                if (chIdx < startChapter || chIdx > endChapter) {
                    rejectedChapters.add(chIdx);
                    continue;
                }
                String title = m.group(2).trim();
                String body = m.group(3).trim();
                // 去重：跳过已存在的章号
                boolean exists = outline.getVolumes().stream()
                        .flatMap(v -> v.getSynopses().stream())
                        .anyMatch(s -> s.getChapterIndex() == chIdx);
                if (exists) continue;
                ChapterSynopsis syn = new ChapterSynopsis(projectId, chIdx, title, body);
                // p10-w3-02: 解析结构化字段（cast/beats/climax/tension/foreshadowing），
                // 修复延迟生成的卷大纲丢字段致 40 章后 ClimaxDiversityTracker 等追踪器失明（GAP#1）
                // NCS-188: 按项目 maxBeatsPerChapter 做节拍算术校验（超限合并裁剪+事件化）
                if (plotter != null) {
                    plotter.parseChapterDetails(syn, body, maxBeatsLimit(projectId));
                } else {
                    syn.setBeats(body);
                }
                volume.addSynopsis(syn);
                parsed++;
            } catch (Exception ignored) {}
        }
        if (!rejectedChapters.isEmpty()) {
            // p32-03: 让作者/诊断链路看到被拒章号与请求区间——错误计划被阻止而非静默采纳
            EventLog.emit("PLAN_VOLUME_RANGE_REJECTED", java.util.Map.of(
                    "projectId", projectId,
                    "volumeNumber", volumeNumber,
                    "requestedStart", startChapter,
                    "requestedEnd", endChapter,
                    "rejectedChapters", rejectedChapters,
                    "acceptedChapters", parsed));
            log.warn("卷{}计划章号越界拒绝(请求区间{}-{}, 被拒{}): {}", volumeNumber,
                    startChapter, endChapter, rejectedChapters.size(), rejectedChapters);
        }
        log.info("解析追加卷{}: {} 章", volumeNumber, parsed);
        if (parsed > 0) {
            Project project = repository.findProject(projectId).orElse(null);
            if (project != null && project.getMode() == ProjectMode.INTERACTIVE) {
                project.setState(NovelState.PAUSED_AT_CHECKPOINT);
                repository.saveProject(project);
                repository.saveCheckpoint(new Checkpoint(projectId,
                        Checkpoint.Type.AFTER_VOLUME_OUTLINE, "卷" + volumeNumber + "大纲已生成，待审核"));
            }
        } else {
            // NCS-077: 解析 0 章不残留空卷——空卷+同号重复卷会触发大纲 PUT
            // 「卷号必须唯一/严格递增」400，挡死作者保存删改后的结构
            outline.getVolumes().remove(volume);
        }
        return parsed;
    }

    /**
     * p32-04（体验报告 #1/P1）: 章节生成时点的计划出处——卷号+章梗概内容指纹。
     * 作者修订计划后指纹变化，据此可判定"本章按哪份梗概写"；无匹配梗概返回 null。
     */
    private static String planRefFor(Outline outline, int chapterIndex) {
        if (outline == null) return null;
        for (Volume v : outline.getVolumes()) {
            for (ChapterSynopsis s : v.getSynopses()) {
                if (s.getChapterIndex() == chapterIndex) {
                    return "volume=" + v.getVolumeIndex() + ";synopsis="
                            + sha256Hex12(s.getTitle() + "\n" + s.getSynopsis() + "\n" + s.getWritingNotes());
                }
            }
        }
        return null;
    }

    private static String sha256Hex12(String input) {
        return com.writing.novel.util.ReviewFingerprint.sha256Hex12(input);
    }

    /**
     * p32-02（体验报告 #2/P1）: 后处理自动重写的版本门。
     * 仅当章节仍处 expectedVersion 时经统一修订边界落库（变更前快照+派生刷新，失败已在 p32-01 隔离）；
     * 处理期间作者保存了新稿时拒绝覆盖、发出 CHAPTER_AUTHOR_CONFLICT 事件并返回 false，
     * 调用方必须放弃新稿并重新加载章节，不得继续消费陈旧内存对象。
     */
    public boolean autoRewriteIfCurrent(String projectId, int chapterIndex, int expectedVersion,
                                        String title, String newContent, String source) {
        try {
            chapterRevisionService.editInOwnedWork(projectId, chapterIndex, expectedVersion,
                    title, newContent, source);
            return true;
        } catch (IllegalStateException conflict) {
            EventLog.emit("CHAPTER_AUTHOR_CONFLICT", java.util.Map.of(
                    "projectId", projectId,
                    "chapterIndex", chapterIndex,
                    "expectedVersion", expectedVersion,
                    "source", source,
                    "reason", String.valueOf(conflict.getMessage())));
            return false;
        }
    }

    /**
     * p7-1: 重写指定章节（不限项目状态，FINAL 也能用）。
     * @param instructions 可选修改意见（如"增加对话""减少打斗"）
     */
    public Chapter rewriteChapter(String projectId, int chapterIndex, String instructions) {
        // NCS-086（听雪刀 P2-4）：修订管线补项目上下文——LLM_CALL 事件与成本归集携带 projectId
        EventContext.setProjectId(projectId);
        try {
            return doRewriteChapter(projectId, chapterIndex, instructions);
        } finally {
            EventContext.clear();
        }
    }

    private Chapter doRewriteChapter(String projectId, int chapterIndex, String instructions) {
        Project p = loadProject(projectId);
        Outline outline = repository.findOutlineByProject(projectId).orElseThrow(
                () -> new IllegalStateException("无大纲"));

        ChapterSynopsis synopsis = findSynopsis(outline, chapterIndex);
        if (synopsis == null) throw new IllegalArgumentException("大纲中无此章节: " + chapterIndex);

        var existing = repository.findChapterByIndex(projectId, chapterIndex)
                .orElseThrow(() -> new IllegalArgumentException("章节不存在: " + chapterIndex));

        String correction = instructions != null && !instructions.isBlank()
                ? instructions : "请基于上下文重新生成更好的版本";

        String newContent = chapterWriter.rewriteForCorrection(
                projectId, chapterIndex, synopsis.getTitle(),
                synopsis.getSynopsis(), synopsis.getWritingNotes(), correction);

        log.info("重写结果(章{}): newContent={} 字", chapterIndex,
                newContent != null ? newContent.length() : "null");

        if (newContent != null && newContent.length() >= 2000) {
            existing = chapterRevisionService.editInOwnedWork(projectId, chapterIndex,
                    existing.getVersion(), existing.getTitle(), newContent, "REWRITE");

            // p16-05: 重写改变了正文——按章重建向量索引（清旧 chunk + 新正文入库）。
            // rewriteForCorrection 内部已重建过一次，此处以持久化后的正文重放，保证向量与 DB 一致。
            try { chapterWriter.reindexChapter(projectId, chapterIndex, existing.getContent()); } catch (Exception ignored) {}

            // mc-p1-02: 重写后失效覆盖该章的摘要（标记 dirty 供下次组装时重算）
            try {
                invalidateSummariesForChapter(projectId, chapterIndex);
            } catch (Exception ignored) {}
            if (contextAssembler != null) contextAssembler.invalidateProject(projectId);
            try { if (factHarvester != null) factHarvester.harvest(projectId, existing); } catch (Exception ignored) {}

            try { entityGraphService.extractFromChapter(projectId, existing); } catch (Exception ignored) {}
            try { foreshadowingExtractor.extract(projectId, existing); } catch (Exception ignored) {}

            // p10: 重写后也运行 Phase 10 检测链（场景分类+一致性守卫+一致性门禁+弧光更新）
            try {
                var sceneType = sceneClassifier.classify(newContent);
                diversityTracker.record("scene", chapterIndex, List.of(sceneType.name()));
                log.info("重写后场景分类(章{}): {}", chapterIndex, sceneType.getLabel());
            } catch (Exception ignored) {}
            try {
                if (consistencyGuard != null) {
                    var guardIssues = consistencyGuard.check(projectId, chapterIndex, newContent);
                    for (var issue : guardIssues) repository.saveContinuityIssue(issue);
                }
            } catch (Exception ignored) {}
            try {
                if (consistencyGate != null) {
                    var gateIssues = consistencyGate.check(projectId, chapterIndex, newContent, p.getGenre());
                    for (var issue : gateIssues) repository.saveContinuityIssue(issue);
                }
            } catch (Exception ignored) {}
            try {
                if (arcScheduler != null) {
                    arcScheduler.updateLastSeenChapters(projectId, chapterIndex, newContent);
                }
            } catch (Exception ignored) {}

            // p11-fix: 重写后也触发角色升级（把无档案实体升级为角色卡）
            try {
                var upgraded = characterProfileUpgrader.upgrade(projectId, chapterIndex);
                if (!upgraded.isEmpty()) {
                    log.info("重写触发角色升级(章{}): {}", chapterIndex, String.join("、", upgraded));
                }
            } catch (Exception e) {
                log.warn("重写触发角色升级失败(章{}): {}", chapterIndex, e.getMessage(), e);
            }

            // p11-fix: 重写后也触发关系演变追踪
            try {
                if (relationshipEvolutionTracker != null) {
                    var reviewSynopsis = findSynopsis(outline, chapterIndex);
                    String castStr = reviewSynopsis != null ? reviewSynopsis.getCast() : "";
                    int changes = relationshipEvolutionTracker.detectAndApply(
                            projectId, chapterIndex, newContent, castStr);
                    if (changes > 0) {
                        log.info("重写触发关系演变(章{}): {} 条变化", chapterIndex, changes);
                    }
                }
            } catch (com.writing.novel.agent.util.PostProcessStageFailure e) {
                // NCS-119: 失败可见（不再静默 ignored）
                EventLog.emit("POST_PROCESS_STAGE_UNKNOWN", Map.of(
                        "projectId", projectId, "chapterIndex", chapterIndex,
                        "stage", "RELATIONSHIP_EVOLUTION", "detail", e.stage(),
                        "reason", EventLog.truncate(e.getMessage(), 200)));
                log.warn("重写触发关系演变失败(章{}): {}", chapterIndex, e.getMessage());
            }

            log.info("章节重写成功(章{}): {} 字", chapterIndex, newContent.length());
        }
        return existing;
    }

    /**
     * p10.1-e1: 伏笔重要性回溯审计——对旧伏笔做批量重要性重评估。
     * 解决 Phase 10 之前提取的伏笔 priority 全为默认 MEDIUM 的问题。
     */
    /**
     * p11-G1: 段落级编辑——只改选中段落，保留前后不变。
     * @param paragraphIndex 段落号（0-based，按 \n 分割）
     * @param selectedText 选中文本（定位验证）
     * @param instruction 编辑指令
     * @param mode REWRITE/EXPAND/INSERT
     */
    public Chapter editPassage(String projectId, int chapterIndex, int paragraphIndex,
                                String selectedText, String instruction, String mode) {
        // NCS-086: 段落编辑同样携带项目上下文（与 rewriteChapter 对齐）
        EventContext.setProjectId(projectId);
        try {
            return doEditPassage(projectId, chapterIndex, paragraphIndex, selectedText, instruction, mode);
        } finally {
            EventContext.clear();
        }
    }

    private Chapter doEditPassage(String projectId, int chapterIndex, int paragraphIndex,
                                  String selectedText, String instruction, String mode) {
        Chapter chapter = repository.findChapterByIndex(projectId, chapterIndex)
                .orElseThrow(() -> new IllegalArgumentException("章节不存在: " + chapterIndex));
        String content = chapter.getContent();
        if (content == null || content.isBlank()) return chapter;

        String[] paragraphs = content.split("\n", -1);
        int resolvedIndex = com.writing.novel.service.PassageLocator.resolve(
                content, paragraphIndex, selectedText);
        String targetParagraph = paragraphs[resolvedIndex];

        String contextBefore = extractContext(paragraphs, resolvedIndex, -1, 2);
        String contextAfter = extractContext(paragraphs, resolvedIndex, 1, 2);

        // 调用 PassageEditor
        // NCS-127: 段落编辑携带全书风格约束（与写作路径同标准）
        String editedPassage = passageEditor.editPassage(
                projectId, targetParagraph, contextBefore, contextAfter, instruction, mode);
        if (editedPassage == null || editedPassage.isBlank()) {
            log.warn("段落编辑失败(章{}段{}): LLM返回空", chapterIndex, paragraphIndex);
            return chapter;
        }

        // 拼接：前段不变 + 新段落 + 后段不变
        StringBuilder newContent = new StringBuilder();
        for (int i = 0; i < paragraphs.length; i++) {
            if (i == resolvedIndex) {
                if ("INSERT".equalsIgnoreCase(mode)) {
                    newContent.append(paragraphs[i]).append("\n").append(editedPassage);
                } else {
                    newContent.append(editedPassage);
                }
            } else {
                newContent.append(paragraphs[i]);
            }
            if (i < paragraphs.length - 1) newContent.append("\n");
        }

        // 长度保护：改写后不能比原来短太多（≥80%）
        if (!"INSERT".equalsIgnoreCase(mode) && newContent.length() < content.length() * 0.8) {
            log.warn("段落编辑后总字数过短({}→{}),保留原版", content.length(), newContent.length());
            return chapter;
        }

        chapter = chapterRevisionService.editInOwnedWork(projectId, chapterIndex,
                chapter.getVersion(), chapter.getTitle(), newContent.toString(), "PASSAGE_EDIT");
        log.info("段落编辑完成(章{}段{}): {}→{}字", chapterIndex, resolvedIndex,
                content.length(), newContent.length());
        refreshChapterAfterRevisionAsync(projectId, chapter);

        return chapter;
    }

    /** Direct edits/restores do not return until their derived state matches the saved chapter. */
    public void refreshChapterAfterRevision(String projectId, Chapter chapter) {
        int chapterIndex = chapter.getChapterIndex();
        com.writing.novel.service.ChapterRevisionService.withChapterRevisionLock(
                projectId, chapterIndex, () -> refreshCurrentChapterDerivedState(projectId, chapter));
    }

    /**
     * Passage editing keeps its established fast response while stale workers are version-gated.
     * p32-13（UI 验证）: REST 修订路径（编辑/回退/Agent 应用）同样改用本异步刷新——
     * 同步派生链（摘要重算等 LLM 调用）曾把响应与工作所有权阻塞十几分钟，
     * UI 呈现为"等待内容处理完成"的假死锁；数据虽已落库但作者无法继续操作。
     * 陈旧 worker 由 DerivedWriteBoundary 版本门丢弃，最终一致。
     */
    public void refreshChapterAfterRevisionAsync(String projectId, Chapter chapter) {
        int chapterIndex = chapter.getChapterIndex();
        com.writing.novel.service.ChapterRevisionService.withChapterRevisionLock(projectId, chapterIndex, () -> {
            if (!isCurrentChapterVersion(projectId, chapter)) return;
            reindexAndInvalidateChapter(projectId, chapter);
            markSummariesDirtyForChapter(projectId, chapterIndex);
        });
        var boundary = new com.writing.novel.service.ChapterRevisionService.DerivedWriteBoundary(
                repository, projectId, chapterIndex, chapter.getVersion());
        POST_PROCESS.execute(() -> {
            // NCS-086: 异步派生线程同样设置项目上下文（LLM 归因不随线程切换丢失）
            EventContext.setProjectId(projectId);
            try {
                recomputeSummariesForChapter(projectId, chapterIndex, boundary);
                refreshChapterEntitiesAndFacts(projectId, chapter, boundary);
                // p37-16: 收割/风格改写使版本提升后，对最终稿重新核对作者要求
                captureDirectiveEvidenceAfterGeneration(projectId, chapterIndex);
            } catch (Exception e) {
                log.warn("异步派生刷新失败(项目{}, 章{}): {}", projectId, chapterIndex, e.getMessage());
                EventLog.emit("CHAPTER_REFRESH_FAILED", java.util.Map.of(
                        "projectId", projectId,
                        "chapterIndex", chapterIndex,
                        "version", chapter.getVersion(),
                        "async", true,
                        "errorClass", e.getClass().getSimpleName(),
                        "message", String.valueOf(e.getMessage())));
            } finally {
                EventContext.clear();
            }
        });
    }

    private void refreshCurrentChapterDerivedState(String projectId, Chapter chapter) {
        if (!isCurrentChapterVersion(projectId, chapter)) return;
        reindexAndInvalidateChapter(projectId, chapter);
        markSummariesDirtyForChapter(projectId, chapter.getChapterIndex());
        recomputeSummariesForChapter(projectId, chapter.getChapterIndex());
        refreshChapterEntitiesAndFacts(projectId, chapter);
    }

    private boolean isCurrentChapterVersion(String projectId, Chapter chapter) {
        return repository.findChapterByIndex(projectId, chapter.getChapterIndex())
                .map(current -> current.getVersion() == chapter.getVersion())
                .orElse(false);
    }

    private void reindexAndInvalidateChapter(String projectId, Chapter chapter) {
        if (chapterWriter != null) {
            try { chapterWriter.reindexChapter(projectId, chapter.getChapterIndex(), chapter.getContent()); }
            catch (Exception ignored) {}
        }
        if (contextAssembler != null) contextAssembler.invalidateProject(projectId);
    }

    private void refreshChapterEntitiesAndFacts(String projectId, Chapter chapter) {
        try {
            if (entityGraphService != null) entityGraphService.extractFromChapter(projectId, chapter);
        } catch (Exception ignored) {}
        try {
            if (foreshadowingExtractor != null) foreshadowingExtractor.extract(projectId, chapter);
        } catch (Exception ignored) {}
        try {
            if (factHarvester != null) factHarvester.harvest(projectId, chapter);
        } catch (Exception ignored) {}
    }

    private void refreshChapterEntitiesAndFacts(
            String projectId, Chapter chapter,
            com.writing.novel.service.ChapterRevisionService.DerivedWriteBoundary boundary) {
        try {
            if (entityGraphService != null) entityGraphService.extractFromChapter(projectId, chapter, boundary);
        } catch (Exception ignored) {}
        try {
            if (foreshadowingExtractor != null) foreshadowingExtractor.extract(projectId, chapter, boundary);
        } catch (Exception ignored) {}
        try {
            if (factHarvester != null) factHarvester.harvest(projectId, chapter, boundary);
        } catch (Exception ignored) {}
    }

    /** 提取上下文段落（向前/向后取 N 段） */
    private String extractContext(String[] paragraphs, int center, int direction, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= count; i++) {
            int idx = center + direction * i;
            if (idx >= 0 && idx < paragraphs.length) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(paragraphs[idx]);
            }
        }
        return sb.toString();
    }

    public int auditForeshadowingImportance(String projectId) {
        if (foreshadowingImportanceAuditor == null) return 0;
        try {
            int upgraded = foreshadowingImportanceAuditor.audit(projectId);
            log.info("伏笔重要性审计完成(项目{}): {} 条升级为 HIGH", projectId, upgraded);
            return upgraded;
        } catch (Exception e) {
            log.warn("伏笔重要性审计失败: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * p11-I2: 构建章节聚合质量报告。
     * 聚合读者评审 + ContinuityIssue + 场景类型 + 套路词统计。
     */
    /**
     * p38-11（审计 P1-4）：按当前稿重新检查——身份不符 409；检查结果绑定触发时稿号，
     * 检查期间正文变化则丢弃结果（过期产物不冒充新稿报告）。
     */
    public ChapterQualityReport recheckChapter(String projectId, int chapterIndex,
            com.writing.novel.domain.ChapterApproval.Identity expectedIdentity) {
        return recheckChapter(projectId, chapterIndex, expectedIdentity, List.of("continuity"));
    }

    /**
     * NCS-087：按检查器路由的重新检查——信封按 (chapter, checker) 存储，
     * 只重跑 continuity 覆盖不了 consistency-guard 的 UNKNOWN（第二轮旅程 A-2）。
     * correction-verification 不可独立重跑（复验依赖当时的原问题清单，无法重建），
     * 明示走担责豁免（NCS-088 check-waiver）。
     */
    public ChapterQualityReport recheckChapter(String projectId, int chapterIndex,
            com.writing.novel.domain.ChapterApproval.Identity expectedIdentity, List<String> checkers) {
        List<String> requested = checkers == null || checkers.isEmpty()
                ? List.of("continuity") : checkers;
        for (String checker : requested) {
            if (!List.of("continuity", "consistency-guard").contains(checker)) {
                if ("correction-verification".equals(checker)) {
                    throw new IllegalStateException("correction-verification 不可独立重跑"
                            + "（复验依赖当时的原问题清单，无法重建）——请使用担责豁免 check-waiver");
                }
                throw new IllegalArgumentException("不支持的重新检查类型: " + checker);
            }
        }
        Chapter chapter = repository.findChapterByIndex(projectId, chapterIndex)
                .orElseThrow(() -> new IllegalArgumentException("章节不存在: " + chapterIndex));
        var currentIdentity = com.writing.novel.domain.ChapterApproval.Identity.of(chapter);
        if (!currentIdentity.equals(expectedIdentity)) {
            throw new com.writing.novel.spi.NovelRepository.ReviewConflictException(
                    com.writing.novel.spi.NovelRepository.ReviewConflictException.Reason.IDENTITY_STALE,
                    "重新检查必须针对当前稿", currentIdentity, null);
        }
        List<ContinuityIssue> issues = new java.util.ArrayList<>();
        for (String checker : requested) {
            if ("continuity".equals(checker)) {
                // NCS-097: 重查与首查同判据——共用 CheckSettingsBuilder（人物卡/知识/伏笔），
                // 真典与封闭式核对段由检查器自身按写作同源口径注入
                var cast = CheckSettingsBuilder.castOf(repository, projectId, chapterIndex);
                String settings = CheckSettingsBuilder.build(repository, relevanceGateway(),
                        projectId, chapterIndex, cast);
                issues.addAll(continuityChecker.check(projectId, chapterIndex, chapter.getContent(),
                        settings));
            } else if ("consistency-guard".equals(checker)) {
                if (consistencyGuard == null) throw new IllegalStateException("ConsistencyGuard 未配置");
                issues.addAll(consistencyGuard.check(projectId, chapterIndex, chapter.getContent(), null));
            }
        }
        // 检查完成后重读：正文已变化则丢弃（不落库为新稿的问题）
        Chapter after = repository.findChapterByIndex(projectId, chapterIndex).orElse(null);
        if (after == null || !com.writing.novel.domain.ChapterApproval.Identity.of(after)
                .equals(currentIdentity)) {
            log.info("重新检查期间正文已变化(章{})，丢弃过期结果", chapterIndex);
            return buildChapterQualityReport(projectId, chapterIndex);
        }
        for (var issue : issues) {
            issue.setChapterVersion(currentIdentity.version());
            issue.setContentHash(currentIdentity.contentHash());
            repository.saveContinuityIssue(issue);
        }
        return buildChapterQualityReport(projectId, chapterIndex);
    }

    public ChapterQualityReport buildChapterQualityReport(String projectId, int chapterIndex) {
        var chapter = repository.findChapterByIndex(projectId, chapterIndex).orElse(null);
        if (chapter == null) {
            return new ChapterQualityReport(chapterIndex, "", 0);
        }
        ChapterQualityReport report = new ChapterQualityReport(
                chapterIndex, chapter.getTitle(), chapter.getWordCount());
        // p32-05: 报告绑定章节当前版本——绑定旧版本的条目在报告中标记过期
        report.setChapterVersion(chapter.getVersion());

        // NCS-104: 风格量化指标——确定性、零 LLM 成本，进质量报告与 golden 基线
        report.setStyleMetrics(com.writing.novel.service.StyleMetricsService.compute(chapter.getContent()));

        // NCS-095: 字数趋势——本章低于前 3 章均值 50% 时提示（只读，不阻断）；
        // 第二轮旅程实测后半部章节字数从 10k 量级滑落到 3-4k 且无任何提示
        List<Integer> previousWords = repository.findChaptersByProject(projectId).stream()
                .filter(c -> c.getChapterIndex() < chapterIndex && c.getChapterIndex() >= chapterIndex - 3)
                .map(Chapter::getWordCount)
                .filter(w -> w > 0)
                .toList();
        if (previousWords.size() >= 2) {
            int avg = (int) Math.round(previousWords.stream().mapToInt(Integer::intValue).average().orElse(0));
            report.setPreviousAvgWordCount(avg);
            report.setWordTrendWarning(avg > 0 && chapter.getWordCount() > 0
                    && chapter.getWordCount() < avg * 0.5);
        }

        // 读者评审
        repository.findChapterReview(projectId, chapterIndex)
                .ifPresent(r -> {
                    r.setStale(r.getChapterVersion() != null
                            && r.getChapterVersion() != chapter.getVersion());
                    report.setReview(r);
                    report.setReviewStale(r.isStale());
                    // p34-02（UI 验收 A3）：显式暴露报告有效性——旧稿报告不得冒充当前审查
                    report.setReportForVersion(r.getChapterVersion());
                    report.setReportCurrent(!r.isStale()
                            && r.getChapterVersion() != null);
                });

        // 该章的 ContinuityIssue
        var issues = repository.findUnresolvedIssues(projectId).stream()
                .filter(i -> i.getDetectedAtChapter() == chapterIndex)
                .peek(i -> i.setStale(i.getChapterVersion() != null
                        && i.getChapterVersion() != chapter.getVersion()))
                .toList();
        report.setIssues(issues);
        report.setStaleIssueCount(issues.stream().filter(
                com.writing.novel.domain.ContinuityIssue::isStale).count());
        report.setNeedsManualReview(chapter.getStatus() == Chapter.Status.NEEDS_MANUAL_REVIEW);
        report.setQualityRisk(issues.stream().anyMatch(i ->
                i.getDescription() != null && i.getDescription().startsWith("[quality_risk]"))
                || repository.findUnresolvedIssues(projectId).stream().anyMatch(i ->
                i.getDescription() != null && i.getDescription().startsWith("[quality_risk]")));

        // 套路词统计
        try {
            var counts = clicheDetector.countCliches(chapter.getContent());
            report.setClicheCount(counts.values().stream().mapToInt(Integer::intValue).sum());
            report.setTopCliches(counts.entrySet().stream()
                    .filter(e -> e.getValue() >= 2)
                    .map(e -> e.getKey() + "(" + e.getValue() + "次)")
                    .toList());
        } catch (Exception ignored) {}

        return report;
    }

    /** 从 ChapterSynopsis.climax 文本推断爽点类型（关键词匹配） */
    List<ClimaxType> inferClimaxTypes(ChapterSynopsis synopsis) {
        if (synopsis == null || synopsis.getClimax() == null) return List.of();
        String text = synopsis.getClimax().toLowerCase();
        List<ClimaxType> types = new java.util.ArrayList<>();
        if (text.contains("信息差") || text.contains("主角知道") || text.contains("暗中")) types.add(ClimaxType.INFO_GAP);
        if (text.contains("智斗") || text.contains("策略") || text.contains("计谋") || text.contains("反间")) types.add(ClimaxType.STRATEGIC);
        if (text.contains("情感") || text.contains("反转") || text.contains("敌变友") || text.contains("友变敌") || text.contains("真相")) types.add(ClimaxType.EMOTIONAL_REVERSAL);
        if (text.contains("规则") || text.contains("利用") || text.contains("漏洞") || text.contains("阵法")) types.add(ClimaxType.RULE_EXPLOIT);
        if (text.contains("反预期") || text.contains("颠覆") || text.contains("意外")) types.add(ClimaxType.ANTI_EXPECTATION);
        return types;
    }

    /** p17-02: 当前项目题材（反馈词题材化用） */
    private com.writing.novel.domain.GenreProfile currentGenre() {
        try {
            String pid = EventContext.projectId();
            if (pid == null) return null;
            return repository.findProject(pid).map(p -> p.getGenre()).orElse(null);
        } catch (Exception e) { return null; }
    }

    /** 从评分报告中提取低分维度的改进方向 */
    private String buildImprovementNotes(com.writing.novel.agent.QualityEvaluationAgent.EvaluationReport report) {
        if (report == null) return "";
        StringBuilder sb = new StringBuilder();
        if (report.consistency < 7) sb.append("一致性需改进；");
        // p17-02: 慢节奏题材按张力而非爽点给反馈，避免评审闭环把严肃题材推向爽文
        if (report.plot < 7) {
            boolean fast = java.util.Optional.ofNullable(currentGenre()).map(g -> g.isFastPaced()).orElse(true);
            sb.append(fast ? "情节爽点密度需提升；" : "情节冲突张力与推进感需加强；");
        }
        if (report.characters < 7) sb.append("人物塑造需深化；");
        if (report.style < 7) sb.append("文风需统一；");
        if (report.worldbuilding < 7) sb.append("世界观需更自洽；");
        if (report.dialogue < 7) sb.append("对话质量需提升（增加对话/区分度/引用）；");
        if (report.patternDiversity < 7) sb.append("叙事模式需更多样化（爽点轮换/场景结构变化）；");
        return sb.isEmpty() ? "" : sb.toString();
    }

    /** 检测是否为 fallback 大纲（Plotter 解析失败时生成的最小占位大纲） */
    private boolean isFallbackOutline(String projectId) {
        var outline = repository.findOutlineByProject(projectId);
        if (outline.isEmpty()) return false;
        var o = outline.get();
        // fallback 特征：0 卷或总章数 ≤ 3（最小大纲）或卷标题为"第一卷"（fallback 默认）
        int totalChapters = o.getVolumes().stream()
                .mapToInt(v -> v.getSynopses().size()).sum();
        if (o.getVolumes().isEmpty() || totalChapters <= 3) {
            // 检查是否看起来像 fallback（卷名为默认值、章节名为默认值）
            for (var v : o.getVolumes()) {
                if ("第一卷".equals(v.getTitle())) return true;
                for (var s : v.getSynopses()) {
                    if ("第一章 开端".equals(s.getTitle())) return true;
                }
            }
        }
        return false;
    }

    private Project loadProject(String projectId) {
        return repository.findProject(projectId)
                .orElseThrow(() -> new IllegalArgumentException("项目不存在: " + projectId));
    }
}
