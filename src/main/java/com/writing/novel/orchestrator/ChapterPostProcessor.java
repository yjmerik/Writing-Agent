package com.writing.novel.orchestrator;

import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.ChapterSynopsis;
import com.writing.novel.domain.ContinuityIssue;
import com.writing.novel.domain.Outline;
import com.writing.novel.spi.NovelRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * p20-02: 章节后处理模块——正文落库（status=POST_PROCESSING）之后的全部加工阶段
 * 经由此模块按固定顺序执行，Orchestrator 不再内联编排细节。
 *
 * <p>阶段顺序（保持与原 doWriteOneChapter 内联实现逐行等价）：
 * CONTINUITY → ENTITY_GRAPH → FACT_HARVEST → NARRATIVE_THREADS → READER_REVIEW →
 * FORESHADOWING → DIVERSITY → ARC_TRACKING → PERIODIC → VOLUME_SUMMARY。
 *
 * <p>约定：
 * <ul>
 *   <li>所有阶段失败不中断写作（阶段内 try/catch 记日志），与旧行为一致</li>
 *   <li>模块不推进项目状态、不改章节 status——POST_PROCESSING→DONE 的裁决留在 Orchestrator</li>
 *   <li>p20-01: CONTINUITY 阶段可能产出 NEEDS_MANUAL_REVIEW，模块原样返回章节由上游裁决</li>
 * </ul>
 */
public class ChapterPostProcessor {

    /**
     * p20-03: 后处理阶段清单——声明顺序即执行顺序/续跑顺序。
     * 每阶段在 ledger 中独立记录 PENDING/RUNNING/COMPLETED（重启续跑用）。
     */
    public enum Stage {
        CONTINUITY, ENTITY_GRAPH, FACT_HARVEST, NARRATIVE_THREADS, READER_REVIEW,
        FORESHADOWING, DIVERSITY, ARC_TRACKING, PERIODIC, VOLUME_SUMMARY
    }

    private static final Logger log = LoggerFactory.getLogger(ChapterPostProcessor.class);

    private final NovelOrchestrator orch;
    private final NovelRepository repository;
    private final com.writing.novel.agent.ChapterWriterAgent chapterWriter;
    private final com.writing.novel.service.EntityGraphService entityGraphService;
    private final com.writing.novel.agent.NarrativeThreadExtractor narrativeThreadExtractor;
    private final com.writing.novel.agent.ReaderReviewAgent readerReviewAgent;
    private final com.writing.novel.agent.ForeshadowingExtractor foreshadowingExtractor;
    private final com.writing.novel.service.ClimaxDiversityTracker climaxDiversityTracker;
    private final com.writing.novel.service.DiversityTracker diversityTracker;
    private final com.writing.novel.agent.SceneClassifier sceneClassifier;
    private final com.writing.novel.service.ArcScheduler arcScheduler;
    private final com.writing.novel.agent.RelationshipEvolutionTracker relationshipEvolutionTracker;
    private final com.writing.novel.agent.PlotMotifTracker plotMotifTracker;
    private final com.writing.novel.service.PlotProgressionDetector plotProgressionDetector;
    private final com.writing.novel.agent.CharacterProfileUpgrader characterProfileUpgrader;
    private final com.writing.novel.agent.ArcUpdater arcUpdater;
    /** p20-04/p22-05: 阶段账本——组合期显式给定；null 时不记账（仅测试） */
    private final com.writing.novel.persistence.sqlite.PostProcessLedgerRepository ledger;

