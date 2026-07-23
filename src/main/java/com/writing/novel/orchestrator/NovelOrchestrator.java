package com.writing.novel.orchestrator;

import com.writing.novel.agent.*;
import com.writing.novel.domain.*;
import com.writing.novel.memory.ContextAssembler;
import com.writing.novel.memory.SummaryService;
import com.writing.novel.service.ClimaxDiversityTracker;
import com.writing.novel.service.EntityGraphService;
import com.writing.novel.service.PlotProgressionDetector;
import com.writing.novel.spi.NovelRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

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
                              ForeshadowingExtractor foreshadowingExtractor) {
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
    }

    /** 构思阶段：生成世界观 + 人物 + 大纲 */
    public void runConceiving(String projectId) {
        Project p = loadProject(projectId);
        if (p.getState() != NovelState.CONCEIVING && p.getState() != NovelState.PAUSED_AT_CHECKPOINT) {
            throw new IllegalStateException("项目不在构思阶段: " + p.getState());
        }

        worldBuilder.build(projectId, p.getConcept(), p.getTags());

        // 风格基线前置：在世界观生成后立即建立全书风格基线（幂等：已有则跳过）
        if (repository.findStyleProfileByProject(projectId).isEmpty()) {
            styleGuardian.establishProfile(projectId,
                    "世界观构思：" + p.getConcept() + "\n类型标签：" + p.getTags());
        }

        // 人物设计（幂等：已有主角则跳过）
        // 传入构思+世界观摘要，让人物设计贴合设定（解决"人物总是陆昭然"问题）
        if (repository.findCharactersByProject(projectId).isEmpty()) {
            String worldSummary = collectWorldbuildingSummary(projectId);
            String charRequirement = "主角。故事构思：" + p.getConcept()
                    + "；类型标签：" + p.getTags()
                    + (worldSummary.isEmpty() ? "" : "\n已生成的世界观设定：\n" + worldSummary);
            characterDesigner.design(projectId, charRequirement, List.of());
        }

        // 大纲（幂等：已有有效大纲则跳过，fallback 大纲需重新生成）
        // 提取主角名+世界观+人物摘要传给 Plotter（解决 Agent 间信息断层）
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
            plotter.plan(projectId, p.getConcept(), theme, 24);
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
        p.setState(NovelState.PLANNING);
        repository.saveProject(p);
    }

    private void runPlanning(String projectId) {
        Project p = loadProject(projectId);
        p.setState(NovelState.WRITING);
        repository.saveProject(p);
    }

    /** 写一章 */
    public Chapter runWritingOneChapter(String projectId) {
        Project p = loadProject(projectId);
        Outline outline = repository.findOutlineByProject(projectId).orElseThrow(
                () -> new IllegalStateException("无大纲，无法写作"));

        int chapterIndex = p.getCurrentChapterIndex() + 1;

        // 幂等检查：该章已存在且内容有效则跳过（断点续写时避免重复）
        var existing = repository.findChapterByIndex(projectId, chapterIndex);
        if (existing.isPresent() && existing.get().getWordCount() >= 500) {
            // 已有有效章节，推进索引并返回
            p.setCurrentChapterIndex(chapterIndex);
            repository.saveProject(p);
            return existing.get();
        }

        ChapterSynopsis synopsis = findSynopsis(outline, chapterIndex);
        if (synopsis == null) {
            p.setState(NovelState.VOLUME_DONE);
            repository.saveProject(p);
            return null;
        }

        Chapter chapter = chapterWriter.write(projectId, chapterIndex,
                synopsis.getTitle(), synopsis.getSynopsis(), synopsis.getWritingNotes());

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
            }
        } catch (Exception e) {
            log.warn("爽点追踪失败(章{}): {}", chapterIndex, e.getMessage());
        }

        // 剧情停滞检测：基于张力曲线，连续低张力触发警告
        try {
            plotProgressionDetector.detectStall(projectId, chapterIndex)
                    .ifPresent(issue -> {
                        repository.saveContinuityIssue(issue);
                        log.warn("剧情停滞警告(章{}): {}", chapterIndex, issue.getDescription());
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
            repository.saveContinuityIssue(new ContinuityIssue(
                    projectId, toChapter,
                    ContinuityIssue.Type.FORESHADOWING,
                    ContinuityIssue.Severity.MEDIUM,
                    desc.toString()));
            log.info("ChekhovAudit(卷{}): {} 条未回收伏笔", volumeIndex, unresolved.size());
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
     * p5-c1: 检测本章是否回收了未回收的伏笔。
     * 方法：对每个未回收伏笔，检查章节内容是否包含伏笔关键词 + 回收标记词。
     */
    private void checkForeshadowingResolution(String projectId, int chapterIndex, Chapter chapter) {
        var unresolved = repository.findUnresolvedForeshadowings(projectId);
        if (unresolved.isEmpty()) return;

        String content = chapter.getContent();
        if (content == null) return;

        // 回收标记词：表明伏笔在本章被揭晓/回收
        var resolveMarkers = java.util.List.of("真相", "原来", "终于", "果然", "揭晓", "解开", "浮出水面",
                "大白于天下", "谜底", "原来如此", "终于明白", "原来是这样");

        for (var f : unresolved) {
            // 提取伏笔描述的关键词（取前 4-8 字作为匹配关键词）
            String desc = f.getDescription();
            String keyword = extractKeyword(desc);
            if (keyword == null || keyword.length() < 2) continue;

            // 检查章节内容是否同时包含伏笔关键词 + 回收标记
            boolean hasKeyword = content.contains(keyword);
            boolean hasResolveMarker = resolveMarkers.stream().anyMatch(content::contains);

            if (hasKeyword && hasResolveMarker) {
                f.resolve(chapterIndex);
                repository.saveForeshadowing(f);
                log.info("伏笔回收(章{}): {}", chapterIndex, desc);
            }
        }
    }

    /** 从伏笔描述中提取匹配关键词 */
    private String extractKeyword(String desc) {
        if (desc == null || desc.length() < 2) return null;
        // 去除常见前缀词
        String cleaned = desc.replaceAll("^(伏笔|暗示|暗藏|埋下|秘密|真相|隐情|未解|关于|有关)[:：]", "").trim();
        // 取前 6 字作为关键词
        return cleaned.length() > 6 ? cleaned.substring(0, 6) : cleaned;
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
            if (newContent != null && newContent.length() >= 500) {
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
