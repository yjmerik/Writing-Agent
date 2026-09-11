package com.writing.novel.orchestrator;

import com.writing.novel.agent.ChapterWriterAgent;
import com.writing.novel.agent.ConsistencyGuard;
import com.writing.novel.agent.ContinuityCheckerAgent;
import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.ChapterReview;
import com.writing.novel.domain.ChapterSynopsis;
import com.writing.novel.domain.ContinuityIssue;
import com.writing.novel.domain.Foreshadowing;
import com.writing.novel.domain.Outline;
import com.writing.novel.domain.Volume;
import com.writing.novel.domain.WritingPreferences;
import com.writing.novel.spi.NovelRepository;
import com.writing.novel.spi.StoryCanon;
import com.writing.novel.telemetry.EventContext;
import com.writing.novel.telemetry.EventLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * p22-04: 章节生产模块——Context（写前上下文装配）→ Write（正文生成）→
 * Quality（连续性/一致性/风格质量链）→ Rewrite（纠偏重写）经小结果接口调用。
 * Orchestrator 只协调阶段与状态机（索引推进/POST_PROCESSING/DONE/checkpoint），
 * 不再拥有写前装配与质量修复策略。
 *
 * <p>结果接口：{@link #produce} 一次驱动全部四阶段并返回 {@link ProductionResult}；
 * 编排器亦可逐阶段调用（prepare/write/qualityGate）以便在阶段间插入状态转移。
 */
public class ChapterProduction {

    private static final Logger log = LoggerFactory.getLogger(ChapterProduction.class);

    private final NovelOrchestrator orch; // late-set 策略依赖（arbiter/repairArbiter/qualityCouncil/storyCanon/relevanceGateway）经此读取
    private final NovelRepository repository;
    private final ChapterWriterAgent chapterWriter;
    private final ContinuityCheckerAgent continuityChecker;
    private final com.writing.novel.agent.ReaderReviewAgent readerReviewAgent;
    private final ConsistencyGuard consistencyGuard;
    private final com.writing.novel.service.ConsistencyGate consistencyGate;
    private final com.writing.novel.service.ExpositionDetector expositionDetector;
    private final com.writing.novel.agent.StyleGuardianAgent styleGuardian;
    private final com.writing.novel.agent.ForeshadowingResolutionDetector foreshadowingResolutionDetector;
    private final com.writing.novel.service.ArcScheduler arcScheduler;
    private final com.writing.novel.agent.PlotMotifTracker plotMotifTracker;
    private final com.writing.novel.service.PlanGate planGate;

    /** 生产结果：章节 + 质量链是否触发了重写 */
    public record ProductionResult(Chapter chapter, boolean rewritten) {}

    ChapterProduction(NovelOrchestrator orch,
                      NovelRepository repository,
                      ChapterWriterAgent chapterWriter,
                      ContinuityCheckerAgent continuityChecker,
                      com.writing.novel.agent.ReaderReviewAgent readerReviewAgent,
                      ConsistencyGuard consistencyGuard,
                      com.writing.novel.service.ConsistencyGate consistencyGate,
                      com.writing.novel.service.ExpositionDetector expositionDetector,
                      com.writing.novel.agent.StyleGuardianAgent styleGuardian,
                      com.writing.novel.agent.ForeshadowingResolutionDetector foreshadowingResolutionDetector,
                      com.writing.novel.service.ArcScheduler arcScheduler,
                      com.writing.novel.agent.PlotMotifTracker plotMotifTracker,
                      com.writing.novel.service.PlanGate planGate) {
        this.orch = orch;
        this.repository = repository;
        this.chapterWriter = chapterWriter;
        this.continuityChecker = continuityChecker;
        this.readerReviewAgent = readerReviewAgent;
        this.consistencyGuard = consistencyGuard;
        this.consistencyGate = consistencyGate;
        this.expositionDetector = expositionDetector;
        this.styleGuardian = styleGuardian;
        this.foreshadowingResolutionDetector = foreshadowingResolutionDetector;
        this.arcScheduler = arcScheduler;
        this.plotMotifTracker = plotMotifTracker;
        this.planGate = planGate;
    }

    // ---------------- 小结果接口（Context→Write→Quality→Rewrite） ----------------

    /**
     * 一次驱动全部四阶段。注意：本方法不做任何项目状态转移（不推进索引、不改章节状态）——
     * 状态机归 Orchestrator；调用方拿到 ProductionResult 自行裁决。
     */
    public ProductionResult produce(String projectId, int chapterIndex,
                                    ChapterSynopsis synopsis, String authorInstruction) {
        String notes = prepare(projectId, chapterIndex, synopsis, authorInstruction);
        Chapter chapter = write(projectId, chapterIndex, synopsis, notes);
        boolean rewritten = qualityGate(projectId, chapterIndex, chapter,
                NovelOrchestrator.parseCast(synopsis != null ? synopsis.getCast() : ""));
        return new ProductionResult(chapter, rewritten);
    }

    /** Context：写前上下文装配——作者指令 + 弧光出场建议 + 桥段去重约束 + 梗概预检 */
    public String prepare(String projectId, int chapterIndex, ChapterSynopsis synopsis,
                          String authorInstruction) {
        // p10-a2: 角色弧光调度——注入配角出场建议
        // p18-03: 作者写前指令优先注入（【作者指令】段），再叠加弧光/桥段等自动 notes
        String enhancedNotes = NovelOrchestrator.prependAuthorInstruction(synopsis.getWritingNotes(), authorInstruction);
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
                orch.markQualityRisk(projectId, "PlanGate(章" + chapterIndex + ")", synGate.feedback());
            }
        }
        return enhancedNotes;
    }

    /** Write：正文生成（chapterWriter 保证 ≥500 字或抛异常），发 CHAPTER_WRITTEN 事件 */
    public Chapter write(String projectId, int chapterIndex, ChapterSynopsis synopsis,
                         String enhancedNotes) {
        EventContext.setChapter(chapterIndex);
        long writeStart = System.currentTimeMillis();
        Chapter chapter = chapterWriter.write(projectId, chapterIndex,
                synopsis.getTitle(), synopsis.getSynopsis(), enhancedNotes);
        EventLog.emit("CHAPTER_WRITTEN", Map.of(
                "chapterIndex", chapterIndex,
                "wordCount", chapter.getWordCount(),
                "durationMs", System.currentTimeMillis() - writeStart));
        return chapter;
    }

    // ---------------- Quality→Rewrite 质量链（自 NovelOrchestrator 原样迁移） ----------------


    /**
     * 写完一章后检查连续性。
     * 发现 HIGH 级矛盾时触发 1 次重写（把矛盾描述加入 prompt 要求修正）。
     * 每 5 章检查一次风格漂移。
     */
    boolean qualityGate(String projectId, int chapterIndex, Chapter chapter, List<String> cast) {
        boolean rewritten = false; // p10-w1-07: 标记是否触发重写（不提前 return，保持伏笔检测等后续流程）
        java.util.List<ContinuityIssue> continuityIssuesRef = java.util.List.of(); // p11-q2-03: 供 guard 合并重写引用
        try {
            String settings = collectSettingsForCheck(projectId, cast);
            var issues = continuityChecker.check(projectId, chapterIndex, chapter.getContent(), settings);
            continuityIssuesRef = issues;

            boolean hasHighIssue = issues.stream()
                    .anyMatch(i -> i.getSeverity() == ContinuityIssue.Severity.HIGH);
            if (orch.arbiterRef() == null && hasHighIssue && chapter.getContent().length() > 500) {
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
            var staleCount = repository.findUnresolvedForeshadowings(projectId).stream()
                    .filter(f -> f.isMustResolve() || f.getPriority() == Foreshadowing.Priority.HIGH)
                    .filter(f -> f.isStale(chapterIndex, threshold))
                    .count();
            // p32-09: 同一伏笔只保留一条未关闭提醒（旧行为逐章重复追加淹没当章阻断项）
            int written = com.writing.novel.service.SuspenseReminderPolicy.refresh(
                    repository, projectId, chapterIndex, threshold);
            if (staleCount > 0) {
                log.warn("伏笔悬置告警(章{}): {} 条悬置过久，新提醒 {} 条", chapterIndex, staleCount, written);
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
                if (orch.arbiterRef() == null && hasHigh && !rewritten && chapter.getContent().length() > 500) {
                    // p11-q2-03: 合并重写——用 QualityCouncil 汇总 continuity+guard 问题生成合并纠偏指令
                    // （解除"guard HIGH 时只喂 guard 问题"的分散模式，多维问题一次重写联合修正）
                    String desc;
                    if (orch.qualityCouncil() != null) {
                        var council = orch.qualityCouncil().collect(continuityIssuesRef, guardIssues, 10, null, null);
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

        if (orch.arbiterRef() != null) {
            rewritten = applyArbiterDecision(projectId, chapterIndex, chapter, cast,
                    continuityIssuesRef, rewritten);
        }

        // p10-d2: 一致性门禁——章号/时代用语检查（纯规则）
        try {
            if (consistencyGate != null) {
                var p = repository.findProject(projectId).orElse(null);
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

            // p32-02: LLM 重写前记录版本基线——处理期间作者保存新稿时拒绝覆盖
            int revisionBase = chapter.getVersion();
            String newContent = chapterWriter.rewriteForCorrection(
                    projectId, chapterIndex, synopsis.getTitle(),
                    synopsis.getSynopsis(), synopsis.getWritingNotes(), issueDesc);
            // 重写成功则用新内容原地更新（经版本门，不产生重复）
            if (newContent != null && newContent.length() >= 2000) {
                if (orch.autoRewriteIfCurrent(projectId, chapterIndex, revisionBase,
                        chapter.getTitle(), newContent, "REWRITE")) {
                    // p33-05: 修订边界可能幂等跳过（相同内容不推版本）——以 DB 权威状态回填，
                    // 不得在内存对象上自行 setContent 假设版本自增（会破坏后续 CAS）
                    repository.findChapterByIndex(projectId, chapterIndex)
                            .ifPresent(chapter::adoptStateFrom);
                } else {
                    repository.findChapterByIndex(projectId, chapterIndex)
                            .ifPresent(chapter::adoptStateFrom);
                }
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
            orch.enrichChapterCheckpointPayload(projectId, review.getChapterIndex());
            } catch (Exception e) {
                log.warn("仲裁前读者检验失败(章{}): {}", chapterIndex, e.getMessage());
            }
        }
        var council = (orch.qualityCouncil() != null ? orch.qualityCouncil() : new com.writing.novel.service.QualityCouncil())
                .collect(continuityIssues, guardIssues, readerOverall, suggestion, null);
        int budget = repository.findWritingPreferences(projectId)
                .map(com.writing.novel.domain.WritingPreferences::getRewriteBudget).orElse(1);
        var decision = orch.arbiterRef().arbitrate(council, budget, alreadyRewritten);
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
        if (orch.repairArbiter() == null) return;
        var action = orch.repairArbiter().classify(issue, alreadyRewritten);
        // p11-q3-01: 质量事件遥测——一致性问题 + 仲裁动作可观测
        EventLog.emit("CONSISTENCY_ISSUE", Map.of(
                "type", issue.getType().name(), "severity", issue.getSeverity().name(),
                "action", action.name(), "desc", EventLog.truncate(issue.getDescription(), 150)));
        switch (action) {
            case MANUAL_REVIEW -> {
                // p32-02: 状态标记走轻量版本门——绝不整行覆盖处理期间作者保存的正文
                if (repository.updateChapterStatusIfVersion(projectId, chapterIndex,
                        Chapter.Status.NEEDS_MANUAL_REVIEW, chapter.getVersion())) {
                    chapter.setStatus(Chapter.Status.NEEDS_MANUAL_REVIEW);
                    // p33-01（体验 #18）：屏障立即持久化——调度只认屏障，状态字段不再被单点绕过
                    repository.saveReviewBarrier(new com.writing.novel.domain.ReviewBarrier(
                            projectId, chapterIndex, chapter.getVersion(),
                            "根源性矛盾待人工：" + issue.getDescription()));
                    EventLog.emit("REVIEW_BARRIER_RAISED", Map.of(
                            "projectId", projectId, "chapterIndex", chapterIndex,
                            "version", chapter.getVersion(),
                            "reason", EventLog.truncate(issue.getDescription(), 150)));
                    log.warn("章{} 标记 NEEDS_MANUAL_REVIEW（根源性矛盾待人工）：{}", chapterIndex, issue.getDescription());
                } else {
                    repository.findChapterByIndex(projectId, chapterIndex)
                            .ifPresent(chapter::adoptStateFrom);
                    log.warn("章{} 状态标记被版本门拒绝（已有更新稿），内存对象已同步最新稿", chapterIndex);
                }
            }
            case SUPERSEDE_CANON -> correctCanon(projectId, chapterIndex, issue);
            case REWRITE -> { /* 已重写，不再重写（最多 1 次） */ }
        }
    }


    /** p11-q0-04: 把矛盾纠正为真典 PLOT_COMMITMENT（待人工确认），让后续章节知晓该矛盾 */
    private void correctCanon(String projectId, int chapterIndex, ContinuityIssue issue) {
        if (orch.storyCanon() == null) return;
        try {
            orch.storyCanon().saveIfAbsent(new com.writing.novel.domain.StoryFact(projectId,
                    com.writing.novel.domain.StoryFact.FactType.PLOT_COMMITMENT,
                    "矛盾待确认(章" + chapterIndex + ")",
                    "【待人工确认】" + issue.getDescription(), chapterIndex));
        } catch (Exception e) {
            log.warn("真典纠正失败(章{}): {}", chapterIndex, e.getMessage());
        }
    }


    /** 收集设定摘要供连续性检查参考。p11-q0-01: 经 RelevanceGateway 按本章 cast 收窄角色 */
    private String collectSettingsForCheck(String projectId, List<String> cast) {
        StringBuilder sb = new StringBuilder();
        var characters = orch.relevanceGateway() != null
                ? orch.relevanceGateway().relevantCharacters(projectId, cast, 10)
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
        if (orch.relevanceGateway() != null) {
            var facts = orch.relevanceGateway().relevantFacts(projectId, cast, 15);
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

    /** 按章号找梗概（与 Orchestrator.findSynopsis 同语义的生产侧副本） */
    private static ChapterSynopsis findSynopsis(Outline outline, int chapterIndex) {
        if (outline == null) return null;
        for (Volume v : outline.getVolumes()) {
            for (ChapterSynopsis s : v.getSynopses()) {
                if (s.getChapterIndex() == chapterIndex) return s;
            }
        }
        return null;
    }
}
