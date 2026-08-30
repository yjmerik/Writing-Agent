package com.writing.novel.orchestrator;

import com.writing.novel.agent.*;
import com.writing.novel.domain.*;
import com.writing.novel.memory.ContextAssembler;
import com.writing.novel.memory.SummaryService;
import com.writing.novel.service.ClimaxDiversityTracker;
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
    private static final java.util.concurrent.ExecutorService POST_PROCESS =
            java.util.concurrent.Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "chapter-postprocess");
                t.setDaemon(true);
                return t;
            });

    private final NovelRepository repository;
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
    /** p10-w2-02: 故事真典仓库——null 时跳过播种/收割（向后兼容旧构造器/测试） */
    private com.writing.novel.persistence.sqlite.StoryFactRepository storyFactRepository;
    private com.writing.novel.memory.RelevanceGateway relevanceGateway; // p11-q0-01: 检索收窄门面（setter 注入）
    private com.writing.novel.service.RepairArbiter repairArbiter; // p11-q0-04: 闭环三分类仲裁（setter 注入）
    private com.writing.novel.service.ConceiveGate conceiveGate; // p11-q1-01: 构思期质量门禁（setter 注入）
    private com.writing.novel.service.PlanGate planGate; // p11-q1-04: 章节梗概预检（setter 注入）
    private com.writing.novel.service.QualityCouncil qualityCouncil; // p11-q2-03: 质检委员会合并重写（setter 注入）
    private com.writing.novel.service.Arbiter arbiter;
    /** p10-w2-03: 事实收割 Agent——null 时跳过（向后兼容） */
    private com.writing.novel.agent.FactHarvesterAgent factHarvester;
    /** p10-w3-03: 归档衰减器——null 时跳过（向后兼容） */
    private com.writing.novel.service.ArchiveCompactor archiveCompactor;
    /** p10-w3-09: 成本预算监控器——null 时跳过成本回执（向后兼容） */
    private com.writing.novel.telemetry.CostBudgetMonitor costBudgetMonitor;

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
                              com.writing.novel.service.ExpositionDetector expositionDetector) {
        this.repository = repository;
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
    }

    /** p10-w2-02: 注入故事真典仓库（@Bean 装配用）；不调则保持 null，播种/收割安全跳过 */
    public void setStoryFactRepository(com.writing.novel.persistence.sqlite.StoryFactRepository storyFactRepository) {
        this.storyFactRepository = storyFactRepository;
    }

    /** p11-q0-01: 注入检索收窄门面（@Bean 装配用）；不调则保持 null，collectSettingsForCheck 降级全量 */
    public void setRelevanceGateway(com.writing.novel.memory.RelevanceGateway relevanceGateway) {
        this.relevanceGateway = relevanceGateway;
    }

    /** p11-q0-04: 注入闭环三分类仲裁器（@Bean 装配用）；不调则保持 null，复检仍 HIGH 仅 log */
    public void setRepairArbiter(com.writing.novel.service.RepairArbiter repairArbiter) {
        this.repairArbiter = repairArbiter;
    }

    /** p11-q1-01: 注入构思期质量门禁（@Bean 装配用）；不调则保持 null，构思期跳过门禁 */
    public void setConceiveGate(com.writing.novel.service.ConceiveGate conceiveGate) {
        this.conceiveGate = conceiveGate;
    }

    /** p11-q1-04: 注入章节梗概预检（@Bean 装配用）；不调则保持 null，写正文前跳过预检 */
    public void setPlanGate(com.writing.novel.service.PlanGate planGate) {
        this.planGate = planGate;
    }

    /** p11-q2-03: 注入质检委员会（@Bean 装配用）；不调则保持 null，guard 重写不合并 */
    public void setQualityCouncil(com.writing.novel.service.QualityCouncil qualityCouncil) {
        this.qualityCouncil = qualityCouncil;
    }

    public void setArbiter(com.writing.novel.service.Arbiter arbiter) {
        this.arbiter = arbiter;
    }

    /** p10-w2-03: 注入事实收割 Agent（@Bean 装配用）；不调则保持 null，收割安全跳过 */
    public void setFactHarvester(com.writing.novel.agent.FactHarvesterAgent factHarvester) {
        this.factHarvester = factHarvester;
    }

    /** p10-w3-03: 注入归档衰减器（@Bean 装配用）；不调则卷末跳过归档 */
    public void setArchiveCompactor(com.writing.novel.service.ArchiveCompactor archiveCompactor) {
        this.archiveCompactor = archiveCompactor;
    }

    /** p10-w3-09: 注入成本预算监控器（@Bean 装配用）；不调则门禁跳过成本回执 */
    public void setCostBudgetMonitor(com.writing.novel.telemetry.CostBudgetMonitor costBudgetMonitor) {
        this.costBudgetMonitor = costBudgetMonitor;
    }

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
     * storyFactRepository==null（旧测试/未注入）时安全跳过。
     */
    private void seedStoryBible(String projectId) {
        if (storyFactRepository == null) return;
        try {
            var project = repository.findProject(projectId).orElse(null);
            if (project == null) return;
            int seeded = 0;

            // 1. 世界观条目 → WORLD_RULE（POWER_SYSTEM/RULE/FACTION/HISTORY/CUSTOM）/ OBJECT_STATE（ITEM）
            for (var entry : repository.findKnowledgeEntriesByProject(projectId)) {
                StoryFact.FactType type = switch (entry.getType() == null ? "" : entry.getType()) {
                    case "POWER_SYSTEM", "RULE", "FACTION", "HISTORY", "CUSTOM" -> StoryFact.FactType.WORLD_RULE;
                    case "ITEM" -> StoryFact.FactType.OBJECT_STATE;
                    default -> null;
                };
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
        boolean exists = storyFactRepository.findActiveBySubject(projectId, subject).stream()
                .anyMatch(f -> f.getFactType() == type);
        if (exists) return 0;
        StoryFact f = new StoryFact(projectId, type, subject, assertion, 1);
        f.setImportance(importance);
        storyFactRepository.save(f);
        return 1;
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

        // 大纲（幂等：已有有效大纲则跳过，fallback 大纲需重新生成）
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
            p.setState(NovelState.PAUSED_AT_CHECKPOINT);
            repository.saveCheckpoint(new Checkpoint(projectId,
                    Checkpoint.Type.AFTER_INITIAL_SETUP, "初始设定生成完成，待审核"));
        } else {
            p.setState(NovelState.PLANNING);
        }
        repository.saveProject(p);
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

    private void runPlanning(String projectId) {
        Project p = loadProject(projectId);
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
        if (existing.isPresent() && existing.get().getWordCount() >= idempotentMinLen) {
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

        // p10-a2: 角色弧光调度——注入配角出场建议
        // p18-03: 作者写前指令优先注入（【作者指令】段），再叠加弧光/桥段等自动 notes
        String enhancedNotes = prependAuthorInstruction(synopsis.getWritingNotes(), instruction);
        if (arcScheduler != null) {
            String arcSuggestion = arcScheduler.scheduleAppearance(projectId, chapterIndex);
            if (!arcSuggestion.isBlank()) {
                enhancedNotes = (enhancedNotes != null ? enhancedNotes + "\n" : "") + arcSuggestion;
            }
        }
        // p11.1-J2: 桥段去重约束
        if (plotMotifTracker != null) {
            String motifConstraint = plotMotifTracker.getRecentMotifConstraint(projectId, chapterIndex);
            if (!motifConstraint.isBlank()) {
                enhancedNotes = (enhancedNotes != null ? enhancedNotes + "\n" : "") + motifConstraint;
            }
        }

        // p11-q1-04: PlanGate 章节梗概预检——写正文前校验梗概质量，不过关标记 quality_risk
        if (planGate != null) {
            var synGate = planGate.checkSynopsis(synopsis);
            if (!synGate.pass()) {
                markQualityRisk(projectId, "PlanGate(章" + chapterIndex + ")", synGate.feedback());
            }
        }

        EventContext.setChapter(chapterIndex);
        long writeStart = System.currentTimeMillis();
        Chapter chapter = chapterWriter.write(projectId, chapterIndex,
                synopsis.getTitle(), synopsis.getSynopsis(), enhancedNotes);
        EventLog.emit("CHAPTER_WRITTEN", Map.of(
                "chapterIndex", chapterIndex,
                "wordCount", chapter.getWordCount(),
                "durationMs", System.currentTimeMillis() - writeStart));

        // 内容校验：不达标不推进索引（chapterWriter.write 已保证 ≥500 字或抛异常）
        p = loadProject(projectId); // 重新加载最新状态
        p.setCurrentChapterIndex(chapterIndex);
        repository.saveProject(p);

        // p10-w1-06: 正文已落库，进入后处理阶段——status=POST_PROCESSING 持久化
        // 后处理若中途失败，重启后可识别 POST_PROCESSING 章节（而非误判为完成）
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

        // 写完后做连续性检查（发现问题记录到 DB，不中断写作）
        // p10-w1-07: 返回是否已触发 continuity 重写——若已重写，reader review 不再重写（互斥，防成本翻倍）
        boolean rewrittenByContinuity = checkContinuityAfterWriting(projectId, chapterIndex, chapter, parseCast(synopsis.getCast()));

        // 图数据库：LLM 提取本章实体和关系
        try {
            entityGraphService.extractFromChapter(projectId, chapter);
        } catch (Exception e) {
            log.warn("实体提取失败(章{}): {}", chapterIndex, e.getMessage());
        }

        // p10-w2-03: 故事真典增量收割——从本章提取事实变更入典（长程记忆，替代级联摘要）
        try {
            if (factHarvester != null) {
                var harvestResult = factHarvester.harvest(projectId, chapter);
                if (harvestResult.total() > 0) {
                    log.info("真典收割(章{}): +{} 新增 / {} 刷新 / {} 取代",
                            chapterIndex, harvestResult.added(), harvestResult.validated(), harvestResult.superseded());
                }
            }
        } catch (Exception e) {
            log.warn("真典收割失败(章{}): {}", chapterIndex, e.getMessage());
        }

        // p8-2: 叙事线索提取与追踪
        try {
            if (narrativeThreadExtractor != null) {
                narrativeThreadExtractor.extractAndUpdate(projectId, chapterIndex, chapter.getContent());
            }
        } catch (Exception e) {
            log.warn("叙事线索提取失败(章{}): {}", chapterIndex, e.getMessage());
        }

        // p8-3: 读者检验 Agentic Loop——每章写后检验，低分自动重写（最多1次）
        // p9-1: 重写长度保护——拒绝比原版短70%的重写
        // p11-I1: 评价结果持久化
        if (readerReviewAgent != null && arbiter == null) {
            try {
                var review = readerReviewAgent.review(chapterIndex, chapter.getContent());
                log.info("读者检验(章{}): {}/10 — {}", chapterIndex, review.getScore(), review.getSummary());
                // p11-q3-01: 质量事件遥测——读者检验评分可观测
                EventLog.emit("QUALITY_REVIEW", Map.of("score", review.getScore(),
                        "passed", review.isPassed(), "summary", EventLog.truncate(review.getSummary(), 150)));
                // p10-w1-07: continuity 已重写则互斥跳过（单章最多 1 次重写，避免最坏情况成本翻倍）
                if (!rewrittenByContinuity && !review.isPassed()) {
                    log.info("章节评分 {} < {}，自动重写", review.getScore(), readerReviewAgent.getPassThreshold());
                    var reviewSynopsis = findSynopsis(outline, chapterIndex);
                    int originalLength = chapter.getContent().length();
                    String newContent = chapterWriter.rewriteForCorrection(
                            projectId, chapterIndex, chapter.getTitle(),
                            reviewSynopsis != null ? reviewSynopsis.getSynopsis() : "",
                            reviewSynopsis != null ? reviewSynopsis.getWritingNotes() : "",
                            "读者检验改进建议：" + review.getImprovementSuggestion());
                    // p9-1: 长度保护——重写不能比原版短太多（≥70%）
                    if (newContent != null && newContent.length() >= originalLength * 0.7) {
                        chapter.setContent(newContent);
                        repository.saveChapter(chapter);
                        log.info("章节重写完成(章{}): {}字→{}字", chapterIndex, originalLength, newContent.length());
                        var reReview = readerReviewAgent.review(chapterIndex, chapter.getContent());
                        log.info("重写后检验(章{}): {}/10", chapterIndex, reReview.getScore());
                        // p11-I1: 持久化重写后的最终评价
                        review = reReview;
                        // p11-q3-01: 质量事件遥测——重写决策可观测
                        EventLog.emit("REWRITE_TRIGGERED", Map.of("reason", "reader_review",
                                "afterScore", reReview.getScore()));
                    } else if (newContent != null) {
                        log.warn("重写内容过短({}字 < 原版{}字的70%)，保留原版", newContent.length(), originalLength);
                        // p16-05: 拒收重写——rewriteForCorrection 已把向量切到被拒版本，
                        // 用保留的原正文重放向量索引，恢复向量库与 DB 一致
                        try { chapterWriter.reindexChapter(projectId, chapterIndex, chapter.getContent()); }
                        catch (Exception re) { log.warn("拒收重写后向量重放失败(章{}): {}", chapterIndex, re.getMessage()); }
                    }
                }
                // p11-I1: 持久化章节评价报告
                try {
                    repository.saveChapterReview(ChapterReview.from(projectId, review));
            enrichChapterCheckpointPayload(projectId, review.getChapterIndex());
                } catch (Exception pe) {
                    log.warn("章节评价持久化失败(章{}): {}", chapterIndex, pe.getMessage());
                }
            } catch (Exception e) {
                log.warn("读者检验失败(章{}): {}", chapterIndex, e.getMessage());
            }
        }

        // p5-c2: 伏笔动态提取——从正文中识别新埋下的伏笔
        try {
            foreshadowingExtractor.extract(projectId, chapter);
        } catch (Exception e) {
            log.warn("伏笔提取失败(章{}): {}", chapterIndex, e.getMessage());
        }

        // 爽点多样性追踪：从章节概要的 climax 字段推断爽点类型
        try {
            var climaxTypes = inferClimaxTypes(synopsis);
            if (!climaxTypes.isEmpty()) {
                climaxDiversityTracker.record(chapterIndex, climaxTypes);
                // p10-b2: 激活 DiversityTracker 的 climax 维度（修复死代码）
                diversityTracker.record("climax", chapterIndex,
                        climaxTypes.stream().map(Enum::name).toList());
            }
        } catch (Exception e) {
            log.warn("爽点追踪失败(章{}): {}", chapterIndex, e.getMessage());
        }

        // p10-b2: 场景结构分类 + 多样性追踪
        try {
            var sceneType = sceneClassifier.classify(chapter.getContent());
            diversityTracker.record("scene", chapterIndex, List.of(sceneType.name()));
            log.info("场景分类(章{}): {}", chapterIndex, sceneType.getLabel());

            // 检测连续 3+ 章同一场景类型 → 结构套路化警告
            int consecutive = diversityTracker.getConsecutiveSceneCount();
            if (consecutive >= 3) {
                String recent = diversityTracker.getRecentSceneTypes(4);
                repository.saveContinuityIssue(new ContinuityIssue(
                        projectId, chapterIndex,
                        ContinuityIssue.Type.STRUCTURE,
                        ContinuityIssue.Severity.MEDIUM,
                        "连续 " + consecutive + " 章使用同一场景类型（" + sceneType.getLabel()
                                + "），存在结构套路化风险。最近场景：" + recent
                                + "。建议下一章切换到不同的场景类型。"));
                log.warn("结构套路化警告(章{}): 连续{}章{}", chapterIndex, consecutive, sceneType.getLabel());
            }
        } catch (Exception e) {
            log.warn("场景分类失败(章{}): {}", chapterIndex, e.getMessage());
        }

        // p10-w3-06: 持久化多样性追踪状态（重启后不丢失，修复纯内存 Map 跨进程清零）
        try {
            diversityTracker.persist(projectId, repository);
        } catch (Exception ignored) {}

        // p10-a2: 更新角色最近出场章节
        try {
            if (arcScheduler != null) {
                arcScheduler.updateLastSeenChapters(projectId, chapterIndex, chapter.getContent());
            }
        } catch (Exception e) {
            log.warn("角色出场更新失败(章{}): {}", chapterIndex, e.getMessage());
        }

        // p11-H3: 关系演变追踪——检测本章角色关系变化并更新图谱
        try {
            if (relationshipEvolutionTracker != null) {
                String castStr = synopsis != null ? synopsis.getCast() : "";
                int changes = relationshipEvolutionTracker.detectAndApply(
                        projectId, chapterIndex, chapter.getContent(), castStr);
                if (changes > 0) {
                    log.info("关系演变(章{}): {} 条变化", chapterIndex, changes);
                }
            }
        } catch (Exception e) {
            log.warn("关系演变追踪失败(章{}): {}", chapterIndex, e.getMessage());
        }

        // p11.1-J2: 桥段追踪——提取叙事桥段标签并检测重复
        try {
            if (plotMotifTracker != null) {
                var motifIssues = plotMotifTracker.trackAndDetect(projectId, chapterIndex, chapter.getContent());
                for (var issue : motifIssues) {
                    repository.saveContinuityIssue(issue);
                    log.warn("桥段重复(章{}): {}", chapterIndex, issue.getDescription());
                }
            }
        } catch (Exception e) {
            log.warn("桥段追踪失败(章{}): {}", chapterIndex, e.getMessage());
        }

        // 剧情停滞检测：基于张力曲线，连续低张力触发警告
        try {
            plotProgressionDetector.detectStall(projectId, chapterIndex)
                    .ifPresent(issue -> {
                        repository.saveContinuityIssue(issue);
                        log.warn("剧情停滞警告(章{}): {}", finalChapterIndex, issue.getDescription());
                    });
        } catch (Exception e) {
            log.warn("停滞检测失败(章{}): {}", chapterIndex, e.getMessage());
        }

        // 角色档案升级：每 5 章检测重复出现的配角并升级
        if (chapterIndex % 5 == 0) {
            try {
                characterProfileUpgrader.upgrade(projectId, chapterIndex);
            } catch (Exception e) {
                log.warn("角色升级失败(章{}): {}", chapterIndex, e.getMessage());
            }
        }

        // 角色弧线更新：每 10 章重新评估已建档角色的成长弧和关系
        if (chapterIndex % 10 == 0) {
            try {
                arcUpdater.updateArcs(projectId, chapterIndex);
            } catch (Exception e) {
                log.warn("弧线更新失败(章{}): {}", chapterIndex, e.getMessage());
            }
        }

        if (chapterIndex % 3 == 0) {
            generateSummary(projectId, chapterIndex - 2, chapterIndex);
        }

        // 卷结束时生成卷级全局摘要（递归摘要）
        checkAndGenerateVolumeSummary(projectId, outline, chapterIndex);

        // p12-K3: 向量库 flush 已在 ChapterWriterAgent.indexChapterInChunks 后完成

        // p10-w1-06: 全部后处理完成 → status=DONE（章节真正就绪）
        // p20-01: 仅从 POST_PROCESSING 推进到 DONE——applyRepairClassification 标记的
        // NEEDS_MANUAL_REVIEW 是人工复核裁决，不得被后处理完成覆盖
        if (chapter.getStatus() == com.writing.novel.domain.Chapter.Status.POST_PROCESSING) {
            chapter.setStatus(com.writing.novel.domain.Chapter.Status.DONE);
            repository.saveChapter(chapter);
        }
        return chapter;
    }

    /** 检查当前章是否是某卷最后一章，如果是则生成卷级摘要 */
    private void checkAndGenerateVolumeSummary(String projectId, Outline outline, int chapterIndex) {
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
        // 本卷埋下但仍未回收的
        var unresolved = plantedInVolume.stream()
                .filter(f -> !f.isResolved())
                .toList();

        if (!unresolved.isEmpty()) {
            StringBuilder desc = new StringBuilder();
            desc.append("卷").append(volumeIndex).append("有 ")
                .append(unresolved.size()).append(" 条未回收伏笔：\n");
            for (var f : unresolved) {
                desc.append("- ").append(f.getDescription())
                    .append("（埋于第").append(f.getPlantedAtChapter()).append("章）\n");
            }
            desc.append("建议在后续章节中回收这些伏笔。");
            // p9-2: 未回收 > 5 条时升级为 HIGH，触发重写路径
            var severity = unresolved.size() > 5
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
     * 写完一章后检查连续性。
     * 发现 HIGH 级矛盾时触发 1 次重写（把矛盾描述加入 prompt 要求修正）。
     * 每 5 章检查一次风格漂移。
     */
    private boolean checkContinuityAfterWriting(String projectId, int chapterIndex, Chapter chapter, List<String> cast) {
        boolean rewritten = false; // p10-w1-07: 标记是否触发重写（不提前 return，保持伏笔检测等后续流程）
        java.util.List<ContinuityIssue> continuityIssuesRef = java.util.List.of(); // p11-q2-03: 供 guard 合并重写引用
        try {
            String settings = collectSettingsForCheck(projectId, cast);
            var issues = continuityChecker.check(projectId, chapterIndex, chapter.getContent(), settings);
            continuityIssuesRef = issues;

            boolean hasHighIssue = issues.stream()
                    .anyMatch(i -> i.getSeverity() == ContinuityIssue.Severity.HIGH);
            if (arbiter == null && hasHighIssue && chapter.getContent().length() > 500) {
                String issueDesc = issues.stream()
                        .filter(i -> i.getSeverity() == ContinuityIssue.Severity.HIGH)
                        .map(ContinuityIssue::getDescription)
                        .reduce((a, b) -> a + "；" + b)
                        .orElse("");
                rewriteWithCorrection(projectId, chapterIndex, chapter, issueDesc, settings);
                // 重写后标记已处理的 HIGH issue 为 resolved（p3-01）
                issues.stream()
                        .filter(i -> i.getSeverity() == ContinuityIssue.Severity.HIGH)
                        .forEach(i -> {
                            i.resolve();
                            repository.saveContinuityIssue(i);
                        });
                rewritten = true; // p10-w1-07: continuity 已触发重写，告知调用方 reader review 互斥跳过
                // p11-q0-05: 重写后重跑 ContinuityChecker 复检（之前只标 resolved 不重跑，重写引入的新矛盾不可见）
                try {
                    var recheck = continuityChecker.check(projectId, chapterIndex,
                            chapter.getContent(), collectSettingsForCheck(projectId, cast));
                    for (var issue : recheck) {
                        if (issue.getSeverity() == ContinuityIssue.Severity.HIGH) {
                            log.warn("重写后新引入连续性 HIGH(章{}): {}", chapterIndex, issue.getDescription());
                            applyRepairClassification(projectId, chapterIndex, chapter, issue, true);
                        }
                    }
                } catch (Exception e) {
                    log.warn("连续性复检失败(章{}): {}", chapterIndex, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("连续性检查失败: {}", e.getMessage());
        }
        // p5-c1: 伏笔回收检测——本章是否回收了某个未回收伏笔
        try {
            checkForeshadowingResolution(projectId, chapterIndex, chapter);
        } catch (Exception e) {
            log.warn("伏笔回收检测失败(章{}): {}", chapterIndex, e.getMessage());
        }

        // p11.1-J3: 伏笔悬置过久告警
        try {
            int threshold = repository.findWritingPreferences(projectId)
                    .map(p -> p.getForeshadowSuspenseThreshold()).orElse(8);
            var stale = repository.findUnresolvedForeshadowings(projectId).stream()
                    .filter(f -> f.isMustResolve() || f.getPriority() == Foreshadowing.Priority.HIGH)
                    .filter(f -> f.isStale(chapterIndex, threshold))
                    .toList();
            for (var f : stale) {
                repository.saveContinuityIssue(new ContinuityIssue(
                        projectId, chapterIndex,
                        ContinuityIssue.Type.FORESHADOWING,
                        ContinuityIssue.Severity.MEDIUM,
                        "伏笔悬置过久：「" + f.getDescription().substring(0, Math.min(30, f.getDescription().length()))
                        + "...」已悬置" + f.suspenseChapters(chapterIndex) + "章（埋于第"
                        + f.getPlantedAtChapter() + "章），建议在近期章节回收"));
            }
            if (!stale.isEmpty()) {
                log.warn("伏笔悬置告警(章{}): {} 条悬置过久", chapterIndex, stale.size());
            }
        } catch (Exception e) {
            log.warn("伏笔悬置检测失败(章{}): {}", chapterIndex, e.getMessage());
        }

        // A3: 已删除冗余的风格检查块（原 p10-d1，只 log 不持久化）——保留下方 p3-02 块（含评分+持久化）

        // p10-a3+c2: 一致性守卫——能力越界 + 凭空证据检测
        try {
            if (consistencyGuard != null) {
                var guardIssues = consistencyGuard.check(projectId, chapterIndex, chapter.getContent(), cast);
                for (var issue : guardIssues) {
                    repository.saveContinuityIssue(issue);
                    log.warn("一致性守卫(章{}): {}", chapterIndex, issue.getDescription());
                }
                // p10-w3-08: ConsistencyGuard 的 HIGH 接入修复闭环（之前只 save 不修）
                // 在同一章未被 continuity 重写过时触发重写（rewritten 标记来自 continuity 检查）
                boolean hasHigh = guardIssues.stream().anyMatch(
                        i -> i.getSeverity() == ContinuityIssue.Severity.HIGH);
                if (arbiter == null && hasHigh && !rewritten && chapter.getContent().length() > 500) {
                    // p11-q2-03: 合并重写——用 QualityCouncil 汇总 continuity+guard 问题生成合并纠偏指令
                    // （解除"guard HIGH 时只喂 guard 问题"的分散模式，多维问题一次重写联合修正）
                    String desc;
                    if (qualityCouncil != null) {
                        var council = qualityCouncil.collect(continuityIssuesRef, guardIssues, 10, null, null);
                        desc = council.combinedFeedback().isBlank()
                                ? guardIssues.stream().filter(i -> i.getSeverity() == ContinuityIssue.Severity.HIGH)
                                        .map(ContinuityIssue::getDescription).reduce((a, b) -> a + "；" + b).orElse("")
                                : council.combinedFeedback();
                    } else {
                        desc = guardIssues.stream()
                                .filter(i -> i.getSeverity() == ContinuityIssue.Severity.HIGH)
                                .map(ContinuityIssue::getDescription)
                                .reduce((a, b) -> a + "；" + b).orElse("");
                    }
                    rewriteWithCorrection(projectId, chapterIndex, chapter, desc, "");
                    rewritten = true; // 标记已重写，reader review 互斥
                    // p10-w3-08: 重写后复检——若仍有 HIGH，标记 issue 但不再重写（最多 1 次）
                    try {
                        var recheck = consistencyGuard.check(projectId, chapterIndex, chapter.getContent(), cast);
                        for (var issue : recheck) {
                            repository.saveContinuityIssue(issue);
                                if (issue.getSeverity() == ContinuityIssue.Severity.HIGH) {
                                    log.warn("重写后仍存一致性守卫 HIGH(章{}): {}", chapterIndex, issue.getDescription());
                                    // p11-q0-04: 重写后仍存 → 三分类仲裁（激活 NEEDS_MANUAL_REVIEW）
                                    applyRepairClassification(projectId, chapterIndex, chapter, issue, true);
                                }
                        }
                    } catch (Exception e) {
                        log.warn("一致性守卫复检失败(章{}): {}", chapterIndex, e.getMessage());
                        EventLog.emit("CONSISTENCY_ISSUE", Map.of(
                                "severity", "DETECT_FAILED", "desc", EventLog.truncate(e.getMessage(), 150)));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("一致性守卫失败(章{}): {}", chapterIndex, e.getMessage());
        }

        if (arbiter != null) {
            rewritten = applyArbiterDecision(projectId, chapterIndex, chapter, cast,
                    continuityIssuesRef, rewritten);
        }

        // p10-d2: 一致性门禁——章号/时代用语检查（纯规则）
        try {
            if (consistencyGate != null) {
                var p = loadProject(projectId);
                var gateIssues = consistencyGate.check(projectId, chapterIndex,
                        chapter.getContent(), p.getGenre());
                for (var issue : gateIssues) {
                    repository.saveContinuityIssue(issue);
                    log.warn("一致性门禁(章{}): {}", chapterIndex, issue.getDescription());
                }
            }
        } catch (Exception e) {
            log.warn("一致性门禁失败(章{}): {}", chapterIndex, e.getMessage());
        }

        // p11.1-J4: 说明文密度检测——连续无对话段落
        try {
            if (expositionDetector != null) {
                expositionDetector.detect(projectId, chapterIndex, chapter.getContent())
                        .ifPresent(issue -> {
                            repository.saveContinuityIssue(issue);
                            log.warn("说明文密度(章{}): {}", chapterIndex, issue.getDescription());
                        });
            }
        } catch (Exception e) {
            log.warn("说明文密度检测失败(章{}): {}", chapterIndex, e.getMessage());
        }

        // 每 5 章检查风格漂移，解析评分并记录问题（p3-02）
        if (chapterIndex % 5 == 0) {
            try {
                var styleOpt = repository.findStyleProfileByProject(projectId);
                if (styleOpt.isPresent()) {
                    var style = styleOpt.get();
                    String baseline = "基调：" + (style.getTone() != null ? style.getTone() : "")
                            + "，叙事：" + (style.getVoice() != null ? style.getVoice() : "")
                            + "，注意事项：" + (style.getDosAndDonts() != null ? style.getDosAndDonts() : "");
                    String report = styleGuardian.checkConsistency(projectId, chapter.getContent(), baseline);
                    // 解析评分：格式 "风格一致性评分：XX/100"
                    if (report != null) {
                        var scoreMatch = java.util.regex.Pattern.compile("(\\d+)/100").matcher(report);
                        if (scoreMatch.find()) {
                            int score = Integer.parseInt(scoreMatch.group(1));
                            if (score < 60) {
                                // 风格评分低，记录为 ContinuityIssue
                                repository.saveContinuityIssue(new ContinuityIssue(
                                        projectId, chapterIndex,
                                        ContinuityIssue.Type.WORLDBUILDING,
                                        ContinuityIssue.Severity.MEDIUM,
                                        "风格漂移（评分" + score + "/100）：" + report));
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("风格检查失败: {}", e.getMessage());
            }
        }
        return rewritten;
    }

    /**
     * p9-2: 伏笔回收检测——用 LLM 判断本章是否回收了未回收伏笔。
     * 替代旧的关键词匹配检测（p5-c1，0%命中率）。
     */
    private void checkForeshadowingResolution(String projectId, int chapterIndex, Chapter chapter) {
        if (foreshadowingResolutionDetector == null) return;
        try {
            int resolved = foreshadowingResolutionDetector.detectAndResolve(
                    projectId, chapterIndex, chapter.getContent());
            if (resolved > 0) {
                log.info("伏笔回收(章{}): {} 条", chapterIndex, resolved);
            }
        } catch (Exception e) {
            log.warn("伏笔回收检测失败(章{}): {}", chapterIndex, e.getMessage());
        }
    }

    /** 带纠偏指令的重写：原地更新原 chapter（避免重复章节） */
    private void rewriteWithCorrection(String projectId, int chapterIndex, Chapter chapter,
                                        String issueDesc, String settings) {
        try {
            var outline = repository.findOutlineByProject(projectId).orElse(null);
            if (outline == null) return;
            var synopsis = findSynopsis(outline, chapterIndex);
            if (synopsis == null) return;

            String newContent = chapterWriter.rewriteForCorrection(
                    projectId, chapterIndex, synopsis.getTitle(),
                    synopsis.getSynopsis(), synopsis.getWritingNotes(), issueDesc);
            // 重写成功则用新内容原地更新（复用原 chapter 的 id，触发 ON CONFLICT DO UPDATE）
            if (newContent != null && newContent.length() >= 2000) {
                chapter.setContent(newContent);
                repository.saveChapter(chapter); // 用原 id 覆盖，不产生重复
            } else if (newContent != null) {
                // p16-05: 拒收重写——rewriteForCorrection 已把向量切到被拒版本，
                // 用保留的原正文重放向量索引，恢复向量库与 DB 一致
                try { chapterWriter.reindexChapter(projectId, chapterIndex, chapter.getContent()); }
                catch (Exception re) { log.warn("拒收重写后向量重放失败(章{}): {}", chapterIndex, re.getMessage()); }
            }
        } catch (Exception e) {
            log.warn("纠偏重写失败，保留原版本: {}", e.getMessage());
            EventLog.emit("CONSISTENCY_ISSUE", Map.of(
                    "severity", "DETECT_FAILED", "desc", EventLog.truncate(e.getMessage(), 150)));
        }
    }

    private boolean applyArbiterDecision(String projectId, int chapterIndex, Chapter chapter,
                                         List<String> cast,
                                         java.util.List<ContinuityIssue> continuityIssues,
                                         boolean alreadyRewritten) {
        java.util.List<ContinuityIssue> guardIssues = java.util.List.of();
        try {
            if (consistencyGuard != null) {
                guardIssues = consistencyGuard.check(projectId, chapterIndex, chapter.getContent(), cast);
            }
        } catch (Exception e) {
            log.warn("仲裁前守卫失败(章{}): {}", chapterIndex, e.getMessage());
            EventLog.emit("CONSISTENCY_ISSUE", Map.of(
                    "severity", "DETECT_FAILED", "desc", EventLog.truncate(e.getMessage(), 150)));
        }
        int readerOverall = 10;
        String suggestion = null;
        if (readerReviewAgent != null) {
            try {
                var review = readerReviewAgent.review(chapterIndex, chapter.getContent());
                readerOverall = review.getScore();
                suggestion = review.getImprovementSuggestion();
                repository.saveChapterReview(ChapterReview.from(projectId, review));
            enrichChapterCheckpointPayload(projectId, review.getChapterIndex());
            } catch (Exception e) {
                log.warn("仲裁前读者检验失败(章{}): {}", chapterIndex, e.getMessage());
            }
        }
        var council = (qualityCouncil != null ? qualityCouncil : new com.writing.novel.service.QualityCouncil())
                .collect(continuityIssues, guardIssues, readerOverall, suggestion, null);
        int budget = repository.findWritingPreferences(projectId)
                .map(com.writing.novel.domain.WritingPreferences::getRewriteBudget).orElse(1);
        var decision = arbiter.arbitrate(council, budget, alreadyRewritten);
        if (decision.action() == com.writing.novel.service.Arbiter.Action.REWRITE
                && chapter.getContent().length() > 500) {
            rewriteWithCorrection(projectId, chapterIndex, chapter, decision.correctionBrief(), "");
            alreadyRewritten = true;
            try {
                var recheckC = continuityChecker.check(projectId, chapterIndex, chapter.getContent(),
                        collectSettingsForCheck(projectId, cast));
                for (var issue : recheckC) {
                    if (issue.getSeverity() == ContinuityIssue.Severity.HIGH) {
                        applyRepairClassification(projectId, chapterIndex, chapter, issue, true);
                    }
                }
                if (consistencyGuard != null) {
                    var recheckG = consistencyGuard.check(projectId, chapterIndex, chapter.getContent(), cast);
                    for (var issue : recheckG) {
                        repository.saveContinuityIssue(issue);
                        if (issue.getSeverity() == ContinuityIssue.Severity.HIGH) {
                            applyRepairClassification(projectId, chapterIndex, chapter, issue, true);
                        }
                    }
                }
                if (readerReviewAgent != null) {
                    readerReviewAgent.review(chapterIndex, chapter.getContent());
                }
            } catch (Exception e) {
                log.warn("仲裁后复检失败(章{}): {}", chapterIndex, e.getMessage());
                EventLog.emit("CONSISTENCY_ISSUE", Map.of(
                        "severity", "DETECT_FAILED", "desc", EventLog.truncate(e.getMessage(), 150)));
            }
        } else if (decision.action() == com.writing.novel.service.Arbiter.Action.BLOCK) {
            continuityIssues.stream()
                    .filter(i -> i.getSeverity() == ContinuityIssue.Severity.HIGH)
                    .findFirst()
                    .ifPresent(i -> applyRepairClassification(projectId, chapterIndex, chapter, i, true));
        }
        return alreadyRewritten;
    }

    /**
     * p11-q0-04: 三分类仲裁——重写/真典supercede/早期根源标记 NEEDS_MANUAL_REVIEW。
     * 重写后仍存或跨章根源型 HIGH 矛盾，激活 NEEDS_MANUAL_REVIEW（此前枚举从未赋值）。
     */
    private void applyRepairClassification(String projectId, int chapterIndex, Chapter chapter,
                                            ContinuityIssue issue, boolean alreadyRewritten) {
        if (repairArbiter == null) return;
        var action = repairArbiter.classify(issue, alreadyRewritten);
        // p11-q3-01: 质量事件遥测——一致性问题 + 仲裁动作可观测
        EventLog.emit("CONSISTENCY_ISSUE", Map.of(
                "type", issue.getType().name(), "severity", issue.getSeverity().name(),
                "action", action.name(), "desc", EventLog.truncate(issue.getDescription(), 150)));
        switch (action) {
            case MANUAL_REVIEW -> {
                chapter.setStatus(Chapter.Status.NEEDS_MANUAL_REVIEW);
                repository.saveChapter(chapter);
                log.warn("章{} 标记 NEEDS_MANUAL_REVIEW（根源性矛盾待人工）：{}", chapterIndex, issue.getDescription());
            }
            case SUPERSEDE_CANON -> correctCanon(projectId, chapterIndex, issue);
            case REWRITE -> { /* 已重写，不再重写（最多 1 次） */ }
        }
    }

    /** p11-q0-04: 把矛盾纠正为真典 PLOT_COMMITMENT（待人工确认），让后续章节知晓该矛盾 */
    private void correctCanon(String projectId, int chapterIndex, ContinuityIssue issue) {
        if (storyFactRepository == null) return;
        try {
            storyFactRepository.save(new com.writing.novel.domain.StoryFact(projectId,
                    com.writing.novel.domain.StoryFact.FactType.PLOT_COMMITMENT,
                    "矛盾待确认(章" + chapterIndex + ")",
                    "【待人工确认】" + issue.getDescription(), chapterIndex));
        } catch (Exception e) {
            log.warn("真典纠正失败(章{}): {}", chapterIndex, e.getMessage());
        }
    }

    /**
     * p11-q1-03: 软门禁——门禁重试上限后仍不过，标记 quality_risk（记 ContinuityIssue，不阻塞推进）。
     * INTERACTIVE 模式下，doConceiving 末尾的 PAUSED_AT_CHECKPOINT 会让人工看到这些 quality_risk 并介入。
     */
    private void markQualityRisk(String projectId, String stage, String feedback) {
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

    /** 从大纲文本中抽取伏笔条目存入 Foreshadowing 表（p3-03） */    private void extractForeshadowingsFromOutline(String projectId) {
        try {
            // 已有伏笔则跳过（幂等）
            if (!repository.findForeshadowingsByProject(projectId).isEmpty()) return;
            var outlineOpt = repository.findOutlineByProject(projectId);
            if (outlineOpt.isEmpty()) return;
            String fullText = outlineOpt.get().getPremise();
            for (var v : outlineOpt.get().getVolumes()) {
                fullText += "\n" + v.getTitle() + " " + v.getSummary();
                for (var s : v.getSynopses()) {
                    fullText += "\n" + s.getSynopsis();
                }
            }
            // 用正则找含"伏笔/暗示/暗藏/埋下/秘密/真相"的段落
            var pattern = java.util.regex.Pattern.compile(
                    "[^\\n。]*(?:伏笔|暗示|暗藏|埋下|秘密|真相|隐情|未解)[^\\n。]*");
            var matcher = pattern.matcher(fullText);
            int count = 0;
            while (matcher.find() && count < 5) {
                String desc = matcher.group().trim();
                if (desc.length() > 5) {
                    repository.saveForeshadowing(new Foreshadowing(
                            projectId, desc, "从大纲自动抽取", 1));
                    count++;
                }
            }
        } catch (Exception e) {
            log.warn("伏笔抽取失败: {}", e.getMessage());
        }
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

    /** 收集设定摘要供连续性检查参考。p11-q0-01: 经 RelevanceGateway 按本章 cast 收窄角色 */
    private String collectSettingsForCheck(String projectId, List<String> cast) {
        StringBuilder sb = new StringBuilder();
        var characters = relevanceGateway != null
                ? relevanceGateway.relevantCharacters(projectId, cast, 10)
                : repository.findCharactersByProject(projectId);
        for (var c : characters) {
            sb.append(c.getName()).append("（").append(c.getRole()).append("）");
            if (c.getPersonality() != null) sb.append(" 性格：").append(c.getPersonality());
            if (c.getAbility() != null) sb.append(" 能力：").append(c.getAbility());
            sb.append("\n");
        }
        for (var e : repository.findKnowledgeEntriesByProject(projectId)) {
            sb.append("【").append(e.getName()).append("】").append(e.getContent()).append("\n");
        }
        // p5-c1: 注入未回收伏笔台账，供连续性检查器参考
        // p16-04: cap 20——mustResolve 恒在，其余按埋设先后（早埋优先），防全量注入随章节无界增长
        var foreshadowings = repository.findUnresolvedForeshadowings(projectId).stream()
                .sorted(java.util.Comparator
                        .comparing(Foreshadowing::isMustResolve).reversed()
                        .thenComparing(Foreshadowing::getPlantedAtChapter))
                .limit(20)
                .toList();
        if (!foreshadowings.isEmpty()) {
            sb.append("\n=== 待回收伏笔 ===\n");
            for (var f : foreshadowings) {
                sb.append("- ").append(f.getDescription())
                  .append("（埋于第").append(f.getPlantedAtChapter()).append("章）\n");
            }
        }
        // p11-q0-02: 故事真典 ACTIVE 事实——长程一致性校验依据（替代级联压缩摘要）
        if (relevanceGateway != null) {
            var facts = relevanceGateway.relevantFacts(projectId, cast, 15);
            if (!facts.isEmpty()) {
                sb.append("\n=== 故事真典（已确立事实，新章节必须严格遵守） ===\n");
                for (var f : facts) {
                    sb.append("- ").append(f.getSubject()).append("（").append(f.getFactType())
                            .append("）：").append(f.getAssertion()).append("\n");
                }
            }
        }
        return sb.toString();
    }

    /** p11-q0-01: 解析 synopsis.cast（逗号/顿号/空格分隔）为名字列表，供收窄 */
    private static List<String> parseCast(String cast) {
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
            Project p = loadProject(projectId);
            if (p.getState() == NovelState.PAUSED_AT_CHECKPOINT) {
                log.info("写作循环遇暂停，已写 {} 章", written);
                break;
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

    /**
     * p16-02: 大纲耗尽且 mustResolve 伏笔未回收时，追加『尾声·伏笔回收卷』。
     * 回收指令写进新章梗概 writingNotes（ChapterWriter 的确定性输入通道），替代原
     * 写 ContinuityIssue 后直接 break 的死路。有界：回收卷已存在时返回 false。
     * 包级可见供测试。
     */
    boolean appendForeshadowingRecoveryVolumeIfNeeded(String projectId, Project p) {
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

    /** 统计项目总字数 */
    public int getTotalWordCount(String projectId) {
        // p12-K3: 使用 SUM 聚合，不全量加载正文
        return repository.getTotalWordCount(projectId);
    }

    private void generateSummary(String projectId, int from, int to) {
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
    void updateBookSnapshot(String projectId, int toChapter, String volumeSummary) {
        try {
            Project p = loadProject(projectId);
            String previous = p.getBookSnapshot() != null ? p.getBookSnapshot() : "（暂无，首版）";
            String keyFacts = "";
            if (storyFactRepository != null) {
                keyFacts = storyFactRepository.findActive(projectId).stream()
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
            var temp = mm.summarize(projectId, 1, toChapter,
                    previous + "\n\n" + (volumeSummary != null ? volumeSummary : "") + "\n\n" + keyFacts);
            String content = temp != null ? temp.getContent() : null;
            if (content == null || content.isBlank()) return;
            // 校验 JSON 含必需字段，坏输出保留旧版
            int start = content.indexOf('{');
            int end = content.lastIndexOf('}');
            if (start < 0 || end <= start) return;
            String json = content.substring(start, end + 1);
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            if (node.path("mainlineProgress").isMissingNode()) return;
            repository.deleteSummary(temp.getId());
            p.setBookSnapshot(json);
            repository.saveProject(p);
            log.info("全书快照已更新(ch{}): {}", toChapter,
                    node.path("mainlineProgress").asText().length() > 40
                            ? node.path("mainlineProgress").asText().substring(0, 40) + "..."
                            : node.path("mainlineProgress").asText());
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
            if (storyFactRepository != null) {
                var facts = storyFactRepository.findActive(projectId);
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

            if (plotter != null) {
                int nextStart = currentChapter + 1;
                var replanGenre = repository.findProject(projectId)
                        .map(Project::getGenre).orElse(null);
                String generated = plotter.planVolume(
                        outline.getPremise(), writtenSummary.toString(),
                        "后续卷", "根据已写剧情重规划未写卷",
                        nextStart, nextStart + 7, "", "", replanGenre);
                Outline scratch = new Outline(projectId, outline.getPremise());
                int nextVol = outline.getVolumes().stream().mapToInt(Volume::getVolumeIndex).max().orElse(0) + 1;
                parseAndAppendVolume(scratch, generated, projectId, nextVol, nextStart);
                new com.writing.novel.service.OutlineReplanner()
                        .replaceUnwritten(outline, currentChapter, scratch.getVolumes());
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
        var summaries = repository.findSummariesByProject(projectId);
        for (var s : summaries) {
            if (chapterIndex >= s.getFromChapter() && chapterIndex <= s.getToChapter()) {
                s.setDirty(true);
                repository.saveSummary(s);
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
            if (storyFactRepository != null) {
                var allFacts = storyFactRepository.findAll(projectId);
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

    private ChapterSynopsis findSynopsis(Outline outline, int chapterIndex) {
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
            String volumeText = plotter.planVolume(
                    masterOutline, previousSummary,
                    "卷" + volumeNumber, "（延迟生成，根据总纲规划）",
                    nextStartChapter, nextEndChapter, existingCharacters, protagonistName,
                    p.getGenre());

            // 解析并追加到 outline
            Outline outline = outlineOpt.get();
            int parsed = parseAndAppendVolume(outline, volumeText, projectId, volumeNumber, nextStartChapter);
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
     * @return 解析出的章节数（0 表示解析失败）
     */
    private int parseAndAppendVolume(Outline outline, String text, String projectId,
                                       int volumeNumber, int startChapter) {
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
        while (m.find()) {
            try {
                int chIdx = Integer.parseInt(m.group(1));
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
                if (plotter != null) {
                    plotter.parseChapterDetails(syn, body);
                } else {
                    syn.setBeats(body);
                }
                volume.addSynopsis(syn);
                parsed++;
            } catch (Exception ignored) {}
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
        }
        return parsed;
    }

    /**
     * p7-1: 重写指定章节（不限项目状态，FINAL 也能用）。
     * @param instructions 可选修改意见（如"增加对话""减少打斗"）
     */
    public Chapter rewriteChapter(String projectId, int chapterIndex, String instructions) {
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
            existing.setContent(newContent);
            repository.saveChapter(existing);

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
            } catch (Exception ignored) {}

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
        String editedPassage = passageEditor.editPassage(
                targetParagraph, contextBefore, contextAfter, instruction, mode);
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

        chapter.setContent(newContent.toString());
        repository.saveChapter(chapter);
        // p16-05: 段落编辑改变了正文——按章重建向量索引（清旧 chunk + 新正文入库），
        // 避免 editPassage 后向量库仍保留编辑前的旧情节被后续章节检索召回
        if (chapterWriter != null) {
            try { chapterWriter.reindexChapter(projectId, chapterIndex, chapter.getContent()); } catch (Exception ignored) {}
        }
        log.info("段落编辑完成(章{}段{}): {}→{}字", chapterIndex, resolvedIndex,
                content.length(), newContent.length());
        if (contextAssembler != null) contextAssembler.invalidateProject(projectId);

        final Chapter snapshot = chapter;
        POST_PROCESS.execute(() -> {
            try { invalidateSummariesForChapter(projectId, chapterIndex); } catch (Exception ignored) {}
            try {
                if (entityGraphService != null) entityGraphService.extractFromChapter(projectId, snapshot);
            } catch (Exception ignored) {}
            try {
                if (foreshadowingExtractor != null) foreshadowingExtractor.extract(projectId, snapshot);
            } catch (Exception ignored) {}
            try {
                if (factHarvester != null) factHarvester.harvest(projectId, snapshot);
            } catch (Exception ignored) {}
        });

        return chapter;
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
    public ChapterQualityReport buildChapterQualityReport(String projectId, int chapterIndex) {
        var chapter = repository.findChapterByIndex(projectId, chapterIndex).orElse(null);
        if (chapter == null) {
            return new ChapterQualityReport(chapterIndex, "", 0);
        }
        ChapterQualityReport report = new ChapterQualityReport(
                chapterIndex, chapter.getTitle(), chapter.getWordCount());

        // 读者评审
        repository.findChapterReview(projectId, chapterIndex)
                .ifPresent(report::setReview);

        // 该章的 ContinuityIssue
        var issues = repository.findUnresolvedIssues(projectId).stream()
                .filter(i -> i.getDetectedAtChapter() == chapterIndex)
                .toList();
        report.setIssues(issues);
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
    private List<ClimaxType> inferClimaxTypes(ChapterSynopsis synopsis) {
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
