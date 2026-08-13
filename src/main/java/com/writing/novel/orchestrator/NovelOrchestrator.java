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

    /** 构思阶段：生成世界观 + 人物 + 大纲 */
    public void runConceiving(String projectId) {
        EventContext.setProjectId(projectId);
        try {
            doConceiving(projectId);
        } finally {
            EventContext.clear();
        }
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

        if (p.getMode() == ProjectMode.INTERACTIVE) {
            p.setState(NovelState.PAUSED_AT_CHECKPOINT);
            repository.saveCheckpoint(new Checkpoint(projectId,
                    Checkpoint.Type.AFTER_INITIAL_SETUP, "初始设定生成完成，待审核"));
        } else {
            p.setState(NovelState.PLANNING);
        }
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
        EventContext.setProjectId(projectId);
        try {
            return doWriteOneChapter(projectId);
        } finally {
            EventContext.clear();
        }
    }

    private Chapter doWriteOneChapter(String projectId) {
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

        // 幂等检查：该章已存在且内容有效则跳过（断点续写时避免重复）
        var existing = repository.findChapterByIndex(projectId, chapterIndex);
        int idempotentMinLen = repository.findWritingPreferences(projectId).map(pref -> pref.getMinChapterLength()).orElse(2000);
        if (existing.isPresent() && existing.get().getWordCount() >= idempotentMinLen) {
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
        String enhancedNotes = synopsis.getWritingNotes();
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

        // 写完后做连续性检查（发现问题记录到 DB，不中断写作）
        checkContinuityAfterWriting(projectId, chapterIndex, chapter);

        // 图数据库：LLM 提取本章实体和关系
        try {
            entityGraphService.extractFromChapter(projectId, chapter);
        } catch (Exception e) {
            log.warn("实体提取失败(章{}): {}", chapterIndex, e.getMessage());
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
        if (readerReviewAgent != null) {
            try {
                var review = readerReviewAgent.review(chapterIndex, chapter.getContent());
                log.info("读者检验(章{}): {}/10 — {}", chapterIndex, review.getScore(), review.getSummary());
                if (!review.isPassed()) {
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
                    } else if (newContent != null) {
                        log.warn("重写内容过短({}字 < 原版{}字的70%)，保留原版", newContent.length(), originalLength);
                    }
                }
                // p11-I1: 持久化章节评价报告
                try {
                    repository.saveChapterReview(ChapterReview.from(projectId, review));
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

        // p7-1: INTERACTIVE 模式——每章写完后暂停等待审核
        if (p.getMode() == ProjectMode.INTERACTIVE) {
            p.setState(NovelState.PAUSED_AT_CHECKPOINT);
            repository.saveProject(p);
            repository.saveCheckpoint(new Checkpoint(projectId,
                    Checkpoint.Type.AFTER_CHAPTER,
                    "第" + chapterIndex + "章「" + chapter.getTitle() + "」已完成，待审核"));
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
        var chapters = repository.findChaptersByProject(projectId);
        // 取本卷最后5章的正文采样
        var recentText = chapters.stream()
                .filter(c -> c.getChapterIndex() >= toChapter - 4 && c.getChapterIndex() <= toChapter)
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
    private void checkContinuityAfterWriting(String projectId, int chapterIndex, Chapter chapter) {
        try {
            String settings = collectSettingsForCheck(projectId);
            var issues = continuityChecker.check(projectId, chapterIndex, chapter.getContent(), settings);

            // HIGH 级矛盾纠偏：触发 1 次重写
            boolean hasHighIssue = issues.stream()
                    .anyMatch(i -> i.getSeverity() == ContinuityIssue.Severity.HIGH);
            if (hasHighIssue && chapter.getContent().length() > 500) {
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
                var guardIssues = consistencyGuard.check(projectId, chapterIndex, chapter.getContent());
                for (var issue : guardIssues) {
                    repository.saveContinuityIssue(issue);
                    log.warn("一致性守卫(章{}): {}", chapterIndex, issue.getDescription());
                }
            }
        } catch (Exception e) {
            log.warn("一致性守卫失败(章{}): {}", chapterIndex, e.getMessage());
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
            }
        } catch (Exception e) {
            log.warn("纠偏重写失败，保留原版本: {}", e.getMessage());
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

    /** 收集设定摘要供连续性检查参考 */
    private String collectSettingsForCheck(String projectId) {
        StringBuilder sb = new StringBuilder();
        for (var c : repository.findCharactersByProject(projectId)) {
            sb.append(c.getName()).append("（").append(c.getRole()).append("）");
            if (c.getPersonality() != null) sb.append(" 性格：").append(c.getPersonality());
            if (c.getAbility() != null) sb.append(" 能力：").append(c.getAbility());
            sb.append("\n");
        }
        for (var e : repository.findKnowledgeEntriesByProject(projectId)) {
            sb.append("【").append(e.getName()).append("】").append(e.getContent()).append("\n");
        }
        // p5-c1: 注入未回收伏笔台账，供连续性检查器参考
        var foreshadowings = repository.findUnresolvedForeshadowings(projectId);
        if (!foreshadowings.isEmpty()) {
            sb.append("\n=== 待回收伏笔 ===\n");
            for (var f : foreshadowings) {
                sb.append("- ").append(f.getDescription())
                  .append("（埋于第").append(f.getPlantedAtChapter()).append("章）\n");
            }
        }
        return sb.toString();
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
            Outline outline = repository.findOutlineByProject(projectId).orElse(null);
            if (outline == null) break;

            // p12-fix: 分层大纲延迟生成——大纲耗尽时尝试生成下一卷
            if (!hasNextChapter(outline, p.getCurrentChapterIndex())) {
                boolean extended = tryExtendLayeredOutline(projectId, p);
                if (extended) {
                    // 重新加载大纲
                    outline = repository.findOutlineByProject(projectId).orElse(null);
                    if (outline == null || !hasNextChapter(outline, p.getCurrentChapterIndex())) {
                        // 仍然没有更多章节，进入完结流程
                        break;
                    }
                } else {
                    break;
                }
            }

            // 跳过已完成的逻辑到 hasNextChapter false 的处理
            if (!hasNextChapter(outline, p.getCurrentChapterIndex())) {
                // p10.1-e1: FINAL 前自动触发伏笔重要性审计（只触发一次）
                if (foreshadowingImportanceAuditor != null && written == 0) {
                    try {
                        int upgraded = foreshadowingImportanceAuditor.audit(projectId);
                        if (upgraded > 0) {
                            log.info("FINAL前伏笔审计: {} 条升级为HIGH", upgraded);
                        }
                    } catch (Exception e) {
                        log.warn("伏笔审计失败: {}", e.getMessage());
                    }
                }

                // p10-c1: FINAL 完整性门禁——mustResolve 的关键伏笔未回收时，尝试追加回收章
                var unresolved = repository.findUnresolvedForeshadowings(projectId);
                var criticalUnresolved = unresolved.stream()
                        .filter(Foreshadowing::isMustResolve)
                        .toList();

                if (!criticalUnresolved.isEmpty() && written < maxChapters
                        && p.getCurrentChapterIndex() < 999) {
                    // p10-c1: 有关键伏笔未回收，追加一个"伏笔回收章"而非直接完结
                    // 标记伏笔为已过期（避免无限追加），然后尝试再写一章
                    log.warn("全书完结前仍有 {} 条 mustResolve 伏笔未回收，追加回收章",
                            criticalUnresolved.size());
                    for (var f : criticalUnresolved) {
                        if (f.getExpectedResolveChapter() == null) {
                            f.setExpectedResolveChapter(p.getCurrentChapterIndex());
                        }
                    }
                    // 给最后一章注入强制回收指令（通过 ContinuityIssue feedback）
                    StringBuilder resolveCmd = new StringBuilder("本章为最终回收章，必须回收以下关键伏笔：\n");
                    for (var f : criticalUnresolved) {
                        resolveCmd.append("- ").append(f.getDescription())
                                .append("（埋于第").append(f.getPlantedAtChapter()).append("章）\n");
                    }
                    resolveCmd.append("请在正文中明确揭晓/解答这些悬念。");
                    repository.saveContinuityIssue(new ContinuityIssue(
                            projectId, p.getCurrentChapterIndex() + 1,
                            ContinuityIssue.Type.FORESHADOWING,
                            ContinuityIssue.Severity.HIGH,
                            resolveCmd.toString()));
                    // 降级伏笔 mustResolve（防下一轮仍卡住），但保留 HIGH priority
                    for (var f : criticalUnresolved) {
                        f.setMustResolve(false);
                        repository.saveForeshadowing(f);
                    }
                    // 不转 FINAL，让循环继续写一章（会因 hasNextChapter 仍 false 而 fallback）
                    // 手动推进：设 state 保持 WRITING，下一轮会再次走到这里但 criticalUnresolved 已清空
                }

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
                break;
            }
            if (p.getState() == NovelState.VOLUME_DONE) {
                p.setState(NovelState.WRITING);
                repository.saveProject(p);
            }

            // 异常容错：单章失败不崩溃整个 loop
            try {
                Chapter ch = runWritingOneChapter(projectId);
                if (ch == null) break;
                written++;
                consecutiveFailures = 0; // 成功则重置失败计数

                // 质量熔断：检查本章是否有 HIGH 级连续性问题
                // p12-fix: 放宽阈值 3→10（百万字写作中 ConsistencyGuard 检测敏感，
                // 能力越界/凭空证据在武侠/玄幻题材中高频出现，不应因此中断写作）
                boolean hasHighIssue = !repository.findUnresolvedIssues(projectId).stream()
                        .filter(i -> i.getDetectedAtChapter() == ch.getChapterIndex())
                        .filter(i -> i.getSeverity() == ContinuityIssue.Severity.HIGH)
                        .toList().isEmpty();
                if (hasHighIssue) {
                    consecutiveHighIssues++;
                    if (consecutiveHighIssues >= 10) {
                        // 连续 10 章 HIGH 问题，暂停写作
                        log.warn("连续 {} 章 HIGH 问题，暂停写作", consecutiveHighIssues);
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
                }
                // 卷级摘要(span>3)保持 dirty——ContextAssembler 过滤，不注入过期内容
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
                return false;
            }

            // 硬性：未解决 HIGH issues
            long highIssues = repository.findUnresolvedIssues(projectId).stream()
                    .filter(i -> i.getSeverity() == ContinuityIssue.Severity.HIGH).count();
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

            log.info("成书门禁 PASS: 伏笔={}, HIGH issues={}, 字数={}",
                    criticalUnresolved.size(), highIssues, totalWords);
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

            // 获取前文摘要（最近卷的摘要）
            String previousSummary = "";
            var summaries = repository.findSummariesByProject(projectId);
            if (!summaries.isEmpty()) {
                previousSummary = summaries.stream()
                        .reduce((a, b) -> b).get().getContent(); // 取最后一条
            }

            // 获取已有角色列表
            String existingCharacters = repository.findCharactersByProject(projectId).stream()
                    .map(c -> c.getName() + "(" + c.getRole() + ")")
                    .reduce((a, b) -> a + ", " + b).orElse("无");

            // cn-02: 取主角名锚定卷大纲
            String protagonistName = repository.findCharactersByProject(projectId).stream()
                    .filter(c -> c.getRole() != null && c.getRole().contains("主角"))
                    .map(c -> c.getName()).findFirst().orElse("");

            // 调用 planVolume 生成下一卷
            String volumeText = plotter.planVolume(
                    masterOutline, previousSummary,
                    "卷" + volumeNumber, "（延迟生成，根据总纲规划）",
                    nextStartChapter, nextEndChapter, existingCharacters, protagonistName);

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
        String cleanText = text == null ? "" : text.replace("**", "");
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
                syn.setBeats(body);
                volume.addSynopsis(syn);
                parsed++;
            } catch (Exception ignored) {}
        }
        log.info("解析追加卷{}: {} 章", volumeNumber, parsed);
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

            // mc-p1-02: 重写后失效覆盖该章的摘要（标记 dirty 供下次组装时重算）
            try {
                invalidateSummariesForChapter(projectId, chapterIndex);
            } catch (Exception ignored) {}

            // 重新提取实体和伏笔
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

        String[] paragraphs = content.split("\n");
        if (paragraphIndex < 0 || paragraphIndex >= paragraphs.length) {
            throw new IllegalArgumentException("段落号越界: " + paragraphIndex + "（共" + paragraphs.length + "段）");
        }

        // 定位验证：选中文本应与段落匹配
        String targetParagraph = paragraphs[paragraphIndex];
        if (selectedText != null && !selectedText.isBlank()
                && !targetParagraph.contains(selectedText.substring(0, Math.min(10, selectedText.length())))) {
            throw new IllegalArgumentException("段落定位失败：选中文本与段落不匹配，请刷新页面重试");
        }

        // 提取上下文（前2段+后2段）
        String contextBefore = extractContext(paragraphs, paragraphIndex, -1, 2);
        String contextAfter = extractContext(paragraphs, paragraphIndex, 1, 2);

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
            if (i == paragraphIndex) {
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
        log.info("段落编辑完成(章{}段{}): {}→{}字", chapterIndex, paragraphIndex,
                content.length(), newContent.length());

        // 重跑检测器
        try { entityGraphService.extractFromChapter(projectId, chapter); } catch (Exception ignored) {}
        try { foreshadowingExtractor.extract(projectId, chapter); } catch (Exception ignored) {}

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

    /** 从评分报告中提取低分维度的改进方向 */
    private String buildImprovementNotes(com.writing.novel.agent.QualityEvaluationAgent.EvaluationReport report) {
        if (report == null) return "";
        StringBuilder sb = new StringBuilder();
        if (report.consistency < 7) sb.append("一致性需改进；");
        if (report.plot < 7) sb.append("情节爽点密度需提升；");
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
