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
                              ForeshadowingImportanceAuditor foreshadowingImportanceAuditor) {
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
                    "世界观维度：" + wbSummary, "[]", 0);
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
                        "角色设定：" + charSummary2, "[]", 0);
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
            plotter.plan(projectId, p.getConcept(), theme, 24, theme, genreRules, plotDiversity);

            // 记录大纲生成
            if (creativeDiversityService != null) {
                var outline = repository.findOutlineByProject(projectId);
                if (outline.isPresent()) {
                    String plotSummary = outline.get().getVolumes().stream()
                            .map(v -> "卷" + v.getVolumeIndex() + ":" + v.getTitle())
                            .reduce((a, b) -> a + ", " + b).orElse("无");
                    creativeDiversityService.record(projectId, "PLOTTER",
                            "剧情结构：" + plotSummary, "[]", 0);
                }
            }
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
            p.setState(NovelState.VOLUME_DONE);
            repository.saveProject(p);
            return null;
        }

        // p10-a2: 角色弧光调度——注入配角出场建议
        String enhancedNotes = synopsis.getWritingNotes();
        if (arcScheduler != null) {
            String arcSuggestion = arcScheduler.scheduleAppearance(projectId, chapterIndex);
            if (!arcSuggestion.isBlank()) {
                enhancedNotes = (enhancedNotes != null ? enhancedNotes + "\n" : "") + arcSuggestion;
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

        // p10-d1: 风格一致性检查（每 5 章）——检测文风漂移和高频套路词
        if (chapterIndex % 5 == 0) {
            try {
                var profile = repository.findStyleProfileByProject(projectId);
                if (profile.isPresent()) {
                    String baseline = "基调：" + profile.get().getTone()
                            + "，叙事：" + profile.get().getVoice()
                            + "，注意事项：" + profile.get().getDosAndDonts();
                    String content = chapter.getContent();
                    if (content.length() > 3000) content = content.substring(0, 3000);
                    String report = styleGuardian.checkConsistency(projectId, content, baseline);
                    log.info("风格检查(章{}): {}", chapterIndex,
                            report.length() > 100 ? report.substring(0, 100) : report);
                }
            } catch (Exception e) {
                log.warn("风格检查失败(章{}): {}", chapterIndex, e.getMessage());
            }
        }

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

        // 每 5 章检查风格漂移，解析评分并记录问题（p3-02）
        if (chapterIndex % 5 == 0) {
            try {
                var styleOpt = repository.findStyleProfileByProject(projectId);
                if (styleOpt.isPresent()) {
                    var style = styleOpt.get();
                    String baseline = (style.getTone() != null ? style.getTone() : "")
                            + (style.getVoice() != null ? " " + style.getVoice() : "")
                            + (style.getDosAndDonts() != null ? " " + style.getDosAndDonts() : "");
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

    /** 从大纲文本中抽取伏笔条目存入 Foreshadowing 表（p3-03） */
    private void extractForeshadowingsFromOutline(String projectId) {
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
        int written = 0;
        int consecutiveFailures = 0;
        int consecutiveHighIssues = 0;

        while (written < maxChapters) {
            Project p = loadProject(projectId);
            Outline outline = repository.findOutlineByProject(projectId).orElse(null);
            if (outline == null) break;
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
                p.setState(NovelState.FINAL);
                repository.saveProject(p);
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
                boolean hasHighIssue = !repository.findUnresolvedIssues(projectId).stream()
                        .filter(i -> i.getDetectedAtChapter() == ch.getChapterIndex())
                        .filter(i -> i.getSeverity() == ContinuityIssue.Severity.HIGH)
                        .toList().isEmpty();
                if (hasHighIssue) {
                    consecutiveHighIssues++;
                    if (consecutiveHighIssues >= 3) {
                        // 连续 3 章 HIGH 问题，暂停写作
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
        return repository.findChaptersByProject(projectId).stream()
                .mapToInt(Chapter::getWordCount)
                .sum();
    }

    private void generateSummary(String projectId, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i <= to; i++) {
            repository.findChapterByIndex(projectId, i).ifPresent(c ->
                    sb.append(c.getContent()).append("\n"));
        }
        if (!sb.isEmpty()) {
            memoryManager.summarize(projectId, from, to, sb.toString());
        }
    }

    private void startNextVolumeOrFinish(String projectId) {
        Project p = loadProject(projectId);
        Outline outline = repository.findOutlineByProject(projectId).orElse(null);
        if (outline != null && hasNextChapter(outline, p.getCurrentChapterIndex())) {
            p.setState(NovelState.WRITING);
        } else {
            p.setState(NovelState.FINAL);
        }
        repository.saveProject(p);
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

        if (newContent != null && newContent.length() >= 2000) {
            existing.setContent(newContent);
            repository.saveChapter(existing);

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

            log.info("章节重写成功(章{}): {} 字", chapterIndex, newContent.length());
        }
        return existing;
    }

    /**
     * p10.1-e1: 伏笔重要性回溯审计——对旧伏笔做批量重要性重评估。
     * 解决 Phase 10 之前提取的伏笔 priority 全为默认 MEDIUM 的问题。
     */
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