    ChapterPostProcessor(NovelOrchestrator orch,
                         NovelRepository repository,
                         com.writing.novel.agent.ChapterWriterAgent chapterWriter,
                         com.writing.novel.service.EntityGraphService entityGraphService,
                         com.writing.novel.agent.NarrativeThreadExtractor narrativeThreadExtractor,
                         com.writing.novel.agent.ReaderReviewAgent readerReviewAgent,
                         com.writing.novel.agent.ForeshadowingExtractor foreshadowingExtractor,
                         com.writing.novel.service.ClimaxDiversityTracker climaxDiversityTracker,
                         com.writing.novel.service.DiversityTracker diversityTracker,
                         com.writing.novel.agent.SceneClassifier sceneClassifier,
                         com.writing.novel.service.ArcScheduler arcScheduler,
                         com.writing.novel.agent.RelationshipEvolutionTracker relationshipEvolutionTracker,
                         com.writing.novel.agent.PlotMotifTracker plotMotifTracker,
                         com.writing.novel.service.PlotProgressionDetector plotProgressionDetector,
                         com.writing.novel.agent.CharacterProfileUpgrader characterProfileUpgrader,
                         com.writing.novel.agent.ArcUpdater arcUpdater,
                         com.writing.novel.persistence.sqlite.PostProcessLedgerRepository ledger) {
        this.orch = orch;
        this.repository = repository;
        this.chapterWriter = chapterWriter;
        this.entityGraphService = entityGraphService;
        this.narrativeThreadExtractor = narrativeThreadExtractor;
        this.readerReviewAgent = readerReviewAgent;
        this.foreshadowingExtractor = foreshadowingExtractor;
        this.climaxDiversityTracker = climaxDiversityTracker;
        this.diversityTracker = diversityTracker;
        this.sceneClassifier = sceneClassifier;
        this.arcScheduler = arcScheduler;
        this.relationshipEvolutionTracker = relationshipEvolutionTracker;
        this.plotMotifTracker = plotMotifTracker;
        this.plotProgressionDetector = plotProgressionDetector;
        this.characterProfileUpgrader = characterProfileUpgrader;
        this.arcUpdater = arcUpdater;
        this.ledger = ledger;
    }

    /** 按固定顺序执行全部后处理阶段，返回（可能被重写过的）章节 */
    public Chapter process(String projectId, Chapter chapter, int chapterIndex,
                           ChapterSynopsis synopsis, Outline outline) {
        // p20-03: 建账（无行 PENDING），逐阶段 RUNNING→COMPLETED
        if (ledger != null) {
            for (Stage stage : Stage.values()) {
                ledger.ensureStage(projectId, chapterIndex, stage);
            }
        }
        return run(projectId, chapter, chapterIndex, synopsis, outline, null);
    }

    /**
     * p20-04: 重启续跑——只执行 ledger 中未完成（RUNNING/PENDING）的阶段。
     * 无账本（legacy 章节或 ledger 未接）时不做任何事。
     */
    public Chapter resume(String projectId, Chapter chapter, int chapterIndex,
                          ChapterSynopsis synopsis, Outline outline) {
        if (ledger == null) return chapter;
        return run(projectId, chapter, chapterIndex, synopsis, outline,
                ledger.unfinishedStages(projectId, chapterIndex));
    }

    private Chapter run(String projectId, Chapter chapter, int chapterIndex,
                        ChapterSynopsis synopsis, Outline outline, java.util.List<Stage> only) {
        // NCS-001: 后处理入口同样受写入门禁——取消/接手后旧任务的投影/派生写入不得继续落库
        var lease = orch.writeLeaseRef();
        if (lease != null) {
            lease.guardAgentWrite(projectId,
                    repository.findActiveWritingTask(projectId).orElse(null),
                    "post-process:" + chapterIndex);
        }
        boolean rewrittenByContinuity = false;
        for (Stage stage : Stage.values()) {
            if (only != null && !only.contains(stage)) continue;
            if (ledger != null) ledger.markRunning(projectId, chapterIndex, stage);
            switch (stage) {
                case CONTINUITY -> rewrittenByContinuity =
                        stageContinuity(projectId, chapterIndex, chapter, synopsis);
                case ENTITY_GRAPH -> stageEntityGraph(projectId, chapter, chapterIndex);
                case FACT_HARVEST -> stageFactHarvest(projectId, chapter, chapterIndex);
                case NARRATIVE_THREADS -> stageNarrativeThreads(projectId, chapter, chapterIndex);
                case READER_REVIEW -> stageReaderReview(projectId, chapter, chapterIndex,
                        synopsis, outline, rewrittenByContinuity);
                case FORESHADOWING -> stageForeshadowing(projectId, chapter, chapterIndex);
                case DIVERSITY -> stageDiversity(projectId, chapter, chapterIndex, synopsis);
                case ARC_TRACKING -> stageArcTracking(projectId, chapter, chapterIndex, synopsis);
                case PERIODIC -> stagePeriodic(projectId, chapter, chapterIndex);
                case VOLUME_SUMMARY -> stageVolumeSummary(projectId, outline, chapterIndex);
            }
            if (ledger != null) ledger.markCompleted(projectId, chapterIndex, stage);
        }
        return chapter;
    }



