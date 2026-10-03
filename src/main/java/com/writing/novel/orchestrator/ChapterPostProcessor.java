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
 *   <li>必需的派生数据失败保留未完成账本，交由已有续跑入口恢复</li>
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
    /** NCS-122: 关系双轨交叉校验（可选；null 跳过） */
    private final com.writing.novel.service.RelationshipConsistencyService relationshipConsistencyService;
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
                         com.writing.novel.service.RelationshipConsistencyService relationshipConsistencyService,
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
        this.relationshipConsistencyService = relationshipConsistencyService;
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
        // NCS-132: 独立提取器扇出组——ENTITY_GRAPH / NARRATIVE_THREADS / FORESHADOWING 互不依赖
        // （写不同的表；WAL+busy_timeout 保证 SQLite 写串行安全），并行执行缩短单章后处理墙钟。
        // FACT_HARVEST 依赖实体规范名，保持在其后串行；ledger/失败语义逐阶段不变。
        java.util.Set<Stage> fanout = java.util.Set.of(Stage.ENTITY_GRAPH, Stage.NARRATIVE_THREADS,
                Stage.FORESHADOWING);
        java.util.List<FanoutTask> fanoutFutures = new java.util.ArrayList<>();
        for (Stage stage : Stage.values()) {
            if (only != null && !only.contains(stage)) continue;
            if (fanout.contains(stage) && (only == null || only.size() > 1)) {
                final Stage st = stage;
                fanoutFutures.add(new FanoutTask(st, FANOUT_EXECUTOR.submit(() ->
                        runSingleStage(st, projectId, chapter, chapterIndex, synopsis, outline, false))));
                continue;
            }
            // FACT_HARVEST 依赖实体规范名——必须等扇出组完成；其余串行阶段不阻塞扇出（继续并行）
            if (!fanoutFutures.isEmpty() && stage == Stage.FACT_HARVEST) {
                awaitFanout(projectId, chapterIndex, fanoutFutures);
                fanoutFutures.clear();
            }
            var result = runSingleStage(stage, projectId, chapter, chapterIndex, synopsis, outline,
                    rewrittenByContinuity);
            if (result instanceof Boolean b && stage == Stage.CONTINUITY) {
                rewrittenByContinuity = b;
            }
        }
        awaitFanout(projectId, chapterIndex, fanoutFutures);
        return chapter;
    }

    /**
     * NCS-132: 等待扇出组。阶段级业务失败已在线程内按 ledger 语义处理；
     * 但 Error（模拟进程死亡/严重故障）必须逃逸到调用方——不被线程边界吞掉。
     *
     * <p>NCS-173: 每个扇出任务限时等待（{@link #fanoutTimeoutMillis()}，默认 600s，可经
     * {@code novel.postprocess.fanout-timeout-seconds} 配置）。超时即 cancel 并抛待恢复异常——
     * 防止上游 LLM 停滞（流式读阻塞）时整个写作循环无限冻结、任务长期持有项目工作权。
     * 停滞阶段不入完成账（重启续跑补齐），健康阶段正常完成不受拖累。
     */
    private void awaitFanout(String projectId, int chapterIndex, java.util.List<FanoutTask> tasks) {
        java.util.List<String> timedOut = new java.util.ArrayList<>();
        for (var task : tasks) {
            try {
                task.future().get(fanoutTimeoutMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException te) {
                task.future().cancel(true);
                awaitCancelledQuietly(task.future());
                // 账本以 COMPLETED 为终态：若阶段线程在中断后实际完成了工作（账本已 COMPLETED），
                // 结果有效、按完成记账不判失败；只有确实未完成的阶段才进入超时失败——
                // 保证"超时"只针对真实停滞，不误伤慢而完成的健康阶段
                var status = ledger == null ? null
                        : ledger.statusOf(projectId, chapterIndex, task.stage()).orElse(null);
                if (status == com.writing.novel.persistence.sqlite.PostProcessLedgerRepository.StageStatus.COMPLETED) {
                    log.info("后处理扇出阶段超时但已完成(章{}, {})——按完成记账", chapterIndex, task.stage());
                    continue;
                }
                timedOut.add(task.stage().name());
                String reason = "扇出阶段超过 " + (fanoutTimeoutMillis() / 1000) + "s 未完成，已放弃待恢复";
                com.writing.novel.telemetry.EventLog.emit("POST_PROCESS_STAGE_TIMEOUT", java.util.Map.of(
                        "projectId", projectId, "chapterIndex", chapterIndex,
                        "stage", task.stage().name(),
                        "reason", com.writing.novel.telemetry.EventLog.truncate(reason, 200)));
                log.warn("后处理扇出阶段超时(章{}, {}): {}", chapterIndex, task.stage(), reason);
            } catch (java.util.concurrent.ExecutionException e) {
                if (e.getCause() instanceof Error err) throw err;
                if (e.getCause() instanceof RuntimeException re) throw re;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("后处理扇出等待被中断", ie);
            }
        }
        if (!timedOut.isEmpty()) {
            throw new IllegalStateException(
                    "后处理扇出阶段超时(" + String.join(",", timedOut) + ")，待恢复");
        }
    }

    /** NCS-173: 扇出等待记录——阶段名用于超时事件定位与待恢复语义 */
    private record FanoutTask(Stage stage, java.util.concurrent.Future<?> future) {}

    /** NCS-173: cancel 后短暂等待阶段线程终止（中断响应通常毫秒级；真不可中断则限时放弃） */
    private static void awaitCancelledQuietly(java.util.concurrent.Future<?> future) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        while (!future.isDone() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** NCS-173: 扇出阶段时限（毫秒）——{@code novel.postprocess.fanout-timeout-seconds}，默认 600s */
    static long fanoutTimeoutMillis() {
        long seconds = Long.getLong("novel.postprocess.fanout-timeout-seconds", 600);
        return Math.max(1, seconds) * 1000L;
    }

    /** 单阶段执行（含 ledger 与失败语义）——串行/并行共用 */
    private Object runSingleStage(Stage stage, String projectId, Chapter chapter, int chapterIndex,
                                  ChapterSynopsis synopsis, Outline outline, boolean rewrittenByContinuity) {
        {
            if (ledger != null) ledger.markRunning(projectId, chapterIndex, stage);
            try {
            Object stageResult = null;
            switch (stage) {
                case CONTINUITY -> stageResult =
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
            return stageResult;
            } catch (com.writing.novel.agent.util.PostProcessStageFailure stageFailure) {
                // NCS-118: 阶段终态失败——UNKNOWN 事件 + ledger 不标 COMPLETED（重启续跑补齐），
                // 不中断其余阶段
                com.writing.novel.telemetry.EventLog.emit("POST_PROCESS_STAGE_UNKNOWN", java.util.Map.of(
                        "projectId", projectId, "chapterIndex", chapterIndex,
                        "stage", stage.name(), "detail", stageFailure.stage(),
                        "reason", com.writing.novel.telemetry.EventLog.truncate(stageFailure.getMessage(), 200)));
                log.warn("后处理阶段失败保持未完成(章{}, {}): {}",
                        chapterIndex, stage, stageFailure.getMessage());
            }
            return null;
        }
    }

    /** NCS-132: 扇出线程池（守护线程）——独立提取器并行。
     *  NCS-173: 用缓存池而非定长池——被超时放弃的停滞线程（HTTP 读阻塞直至客户端超时）
     *  不再占坑排队，后续章节的扇出任务可即时获得新线程，避免级联超时。 */
    private static final java.util.concurrent.ExecutorService FANOUT_EXECUTOR =
            new java.util.concurrent.ThreadPoolExecutor(0, Integer.MAX_VALUE,
                    60L, java.util.concurrent.TimeUnit.SECONDS,
                    new java.util.concurrent.SynchronousQueue<>(),
                    r -> {
                        Thread t = new Thread(r, "postprocess-fanout");
                        t.setDaemon(true);
                        return t;
                    });



    /** 一致性检查 + 闭环修复（可能触发重写/真典纠正/NEEDS_MANUAL_REVIEW） */
    private boolean stageContinuity(String projectId, int chapterIndex, Chapter chapter,
                                    ChapterSynopsis synopsis) {
        // p10-w1-07: 返回是否已触发 continuity 重写——若已重写，reader review 不再重写（互斥，防成本翻倍）
        return orch.production().qualityGate(projectId, chapterIndex, chapter,
                NovelOrchestrator.parseCast(synopsis != null ? synopsis.getCast() : ""));
    }

    /** 图数据库：LLM 提取本章实体和关系 */
    private void stageEntityGraph(String projectId, Chapter chapter, int chapterIndex) {
        if (entityGraphService == null) return;
        try {
            var result = entityGraphService.extractFromChapter(projectId, chapter);
            if (result == null || (result.error() != null && !result.error().isBlank())) {
                throw new IllegalStateException(result == null ? "实体提取没有返回结果" : result.error());
            }
        } catch (Exception e) {
            log.warn("实体提取失败(章{}): {}", chapterIndex, e.getMessage());
            com.writing.novel.telemetry.EventLog.emit("LLM_ERROR", Map.of(
                    "projectId", projectId, "chapterIndex", chapterIndex,
                    "taskType", "EXTRACTION", "phase", "entity-extraction",
                    "errorClass", e.getClass().getSimpleName(),
                    "message", com.writing.novel.telemetry.EventLog.truncate(e.getMessage(), 200)));
            throw new IllegalStateException("实体提取失败，第" + chapterIndex + "章待恢复", e);
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
            throw new IllegalStateException("真典收割失败，第" + chapterIndex + "章待恢复", e);
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
        // Reader review and its rewrite/recheck now belong to ChapterProduction's unified gate.
        // Recover older ledgers if they have no current review; never resample a current report.
        orch.production().completeReaderReview(projectId, chapterIndex, chapter);
    }

    /** p5-c2: 伏笔动态提取——从正文中识别新埋下的伏笔 */
    private void stageForeshadowing(String projectId, Chapter chapter, int chapterIndex) {
        try {
            foreshadowingExtractor.extract(projectId, chapter);
        } catch (com.writing.novel.agent.util.PostProcessStageFailure e) {
            throw e;   // NCS-118: ledger 不标 COMPLETED
        } catch (Exception e) {
            throw new com.writing.novel.agent.util.PostProcessStageFailure(
                    "foreshadow-extract", "伏笔提取失败: " + e.getMessage(), e);
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
        // NCS-119: 阶段失败上抛（ledger 不标 COMPLETED，POST_PROCESS_STAGE_UNKNOWN 可见）
        try {
            if (relationshipEvolutionTracker != null) {
                String castStr = synopsis != null ? synopsis.getCast() : "";
                int changes = relationshipEvolutionTracker.detectAndApply(
                        projectId, chapterIndex, chapter.getContent(), castStr);
                if (changes > 0) {
                    log.info("关系演变(章{}): {} 条变化", chapterIndex, changes);
                }
            }
        } catch (com.writing.novel.agent.util.PostProcessStageFailure e) {
            throw e;
        } catch (Exception e) {
            throw new com.writing.novel.agent.util.PostProcessStageFailure("relationship-evolution", "关系演变追踪失败: " + e.getMessage(), e);
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
        } catch (com.writing.novel.agent.util.PostProcessStageFailure e) {
            throw e;
        } catch (Exception e) {
            throw new com.writing.novel.agent.util.PostProcessStageFailure("plot-motif", "桥段追踪失败: " + e.getMessage(), e);
        }

        // NCS-122: 关系双轨交叉校验（纯规则，零 LLM）——边表 vs 真典关系事实矛盾信号
        try {
            if (relationshipConsistencyService != null) {
                for (var issue : relationshipConsistencyService.crossCheck(projectId, chapterIndex)) {
                    repository.saveContinuityIssue(issue);
                    log.warn("关系双轨(章{}): {}", chapterIndex, issue.getDescription());
                }
            }
        } catch (Exception e) {
            log.warn("关系双轨校验失败(章{}): {}", chapterIndex, e.getMessage());
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
