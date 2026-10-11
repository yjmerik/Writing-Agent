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
import com.writing.novel.spi.NovelRepository;
import java.util.ArrayList;
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

    /** NCS-022: 审美档案注入函数（projectId → 注入文本；空=无档案不注入）——late-set */
    private volatile java.util.function.Function<String, String> aestheticInjection;

    public void setAestheticInjection(java.util.function.Function<String, String> injection) {
        this.aestheticInjection = injection;
    }

    /** NCS-193: 写前场景规划服务（late-set，null=能力关闭安全降级） */
    private volatile com.writing.novel.service.ScenePlanService scenePlanService;

    public void setScenePlanService(com.writing.novel.service.ScenePlanService service) {
        this.scenePlanService = service;
    }

    /** NCS-195: 叙事策略解析（late-set，null=门禁关闭） */
    private volatile com.writing.novel.service.NarrativePolicyService texturePolicyService;

    public void setTexturePolicyService(com.writing.novel.service.NarrativePolicyService service) {
        this.texturePolicyService = service;
    }

    /** NCS-196: 质感定点扩写编辑器（late-set；null=跳过并事件化） */
    private volatile com.writing.novel.agent.PassageEditor texturePassageEditor;

    public void setTexturePassageEditor(com.writing.novel.agent.PassageEditor editor) {
        this.texturePassageEditor = editor;
    }

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
        // NCS-018: 场景合同条件注入——仅当梗概携带合同时追加【场景合同】段
        // （无合同的既有大纲注入不变，写作管线指纹稳定）
        if (synopsis.getSceneContract() != null && !synopsis.getSceneContract().isBlank()) {
            enhancedNotes = (enhancedNotes != null ? enhancedNotes + "\n\n" : "")
                    + "【场景合同（本章必须成立的因果约束）】\n" + synopsis.getSceneContract();
        }
        // NCS-022: 审美档案条件注入——仅有档案条目时追加（空档案注入不变）
        if (aestheticInjection != null) {
            String aesthetic = aestheticInjection.apply(projectId);
            if (aesthetic != null && !aesthetic.isBlank()) {
                enhancedNotes = (enhancedNotes != null ? enhancedNotes + "\n\n" : "") + aesthetic;
            }
        }
        // NCS-193: 写前场景规划——产出并落梗概（幂等）；NCS-194: 渲染场景预算进写作任务
        if (scenePlanService != null) {
            try {
                String plan = scenePlanService.ensurePlanned(projectId, chapterIndex);
                String guide = scenePlanService.renderWritingGuide(plan);
                if (guide != null && !guide.isBlank()) {
                    enhancedNotes = (enhancedNotes != null ? enhancedNotes + "\n\n" : "") + guide;
                }
            } catch (Exception e) {
                EventLog.emit("SCENE_PLAN_FAILED", Map.of(
                        "chapterIndex", chapterIndex, "error", String.valueOf(e.getMessage())));
            }
        }
        return enhancedNotes == null ? "" : enhancedNotes;
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
        // NCS-187: 纹理指标遥测（observe-only）——不阻断、不触发重写；TextureGate（后续 feature）消费
        Map<String, Object> texture = com.writing.novel.service.StyleMetricsService.computeTexture(chapter.getContent());
        texture.put("chapterIndex", chapterIndex);
        texture.put("wordCount", chapter.getWordCount());
        EventLog.emit("TEXTURE_METRICS", texture);
        return chapter;
    }

    // ---------------- Quality→Rewrite 质量链（自 NovelOrchestrator 原样迁移） ----------------


    /**
     * 写完一章后检查连续性。
     * 发现 HIGH 级矛盾时触发 1 次重写（把矛盾描述加入 prompt 要求修正）。
     * 每 5 章检查一次风格漂移。
     */
    boolean qualityGate(String projectId, int chapterIndex, Chapter chapter, List<String> cast) {
        boolean rewritten = false;
        List<ContinuityIssue> continuityIssuesRef;
        try {
            continuityIssuesRef = checkedContinuity(projectId, chapterIndex, chapter, cast);
        } catch (Exception e) {
            pauseForQuality(projectId, chapterIndex, chapter, "连续性检查未完成：" + e.getMessage());
            return false;
        }
        // p5-c1: 伏笔回收检测——本章是否回收了某个未回收伏笔
        try {
            checkForeshadowingResolution(projectId, chapterIndex, chapter);
        } catch (com.writing.novel.agent.util.PostProcessStageFailure e) {
            // NCS-118: 检测失败可见但不阻断章节（伏笔状态保持悬置待复检）
            EventLog.emit("POST_PROCESS_STAGE_UNKNOWN", Map.of(
                    "projectId", projectId, "chapterIndex", chapterIndex,
                    "stage", "FORESHADOWING_RESOLUTION", "detail", e.stage(),
                    "reason", EventLog.truncate(e.getMessage(), 200)));
            log.warn("伏笔回收检测失败(章{}): {}", chapterIndex, e.getMessage());
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

        List<ContinuityIssue> guardIssuesRef = List.of();
        try {
            if (consistencyGuard != null) {
                guardIssuesRef = consistencyGuard.check(projectId, chapterIndex, chapter.getContent(), cast);
                guardIssuesRef.forEach(repository::saveContinuityIssue);
            }
        } catch (Exception e) {
            pauseForQuality(projectId, chapterIndex, chapter, "一致性守卫未完成：" + e.getMessage());
            return false;
        }
        // NCS-100: 风格评分先于仲裁决策计算并传入委员会——低分在重写预算内触发风格重写
        Integer styleScore = styleGateScore(projectId, chapterIndex, chapter);
        rewritten = applyArbiterDecision(projectId, chapterIndex, chapter, cast,
                continuityIssuesRef, guardIssuesRef, false, styleScore);

        // p10-d2: 一致性门禁——章号/时代用语检查（纯规则）
        try {
            if (consistencyGate != null) {
                var p = repository.findProject(projectId).orElse(null);
                var gateIssues = consistencyGate.check(projectId, chapterIndex,
                        chapter.getContent(), p.getGenre());
                for (var issue : gateIssues) {
                    repository.saveContinuityIssue(issue);
                    log.warn("一致性门禁(章{}): {}", chapterIndex, issue.getDescription());
                    if (issue.getSeverity() == ContinuityIssue.Severity.HIGH)
                        pauseForQuality(projectId, chapterIndex, chapter, "规则检查发现严重问题：" + issue.getDescription());
                }
            }
        } catch (Exception e) {
            pauseForQuality(projectId, chapterIndex, chapter, "规则检查未完成：" + e.getMessage());
        }

        // p11.1-J4: 说明文密度检测已并入 TextureGate（NCS-195 反向检测），此处保留原行为兼容
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

        // NCS-195: 双向质感门禁——写后、读者评审前；重写已触发或门禁关闭时跳过
        rewritten = rewritten || applyTextureGate(projectId, chapterIndex, chapter, cast);

        return rewritten;
    }

    /** NCS-195: 双向质感门禁执行——越界落 issue + 分级路由（质感单项→定点扩写，结构性→整章重写） */
    private boolean applyTextureGate(String projectId, int chapterIndex, Chapter chapter, List<String> cast) {
        if (texturePolicyService == null) return false;
        try {
            var policy = texturePolicyService.resolve(projectId);
            var verdict = new com.writing.novel.service.TextureGate()
                    .check(projectId, chapterIndex, chapter.getContent(), policy);
            EventLog.emit("TEXTURE_GATE", Map.of(
                    "chapterIndex", chapterIndex,
                    "pass", verdict.pass(),
                    "issues", verdict.issues().size(),
                    "expansionTargets", verdict.expansionTargets().size(),
                    "rewriteTargets", verdict.rewriteTargets().size(),
                    "dialogueParagraphRatio", verdict.dialogueRatio(),
                    "dialoguePingPong", verdict.pingPongCount()));
            if (verdict.pass()) return false;
            verdict.issues().forEach(repository::saveContinuityIssue);
            verdict.issues().forEach(i -> log.warn("质感门禁(章{}): {}", chapterIndex, i.getDescription()));
            // 结构性越界 → 既有整章重写（纠偏指令携带具体质感目标）
            if (!verdict.rewriteTargets().isEmpty()) {
                String instruction = "质感修复（结构性）：本章 " + String.join("；", verdict.rewriteTargets())
                        + "。重写时逐项落实上述目标，其余情节事实不得改变。";
                return rewriteWithCorrection(projectId, chapterIndex, chapter, instruction, null);
            }
            // 质感单项 → 定点扩写通道（NCS-196）：挑最弱 1-2 场景段 EXPAND，复验通过才替换
            if (!verdict.expansionTargets().isEmpty()) {
                return expandForTexture(projectId, chapterIndex, chapter, cast,
                        verdict.expansionTargets());
            }
            return false;
        } catch (Exception e) {
            // 门禁是增强能力——解析/执行失败不阻断章节
            EventLog.emit("TEXTURE_GATE_FAILED", Map.of(
                    "chapterIndex", chapterIndex, "error", EventLog.truncate(String.valueOf(e.getMessage()), 200)));
            return false;
        }
    }

    /**
     * NCS-196: 定点扩写修复通道——按修复方向挑最弱 1-2 场景段调 PassageEditor EXPAND
     * （1.5-2 倍、感官细节/心理/动作、情节事实不变），扩写稿经 ConsistencyGuard 快速复验：
     * 无 HIGH 才替换（autoRewriteIfCurrent 版本化落库）；有 HIGH 回退原文并升级整章重写。
     *
     * @return 是否触发了整章重写（qualityGate 的 rewritten 语义）
     */
    boolean expandForTexture(String projectId, int chapterIndex, Chapter chapter,
                             List<String> cast, List<String> targets) {
        if (texturePassageEditor == null) {
            EventLog.emit("TEXTURE_EXPANSION_SKIPPED", Map.of("chapterIndex", chapterIndex,
                    "reason", "passageEditor 未接线"));
            return false;
        }
        String content = chapter.getContent();
        List<String> segments = sceneSegments(content);
        if (segments.size() < 2) {
            EventLog.emit("TEXTURE_EXPANSION_SKIPPED", Map.of("chapterIndex", chapterIndex,
                    "reason", "无可识别的场景分段"));
            return false;
        }
        boolean dialogueOverrun = targets.stream().anyMatch(t -> t.contains("超出区间上限"));
        // 最弱优先：对白超标取对白密度最高的段，其余取对白密度最低（叙述最重）的段
        List<String> ranked = new ArrayList<>(segments);
        ranked.sort(java.util.Comparator.comparingDouble(this::dialogueRatioOf));
        List<String> selected = dialogueOverrun
                ? List.of(ranked.get(ranked.size() - 1),
                  ranked.size() > 2 ? ranked.get(ranked.size() - 2) : ranked.get(0))
                : List.of(ranked.get(0), ranked.size() > 2 ? ranked.get(1) : ranked.get(ranked.size() - 1));
        String instruction = "定点扩写：将该场景段扩写至原文的 1.5-2 倍，补充感官细节（视觉/听觉/触觉/嗅觉）、"
                + "人物心理与微动作。本轮修复目标：" + String.join("；", targets)
                + "。硬约束：不得改变任何情节事实（人物、事件、时间、因果均保持不变），不得新增人物或事件。";

        String candidate = content;
        int expandedCount = 0;
        for (String segment : selected) {
            if (segment.equals(selected.get(0)) && expandedCount > 0) continue; // 去重（段少于3时可能重叠）
            String contextBefore = content.substring(0, Math.max(0, content.indexOf(segment)));
            String expanded = texturePassageEditor.editPassage(projectId, segment,
                    contextBefore.length() > 600 ? contextBefore.substring(contextBefore.length() - 600) : contextBefore,
                    "", instruction, "EXPAND");
            if (expanded == null || expanded.isBlank()) continue;
            int grown = expanded.length();
            if (grown < segment.length() || grown > segment.length() * 3) continue; // 不接受缩水或失控膨胀
            candidate = candidate.replace(segment, expanded);
            expandedCount++;
        }
        if (expandedCount == 0 || candidate.equals(content)) {
            EventLog.emit("TEXTURE_EXPANSION_SKIPPED", Map.of("chapterIndex", chapterIndex,
                    "reason", "扩写输出不可用"));
            return false;
        }

        // 快速复验：扩写稿引入 HIGH 矛盾（情节漂移）→ 回退原文并升级整章重写
        List<ContinuityIssue> recheck = consistencyGuard != null
                ? consistencyGuard.check(projectId, chapterIndex, candidate, cast) : List.of();
        boolean hasHigh = recheck.stream().anyMatch(i -> i.getSeverity() == ContinuityIssue.Severity.HIGH);
        if (hasHigh) {
            EventLog.emit("TEXTURE_EXPAND_REJECTED", Map.of("chapterIndex", chapterIndex,
                    "reason", EventLog.truncate(recheck.stream()
                            .filter(i -> i.getSeverity() == ContinuityIssue.Severity.HIGH)
                            .map(ContinuityIssue::getDescription).findFirst().orElse("HIGH"), 200)));
            return rewriteWithCorrection(projectId, chapterIndex, chapter,
                    "质感修复（定点扩写复验未过，升级整章重写）：" + String.join("；", targets), null);
        }
        int revisionBase = chapter.getVersion();
        orch.autoRewriteIfCurrent(projectId, chapterIndex, revisionBase,
                chapter.getTitle(), candidate, "TEXTURE_EXPAND");
        repository.findChapterByIndex(projectId, chapterIndex).ifPresent(chapter::adoptStateFrom);
        EventLog.emit("TEXTURE_EXPANDED", Map.of(
                "chapterIndex", chapterIndex, "expandedSegments", expandedCount));
        return false;
    }

    /** 按场景分隔行切分段落组（分隔行归属前一段落组尾） */
    static List<String> sceneSegments(String content) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            boolean separator = trimmed.length() >= 2 && !trimmed.isEmpty()
                    && trimmed.chars().allMatch(c -> "＊*—–·※ \t".indexOf(c) >= 0);
            current.append(line).append('\n');
            if (separator) {
                out.add(current.toString());
                current.setLength(0);
            }
        }
        if (!current.toString().isBlank()) out.add(current.toString());
        return out.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** 段内对白字符占比（「」/“”引号内字符 / 全部字符）——最弱场景排序用 */
    private double dialogueRatioOf(String segment) {
        int total = 0, quoted = 0;
        boolean inQuote = false;
        for (char c : segment.toCharArray()) {
            if (c == '「' || c == '“') { inQuote = true; continue; }
            if (c == '」' || c == '”') { inQuote = false; continue; }
            if (!Character.isWhitespace(c)) {
                total++;
                if (inQuote) quoted++;
            }
        }
        return total == 0 ? 0 : (double) quoted / total;
    }

    /**
     * NCS-100: 风格漂移评分（每 5 章一次，p3-02 节奏）——先于仲裁执行，
     * 低分（<60）落 ContinuityIssue 且评分进 QualityCouncil 触发预算内重写；
     * 评分解析失败发事件，不再静默跳过。
     */
    private Integer styleGateScore(String projectId, int chapterIndex, Chapter chapter) {
        if (chapterIndex % 5 != 0) return null;
        try {
            var styleOpt = repository.findStyleProfileByProject(projectId);
            if (styleOpt.isEmpty()) return null;
            var style = styleOpt.get();
            // NCS-128: 漂移检测基准 = 意图快照（锁定值优先），不是随正文演化的 state
            String baseline = "基调：" + style.intentTone()
                    + "，叙事：" + style.intentVoice()
                    + "，注意事项：" + style.intentDosAndDonts();
            String report = styleGuardian.checkConsistency(projectId, chapter.getContent(), baseline);
            if (report == null) {
                EventLog.emit("STYLE_SCORE_PARSE_FAILED", Map.of("projectId", projectId,
                        "chapterIndex", chapterIndex, "reason", "null_report"));
                return null;
            }
            var scoreMatch = java.util.regex.Pattern.compile("(\\d+)/100").matcher(report);
            if (!scoreMatch.find()) {
                EventLog.emit("STYLE_SCORE_PARSE_FAILED", Map.of("projectId", projectId,
                        "chapterIndex", chapterIndex,
                        "reason", EventLog.truncate("no_score_pattern: " + report, 200)));
                return null;
            }
            int score = Integer.parseInt(scoreMatch.group(1));
            if (score < 60) {
                repository.saveContinuityIssue(new ContinuityIssue(
                        projectId, chapterIndex,
                        ContinuityIssue.Type.STYLE,
                        ContinuityIssue.Severity.MEDIUM,
                        "风格漂移（评分" + score + "/100）：" + report));
            }
            return score;
        } catch (Exception e) {
            EventLog.emit("STYLE_SCORE_PARSE_FAILED", Map.of("projectId", projectId,
                    "chapterIndex", chapterIndex,
                    "reason", EventLog.truncate(String.valueOf(e.getMessage()), 200)));
            log.warn("风格检查失败: {}", e.getMessage());
            return null;
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
        } catch (com.writing.novel.agent.util.PostProcessStageFailure e) {
            // NCS-118: 检测失败可见但不阻断章节（伏笔状态保持悬置待复检）
            EventLog.emit("POST_PROCESS_STAGE_UNKNOWN", Map.of(
                    "projectId", projectId, "chapterIndex", chapterIndex,
                    "stage", "FORESHADOWING_RESOLUTION", "detail", e.stage(),
                    "reason", EventLog.truncate(e.getMessage(), 200)));
            log.warn("伏笔回收检测失败(章{}): {}", chapterIndex, e.getMessage());
        } catch (Exception e) {
            log.warn("伏笔回收检测失败(章{}): {}", chapterIndex, e.getMessage());
        }
    }


    /** 带纠偏指令的重写：原地更新原 chapter（避免重复章节） */
    private boolean rewriteWithCorrection(String projectId, int chapterIndex, Chapter chapter,
                                          String issueDesc, String settings) {
        try {
            var outline = repository.findOutlineByProject(projectId).orElse(null);
            if (outline == null) return false;
            var synopsis = findSynopsis(outline, chapterIndex);
            if (synopsis == null) return false;
            int revisionBase = chapter.getVersion();
            // NCS-194: 重写路径同步注入场景预算（与首写同一动线）
            String rewriteNotes = synopsis.getWritingNotes();
            if (scenePlanService != null) {
                String guide = scenePlanService.renderWritingGuide(synopsis.getScenePlan());
                if (guide != null && !guide.isBlank()) {
                    rewriteNotes = (rewriteNotes == null || rewriteNotes.isBlank()
                            ? guide : rewriteNotes + "\n\n" + guide);
                }
            }
            String newContent = chapterWriter.rewriteForCorrection(projectId, chapterIndex, synopsis.getTitle(),
                    synopsis.getSynopsis(), rewriteNotes, issueDesc);
            if (newContent == null || newContent.length() < Math.max(2000, chapter.getContent().length() * 0.7)) {
                if (newContent != null) chapterWriter.reindexChapter(projectId, chapterIndex, chapter.getContent());
                return false;
            }
            boolean applied = orch.autoRewriteIfCurrent(projectId, chapterIndex, revisionBase,
                    chapter.getTitle(), newContent, "REWRITE");
            repository.findChapterByIndex(projectId, chapterIndex).ifPresent(chapter::adoptStateFrom);
            return applied;
        } catch (Exception e) {
            log.warn("纠偏重写失败，保留原版本: {}", e.getMessage());
            return false;
        }
    }

    private List<ContinuityIssue> checkedContinuity(String projectId, int chapterIndex,
                                                   Chapter chapter, List<String> cast) {
        var result = continuityChecker.checkWithEnvelope(projectId, chapterIndex, chapter.getContent(),
                collectSettingsForCheck(projectId, chapterIndex, cast));
        if (result.status() != com.writing.novel.agent.CheckResultEnvelope.Status.SUCCESS
                && result.status() != com.writing.novel.agent.CheckResultEnvelope.Status.VALID_EMPTY)
            throw new IllegalStateException(result.reason());
        return result.issues();
    }

    private com.writing.novel.domain.ReviewReport checkedReaderReview(String projectId, int chapterIndex,
                                                                       Chapter chapter) {
        if (readerReviewAgent == null) return null;
        var review = readerReviewAgent.review(chapterIndex, chapter.getContent());
        if (review.getSummary() == null || review.getSummary().isBlank()
                || "检验解析失败".equals(review.getSummary()))
            throw new IllegalStateException("读者检查没有返回有效评价");
        repository.saveChapterReview(ChapterReview.from(projectId, review));
        orch.enrichChapterCheckpointPayload(projectId, chapterIndex);
        EventLog.emit("QUALITY_REVIEW", Map.of("projectId", projectId, "chapterIndex", chapterIndex,
                "version", chapter.getVersion(), "score", review.getScore(), "passed", review.isPassed()));
        return review;
    }

    void completeReaderReview(String projectId, int chapterIndex, Chapter chapter) {
        if (readerReviewAgent == null || chapter.getStatus() == Chapter.Status.NEEDS_MANUAL_REVIEW
                || repository.findChapterReview(projectId, chapterIndex)
                .filter(r -> r.getChapterVersion() != null && r.getChapterVersion() == chapter.getVersion()).isPresent()) return;
        try {
            var review = checkedReaderReview(projectId, chapterIndex, chapter);
            if (review != null && !review.isPassed())
                pauseForQuality(projectId, chapterIndex, chapter, "补跑读者检查未通过，待人工复核");
        } catch (Exception e) {
            pauseForQuality(projectId, chapterIndex, chapter, "补跑读者检查失败：" + e.getMessage());
        }
    }

    /** NCS-098: 高危问题三分类结果；rewrite=进入既有重写仲裁链，其余两类走各自出口 */
    record RepairPartition(List<ContinuityIssue> rewrite, List<ContinuityIssue> manualReview,
                           List<ContinuityIssue> canonEvolution) {}

    /** NCS-098: 激活 RepairArbiter 三分类——只有 HIGH 级参与路由，非 HIGH 全部进入 rewrite 桶（随 council 反馈） */
    static RepairPartition partitionByRepairAction(List<ContinuityIssue> issues, boolean alreadyRewritten) {
        com.writing.novel.service.RepairArbiter arbiter = new com.writing.novel.service.RepairArbiter();
        List<ContinuityIssue> rewrite = new java.util.ArrayList<>();
        List<ContinuityIssue> manual = new java.util.ArrayList<>();
        List<ContinuityIssue> evolution = new java.util.ArrayList<>();
        for (ContinuityIssue issue : issues) {
            if (issue.getSeverity() != ContinuityIssue.Severity.HIGH) {
                rewrite.add(issue);
                continue;
            }
            switch (arbiter.classify(issue, alreadyRewritten)) {
                case MANUAL_REVIEW -> manual.add(issue);
                case SUPERSEDE_CANON -> evolution.add(issue);
                default -> rewrite.add(issue);
            }
        }
        return new RepairPartition(rewrite, manual, evolution);
    }

    private boolean applyArbiterDecision(String projectId, int chapterIndex, Chapter chapter,
                                         List<String> cast, List<ContinuityIssue> continuityIssues,
                                         List<ContinuityIssue> guardIssues, boolean alreadyRewritten,
                                         Integer styleScore) {
        // NCS-098: 先按 RepairArbiter 三分类路由 continuity/guard 高危问题——
        // 跨章/根源性矛盾直通人工（不浪费重写预算），真典合法演进不改正文（事件留痕+issue 结清）
        var continuityPartition = partitionByRepairAction(continuityIssues, alreadyRewritten);
        var guardPartition = partitionByRepairAction(guardIssues, alreadyRewritten);
        List<ContinuityIssue> manualReview = new java.util.ArrayList<>(continuityPartition.manualReview());
        manualReview.addAll(guardPartition.manualReview());
        List<ContinuityIssue> canonEvolution = new java.util.ArrayList<>(continuityPartition.canonEvolution());
        canonEvolution.addAll(guardPartition.canonEvolution());
        if (!manualReview.isEmpty()) {
            pauseForQuality(projectId, chapterIndex, chapter,
                    "根源性/跨章矛盾待人工裁决（单章重写无法修复）：" + manualReview.get(0).getDescription());
            return false;
        }
        for (ContinuityIssue issue : canonEvolution) {
            issue.resolve();
            repository.saveContinuityIssue(issue);
            EventLog.emit("CONTRADICTION_ARBITRATED", Map.of("projectId", projectId,
                    "chapterIndex", chapterIndex, "version", chapter.getVersion(),
                    "issueId", issue.getId(), "action", "SUPERSEDE_CANON",
                    "description", EventLog.truncate(issue.getDescription(), 200)));
        }
        List<ContinuityIssue> effectiveIssues = continuityPartition.rewrite();
        List<ContinuityIssue> effectiveGuardIssues = guardPartition.rewrite();
        com.writing.novel.domain.ReviewReport review;
        try {
            review = checkedReaderReview(projectId, chapterIndex, chapter);
        } catch (Exception e) {
            pauseForQuality(projectId, chapterIndex, chapter, "读者检查未完成：" + e.getMessage());
            return false;
        }
        int readerOverall = review == null ? 10 : review.getScore();
        var council = (orch.qualityCouncil() != null ? orch.qualityCouncil() : new com.writing.novel.service.QualityCouncil())
                .collect(effectiveIssues, effectiveGuardIssues, readerOverall,
                        review == null ? null : review.getImprovementSuggestion(), styleScore);
        int budget = repository.findWritingPreferences(projectId)
                .map(com.writing.novel.domain.WritingPreferences::getRewriteBudget).orElse(1);
        var arbiter = orch.arbiterRef() != null ? orch.arbiterRef() : new com.writing.novel.service.Arbiter();
        var decision = arbiter.arbitrate(council, budget, alreadyRewritten);
        boolean needsRepair = council.hasHigh() || readerOverall < 7;
        if (decision.action() != com.writing.novel.service.Arbiter.Action.REWRITE) {
            if (needsRepair || decision.action() == com.writing.novel.service.Arbiter.Action.BLOCK)
                pauseForQuality(projectId, chapterIndex, chapter, "质量问题未解决且无法继续自动重写：" + council.combinedFeedback());
            return false;
        }
        if (!rewriteWithCorrection(projectId, chapterIndex, chapter, decision.correctionBrief(), "")) {
            pauseForQuality(projectId, chapterIndex, chapter, "纠偏未成功落稿，原问题尚未验证解决");
            return false;
        }
        EventLog.emit("REWRITE_TRIGGERED", Map.of("projectId", projectId, "chapterIndex", chapterIndex,
                "version", chapter.getVersion(), "reason", "quality_council"));
        try {
            var remaining = new java.util.ArrayList<>(checkedContinuity(projectId, chapterIndex, chapter, cast));
            if (consistencyGuard != null) {
                var checked = consistencyGuard.check(projectId, chapterIndex, chapter.getContent(), cast);
                checked.forEach(repository::saveContinuityIssue);
                remaining.addAll(checked);
            }
            var high = remaining.stream().filter(i -> i.getSeverity() == ContinuityIssue.Severity.HIGH).toList();
            if (!high.isEmpty()) {
                pauseForQuality(projectId, chapterIndex, chapter, "纠偏后仍有严重问题：" + high.get(0).getDescription());
                return true;
            }
            var finalReview = checkedReaderReview(projectId, chapterIndex, chapter);
            if (finalReview != null && !finalReview.isPassed()) {
                pauseForQuality(projectId, chapterIndex, chapter, "纠偏后读者检查仍未通过：" + finalReview.getScore() + "/10");
                return true;
            }
            var originalHigh = council.issues().stream()
                    .filter(i -> i.getSeverity() == ContinuityIssue.Severity.HIGH).toList();
            if (!originalHigh.isEmpty()) {
                if (consistencyGuard == null) throw new IllegalStateException("缺少原问题逐项复验能力");
                var verdicts = consistencyGuard.verifyCorrections(projectId, chapterIndex, chapter.getVersion(),
                        chapter.getContent(), originalHigh, collectSettingsForCheck(projectId, chapterIndex, cast));
                var current = repository.findChapterByIndex(projectId, chapterIndex).orElseThrow();
                if (current.getVersion() != chapter.getVersion())
                    throw new IllegalStateException("复验期间出现新稿，需要重新检查");
                for (int i = 0; i < originalHigh.size(); i++) {
                    var verdict = verdicts.get(i);
                    if (verdict.resolved()) {
                        originalHigh.get(i).resolve();
                        repository.saveContinuityIssue(originalHigh.get(i));
                    } else {
                        pauseForQuality(projectId, chapterIndex, chapter,
                                "原问题复验" + verdict.status() + "：" + originalHigh.get(i).getDescription() + "；" + verdict.reason());
                    }
                    EventLog.emit("CORRECTION_VERIFIED", Map.of("projectId", projectId, "chapterIndex", chapterIndex,
                            "version", chapter.getVersion(), "issueId", originalHigh.get(i).getId(),
                            "verdict", verdict.status(), "evidence", verdict.evidence(), "reason", verdict.reason()));
                }
            }
        } catch (Exception e) {
            pauseForQuality(projectId, chapterIndex, chapter, "纠偏后复验未完成：" + e.getMessage());
        }
        return true;
    }

    /** Unknown quality is a durable pause, never a successful empty report. No prose is overwritten. */
    private void pauseForQuality(String projectId, int chapterIndex, Chapter chapter, String reason) {
        if (!repository.updateChapterStatusIfVersion(projectId, chapterIndex,
                Chapter.Status.NEEDS_MANUAL_REVIEW, chapter.getVersion())) {
            repository.findChapterByIndex(projectId, chapterIndex).ifPresent(chapter::adoptStateFrom);
            reason = "检查期间出现新稿，需要重新检查。" + reason;
            if (!repository.updateChapterStatusIfVersion(projectId, chapterIndex,
                    Chapter.Status.NEEDS_MANUAL_REVIEW, chapter.getVersion()))
                throw new IllegalStateException("无法建立当前稿质量暂停状态");
        }
        chapter.setStatus(Chapter.Status.NEEDS_MANUAL_REVIEW);
        repository.saveReviewBarrier(new com.writing.novel.domain.ReviewBarrier(
                projectId, chapterIndex, chapter.getVersion(), reason));
        EventLog.emit("REVIEW_BARRIER_RAISED", Map.of("projectId", projectId, "chapterIndex", chapterIndex,
                "version", chapter.getVersion(), "reason", EventLog.truncate(reason, 300)));
        log.warn("章{}质量检查暂停：{}", chapterIndex, reason);
    }

    /** 收集设定摘要供连续性检查参考。NCS-097: 委托共享构建器（首查/重查同判据）；
     *  故事真典不再在此注入——由 ContinuityCheckerAgent 以带章节号的写作同源口径注入，消除双通道 */
    private String collectSettingsForCheck(String projectId, int chapterIndex, List<String> cast) {
        return CheckSettingsBuilder.build(repository, orch.relevanceGateway(), projectId, chapterIndex, cast);
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