    /** 一致性检查 + 闭环修复（可能触发重写/真典纠正/NEEDS_MANUAL_REVIEW） */
    private boolean stageContinuity(String projectId, int chapterIndex, Chapter chapter,
                                    ChapterSynopsis synopsis) {
        // p10-w1-07: 返回是否已触发 continuity 重写——若已重写，reader review 不再重写（互斥，防成本翻倍）
        return orch.production().qualityGate(projectId, chapterIndex, chapter,
                NovelOrchestrator.parseCast(synopsis != null ? synopsis.getCast() : ""));
    }

    /** 图数据库：LLM 提取本章实体和关系 */
    private void stageEntityGraph(String projectId, Chapter chapter, int chapterIndex) {
        try {
            entityGraphService.extractFromChapter(projectId, chapter);
        } catch (Exception e) {
            log.warn("实体提取失败(章{}): {}", chapterIndex, e.getMessage());
        }
    }

    /** p10-w2-03: 故事真典增量收割——从本章提取事实变更入典（长程记忆，替代级联摘要） */
    private void stageFactHarvest(String projectId, Chapter chapter, int chapterIndex) {
        try {
            var factHarvester = orch.factHarvester();
            if (factHarvester != null) {
                var harvestResult = factHarvester.harvest(projectId, chapter);
                if (harvestResult.total() > 0) {
                    log.info("真典收割(章{}): +{} 新增 / {} 刷新 / {} 取代",
                            chapterIndex, harvestResult.added(), harvestResult.validated(),
                            harvestResult.superseded());
                }
            }
        } catch (Exception e) {
            log.warn("真典收割失败(章{}): {}", chapterIndex, e.getMessage());
        }
    }

    /** p8-2: 叙事线索提取与追踪 */
    private void stageNarrativeThreads(String projectId, Chapter chapter, int chapterIndex) {
        try {
            if (narrativeThreadExtractor != null) {
                narrativeThreadExtractor.extractAndUpdate(projectId, chapterIndex, chapter.getContent());
            }
        } catch (Exception e) {
            log.warn("叙事线索提取失败(章{}): {}", chapterIndex, e.getMessage());
        }
    }

    /**
     * p8-3: 读者检验 Agentic Loop——每章写后检验，低分自动重写（最多1次）。
     * p9-1: 重写长度保护——拒绝比原版短70%的重写。p11-I1: 评价结果持久化。
     */
    private void stageReaderReview(String projectId, Chapter chapter, int chapterIndex,
                                   ChapterSynopsis synopsis, Outline outline,
                                   boolean rewrittenByContinuity) {
        if (readerReviewAgent == null || orch.hasArbiter()) {
            return;
        }
        try {
            var review = readerReviewAgent.review(chapterIndex, chapter.getContent());
            log.info("读者检验(章{}): {}/10 — {}", chapterIndex, review.getScore(), review.getSummary());
            // p11-q3-01: 质量事件遥测——读者检验评分可观测
            com.writing.novel.telemetry.EventLog.emit("QUALITY_REVIEW", Map.of("score", review.getScore(),
                    "passed", review.isPassed(),
                    "summary", com.writing.novel.telemetry.EventLog.truncate(review.getSummary(), 150)));
            // p10-w1-07: continuity 已重写则互斥跳过（单章最多 1 次重写，避免最坏情况成本翻倍）
            if (!rewrittenByContinuity && !review.isPassed()) {
                log.info("章节评分 {} < {}，自动重写", review.getScore(), readerReviewAgent.getPassThreshold());
                var reviewSynopsis = orch.findSynopsis(outline, chapterIndex);
                int originalLength = chapter.getContent().length();
                // p32-02: LLM 重写前记录版本基线——处理期间作者保存新稿时拒绝覆盖
                int revisionBase = chapter.getVersion();
                String newContent = chapterWriter.rewriteForCorrection(
                        projectId, chapterIndex, chapter.getTitle(),
                        reviewSynopsis != null ? reviewSynopsis.getSynopsis() : "",
                        reviewSynopsis != null ? reviewSynopsis.getWritingNotes() : "",
                        "读者检验改进建议：" + review.getImprovementSuggestion());
                // p9-1: 长度保护——重写不能比原版短太多（≥70%）
                if (newContent != null && newContent.length() >= originalLength * 0.7) {
                    if (orch.autoRewriteIfCurrent(projectId, chapterIndex, revisionBase,
                            chapter.getTitle(), newContent, "REWRITE")) {
                        // p33-05: 以 DB 权威状态回填（修订边界可能幂等跳过版本自增）
                        repository.findChapterByIndex(projectId, chapterIndex)
                                .ifPresent(chapter::adoptStateFrom);
                        log.info("章节重写完成(章{}): {}字→{}字", chapterIndex, originalLength, newContent.length());
                        var reReview = readerReviewAgent.review(chapterIndex, chapter.getContent());
                        log.info("重写后检验(章{}): {}/10", chapterIndex, reReview.getScore());
                        // p11-I1: 持久化重写后的最终评价
                        review = reReview;
                        // p11-q3-01: 质量事件遥测——重写决策可观测
                        com.writing.novel.telemetry.EventLog.emit("REWRITE_TRIGGERED", Map.of(
                                "reason", "reader_review", "afterScore", reReview.getScore()));
                    } else {
                        repository.findChapterByIndex(projectId, chapterIndex)
                                .ifPresent(chapter::adoptStateFrom);
                        log.warn("章{}: 自动重写遇作者保存新稿，弃用重写稿并同步内存至作者稿", chapterIndex);
                    }
                } else if (newContent != null) {
                    log.warn("重写内容过短({}字 < 原版{}字的70%)，保留原版", newContent.length(), originalLength);
                    // p16-05: 拒收重写——rewriteForCorrection 已把向量切到被拒版本，
                    // 用保留的原正文重放向量索引，恢复向量库与 DB 一致
                    try { chapterWriter.reindexChapter(projectId, chapterIndex, chapter.getContent()); }
                    catch (Exception re) {
                        log.warn("拒收重写后向量重放失败(章{}): {}", chapterIndex, re.getMessage());
                    }
                }
            }
            // p11-I1: 持久化章节评价报告
            try {
                repository.saveChapterReview(
                        com.writing.novel.domain.ChapterReview.from(projectId, review));
                orch.enrichChapterCheckpointPayload(projectId, review.getChapterIndex());
            } catch (Exception pe) {
                log.warn("章节评价持久化失败(章{}): {}", chapterIndex, pe.getMessage());
            }
        } catch (Exception e) {
            log.warn("读者检验失败(章{}): {}", chapterIndex, e.getMessage());
        }
    }

    /** p5-c2: 伏笔动态提取——从正文中识别新埋下的伏笔 */
    private void stageForeshadowing(String projectId, Chapter chapter, int chapterIndex) {
        try {
            foreshadowingExtractor.extract(projectId, chapter);
        } catch (Exception e) {
            log.warn("伏笔提取失败(章{}): {}", chapterIndex, e.getMessage());
        }
    }

    /** 爽点/场景多样性追踪（p8/p10-b2）+ 持久化（p10-w3-06） */
    private void stageDiversity(String projectId, Chapter chapter, int chapterIndex,
                                ChapterSynopsis synopsis) {
        try {
            var climaxTypes = orch.inferClimaxTypes(synopsis);
            if (!climaxTypes.isEmpty()) {
                climaxDiversityTracker.record(chapterIndex, climaxTypes);
                // p10-b2: 激活 DiversityTracker 的 climax 维度（修复死代码）
                diversityTracker.record("climax", chapterIndex,
                        climaxTypes.stream().map(Enum::name).toList());
            }
        } catch (Exception e) {
            log.warn("爽点追踪失败(章{}): {}", chapterIndex, e.getMessage());
        }

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
    }

    /** 角色出场/关系演变/桥段/剧情停滞追踪（p10-a2/p11-H3/p11.1-J2） */
    private void stageArcTracking(String projectId, Chapter chapter, int chapterIndex,
                                  ChapterSynopsis synopsis) {
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
                        log.warn("剧情停滞警告(章{}): {}", chapterIndex, issue.getDescription());
                    });
        } catch (Exception e) {
            log.warn("停滞检测失败(章{}): {}", chapterIndex, e.getMessage());
        }
    }

    /** 周期性加工：角色档案升级（每5章）/ 弧线更新（每10章）/ 滚动摘要（每3章） */
    private void stagePeriodic(String projectId, Chapter chapter, int chapterIndex) {
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
            orch.generateSummary(projectId, chapterIndex - 2, chapterIndex);
        }
    }

    /** 卷结束时生成卷级全局摘要（递归摘要） */
    private void stageVolumeSummary(String projectId, Outline outline, int chapterIndex) {
        orch.checkAndGenerateVolumeSummary(projectId, outline, chapterIndex);
    }
}
