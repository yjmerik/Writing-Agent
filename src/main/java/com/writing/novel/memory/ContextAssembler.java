package com.writing.novel.memory;

import com.writing.novel.domain.*;
import com.writing.novel.domain.Character;
import com.writing.novel.service.ClimaxDiversityTracker;
import com.writing.novel.service.ClicheDetector;
import com.writing.novel.service.DiversityTracker;
import com.writing.novel.service.EntityGraphService;
import com.writing.novel.service.NarrativeThreadTracker;
import com.writing.novel.spi.NovelRepository;

import java.util.List;
import java.util.Optional;
import java.util.Comparator;
import java.util.stream.Collectors;

/**
 * 上下文组装器 — 实现 spec 第 5 节的上下文组装公式。
 * 把三层记忆 + 风格 + 任务拼装成 ChapterWriter 所需的完整上下文。
 */
public class ContextAssembler {

    private static final int RETRIEVAL_TOP_K = 5;
    private static final int RECENT_TEXT_CHARS = 2000;
    private static final int RECENT_SUMMARY_WINDOW = 5;
    /** op-01: 开篇高压章节数——前 N 章强制快速抓人 */
    public static final int OPENING_CHAPTERS = 5;

    private final NovelRepository repository;
    private final KnowledgeBaseService knowledgeBase;
    private final SummaryService summaryService;
    private final VectorRetrievalService vectorRetrieval;
    private final EntityGraphService entityGraphService;
    private final NarrativeThreadTracker narrativeThreadTracker;
    private final DiversityTracker diversityTracker;
    private final ClimaxDiversityTracker climaxDiversityTracker;
    private final ClicheDetector clicheDetector;
    /** p10-w1-01: 记忆版本号——null 时退化为永不失效（向后兼容旧构造器/测试） */
    private final MemoryRevision memoryRevision;
    /** p10-w2-07: 故事真典仓库——null 时跳过真典注入（向后兼容） */
    private com.writing.novel.spi.StoryCanon storyCanon;
    /** p10-w2-05: 检索收窄门面——null 时回退全量（向后兼容旧构造器/测试） */
    private RelevanceGateway relevanceGateway;
    private com.writing.novel.service.AuthorIntentContextService authorIntentContextService;

    public void setAuthorIntentContextService(com.writing.novel.service.AuthorIntentContextService service) {
        this.authorIntentContextService = service;
    }


    public ContextAssembler(NovelRepository repository,
                            KnowledgeBaseService knowledgeBase,
                            SummaryService summaryService,
                            VectorRetrievalService vectorRetrieval,
                            EntityGraphService entityGraphService,
                            ClimaxDiversityTracker climaxDiversityTracker,
                            NarrativeThreadTracker narrativeThreadTracker,
                            DiversityTracker diversityTracker,
                            ClicheDetector clicheDetector) {
        this(repository, knowledgeBase, summaryService, vectorRetrieval, entityGraphService,
                climaxDiversityTracker, narrativeThreadTracker, diversityTracker, clicheDetector, null);
    }

    /** p10-w1-01: 带记忆版本号的构造器（生产装配用，启用稳定段缓存失效） */
    public ContextAssembler(NovelRepository repository,
                            KnowledgeBaseService knowledgeBase,
                            SummaryService summaryService,
                            VectorRetrievalService vectorRetrieval,
                            EntityGraphService entityGraphService,
                            ClimaxDiversityTracker climaxDiversityTracker,
                            NarrativeThreadTracker narrativeThreadTracker,
                            DiversityTracker diversityTracker,
                            ClicheDetector clicheDetector,
                            MemoryRevision memoryRevision) {
        this.repository = repository;
        this.knowledgeBase = knowledgeBase;
        this.summaryService = summaryService;
        this.vectorRetrieval = vectorRetrieval;
        this.entityGraphService = entityGraphService;
        this.narrativeThreadTracker = narrativeThreadTracker;
        this.diversityTracker = diversityTracker;
        this.climaxDiversityTracker = climaxDiversityTracker;
        this.clicheDetector = clicheDetector;
        this.memoryRevision = memoryRevision;
    }

    /** D1+p10-w1-01: 稳定段缓存——key=projectId:sectionName，value=(段文本, 缓存时版本号)。
     *  写入方 bump MemoryRevision 后，下次组装比较版本号不一致即重算，修复"缓存永驻"正确性 bug。 */
    private static final class CacheEntry {
        final String value;
        final long revision;
        CacheEntry(String value, long revision) { this.value = value; this.revision = revision; }
    }
    private final java.util.Map<String, CacheEntry> stableSectionCache = new java.util.concurrent.ConcurrentHashMap<>();
    // p37-10: 作者要求选取服务（可选注入；未注入时不注入该节，行为与旧版一致）
    private com.writing.novel.service.DirectiveContextService directiveContextService;
    /** NCS-096: 注入清单持久化出口（可选；未注入时清单仍随 WritingContext 返回，仅不落库） */
    private InjectionManifest.Store manifestStore;
    /** NCS-114: 召回反馈服务（可选；未注入时真典 cap 不受命中率调节） */
    private RecallFeedbackService recallFeedbackService;

    /** p37-10: 注入作者要求选取服务（生产装配用）。 */
    public void setDirectiveContextService(com.writing.novel.service.DirectiveContextService s) {
        this.directiveContextService = s;
    }

    /** NCS-096: 注入清单持久化出口（生产装配用）。 */
    public void setManifestStore(InjectionManifest.Store store) {
        this.manifestStore = store;
    }

    /** NCS-114: 注入召回反馈服务（生产装配用）。 */
    public void setRecallFeedbackService(RecallFeedbackService service) {
        this.recallFeedbackService = service;
    }

    public WritingContext assemble(String projectId, int chapterIndex,
                                   String chapterTitle, String chapterSynopsis,
                                   String writingNotes) {
        return assemble(projectId, chapterIndex, chapterTitle, chapterSynopsis,
                writingNotes, TokenBudget.DEFAULT_BUDGET);
    }

    /**
     * mc-p2-03: 带模型感知预算的组装。
     * @param maxContextTokens 组装预算（通常由 TokenBudget.budgetForModel(contextWindow) 推导）
     */
    public WritingContext assemble(String projectId, int chapterIndex,
                                   String chapterTitle, String chapterSynopsis,
                                   String writingNotes, int maxContextTokens) {

        WritingContext ctx = new WritingContext();
        // NCS-096: 注入清单——与各 section 同源生成，选取点现场记录
        InjectionManifest manifest = new InjectionManifest(
                projectId, chapterIndex, draftVersionOf(projectId, chapterIndex), maxContextTokens);

        // 【故事原旨】最高优先级注入——让 LLM 永远知道这本书讲什么
        ctx.setPremiseSection(buildPremiseSection(projectId));

        repository.findStyleProfileByProject(projectId)
                .ifPresent(style -> ctx.setStyleSection(buildStyleSection(style)));

        ctx.setWorldbuildingSection(buildWorldbuildingSection(projectId));
        ctx.setVolumeOutlineSection(buildVolumeOutline(projectId, chapterIndex));
        ctx.setRecentSummarySection(buildRecentSummary(projectId, chapterIndex));
        ctx.setRecentTextSection(buildRecentText(projectId, chapterIndex));

        String queryText = chapterSynopsis != null ? chapterSynopsis : chapterTitle;
        ChapterSynopsis fullSynopsis = findFullSynopsis(projectId, chapterIndex);
        // NCS-110: 多路查询——大纲 + cast 规范名 + 待回收伏笔关键词
        List<String> cast = expandCast(parseCast(fullSynopsis), projectId);
        List<String> foreshadowHints = repository.findUnresolvedForeshadowings(projectId).stream()
                .filter(f -> f.getPlantedAtChapter() <= chapterIndex)
                .filter(f -> f.getPriority() == Foreshadowing.Priority.HIGH || f.isMustResolve())
                .sorted(java.util.Comparator.comparingInt(
                        f -> -f.suspenseChapters(chapterIndex)))
                .map(Foreshadowing::getDescription)
                .limit(3)
                .toList();
        assembleRetrievedContext(projectId, queryText, cast, foreshadowHints, ctx, maxContextTokens, manifest);
        ctx.setRelevantForeshadowings(buildForeshadowings(projectId, chapterIndex, cast, manifest));
        ctx.setBibleSection(buildBibleSection(projectId, chapterIndex, fullSynopsis, manifest));
        ctx.setTaskSection(buildTaskSection(chapterTitle, chapterSynopsis, writingNotes, fullSynopsis));

        // p37-10: 有效作者要求注入生成上下文（不可静默裁剪）——HARD 超预算明确阻塞。
        // 作者要求预算以字符计，取组装预算同量级。
        if (directiveContextService != null) {
            var selected = directiveContextService.select(projectId, chapterIndex, maxContextTokens);
            if (!selected.blockedHardIds().isEmpty()) {
                // HARD 截不掉：明确阻塞生成（请求不发送），需作者缩减要求
                throw new IllegalStateException("作者硬性要求超出上下文预算，无法完整注入（需缩减要求）: "
                        + selected.blockedHardIds());
            }
            if (!selected.isEmpty()) {
                ctx.setAuthorDirectiveSection(selected.section());
            }
        }

        // Read the current contract for each request, outside the cached story-premise section.
        if (authorIntentContextService != null) {
            String contract = authorIntentContextService.render(projectId, maxContextTokens);
            if (!contract.isBlank()) {
                String directives = ctx.getAuthorDirectiveSection();
                String protectedContext = contract + (directives == null || directives.isBlank()
                        ? "" : "\n\n" + directives);
                if (TokenBudget.estimateTokens(protectedContext) > maxContextTokens) {
                    throw new IllegalStateException("创作约定与作者要求合计超出上下文预算，不能静默截断");
                }
                ctx.setAuthorDirectiveSection(protectedContext);
            }
        }

        // 实体一致性注入（NCS-113: 带本章 cast）
        ctx.setEntityConsistencySection(buildEntityConsistencySection(projectId, chapterIndex, cast));

        // 写作偏好：对话约束（含密度目标）+ 原有的量化约束
        ctx.setWritingConstraints(buildWritingConstraints(projectId, chapterIndex, fullSynopsis));

        // 角色声音注入（说话风格 + 知识领域）
        ctx.setCharacterVoiceSection(buildCharacterVoiceSection(projectId, fullSynopsis));

        // 引用素材注入（与角色知识领域匹配的世界观条目）
        ctx.setKnowledgeQuoteSection(buildKnowledgeQuoteSection(projectId));

        // 叙事线索追踪（NCS-113: 带本章 cast）
        ctx.setNarrativeThreadSection(buildNarrativeThreadSection(projectId, cast));

        // p11-H4: 角色关系网络注入
        String relationshipWeb = buildRelationshipWeb(projectId, fullSynopsis);
        if (relationshipWeb != null && !relationshipWeb.isBlank()) {
            ctx.setNarrativeThreadSection(
                    (ctx.getNarrativeThreadSection() != null ? ctx.getNarrativeThreadSection() + "\n\n" : "")
                    + relationshipWeb);
        }

        // 质量反馈闭环：上卷评价注入
        ctx.setQualityFeedbackSection(buildQualityFeedbackSection(projectId));
        ctx.setFeedbackSection(buildFeedbackSection(projectId, chapterIndex));

        // Token 预算控制：预算按模型窗口推导（90%），超限时按优先级裁剪低优先级 section
        java.util.Map<String, Integer> beforeTrim = sectionTokenSnapshot(ctx);
        applyTokenBudget(ctx, maxContextTokens);
        // NCS-112: 兜底校验——不可裁盲区（taskSection/bible/约束等）之和超预算时显式事件化，
        // 不再静默发送超窗 prompt
        int finalEstimate = sumTokens(ctx);
        if (finalEstimate > maxContextTokens) {
            lastBudgetOverflowEstimate = finalEstimate;
            com.writing.novel.telemetry.EventLog.emit("CONTEXT_BUDGET_EXCEEDED", java.util.Map.of(
                    "projectId", projectId, "chapterIndex", chapterIndex,
                    "estimatedTokens", finalEstimate, "budget", maxContextTokens));
        }
        finalizeManifest(manifest, ctx, beforeTrim);
        ctx.setManifest(manifest);
        saveManifestQuietly(manifest);

        return ctx;
    }

    /** NCS-096: 本次组装针对的稿件版本（现有章版本+1；新章=1），与 Chapter.version 对齐 */
    private int draftVersionOf(String projectId, int chapterIndex) {
        return repository.findChapterByIndex(projectId, chapterIndex)
                .map(Chapter::getVersion).orElse(0) + 1;
    }

    /** NCS-096: section 名→文本视图（快照/结算共用同一枚举顺序） */
    private static List<String[]> sectionViews(WritingContext ctx) {
        return List.of(
                new String[]{"premiseSection", ctx.getPremiseSection()},
                new String[]{"authorDirectiveSection", ctx.getAuthorDirectiveSection()},
                new String[]{"styleSection", ctx.getStyleSection()},
                new String[]{"worldbuildingSection", ctx.getWorldbuildingSection()},
                new String[]{"bibleSection", ctx.getBibleSection()},
                new String[]{"entityConsistencySection", ctx.getEntityConsistencySection()},
                new String[]{"feedbackSection", ctx.getFeedbackSection()},
                new String[]{"relevantSettings", ctx.getRelevantSettings()},
                new String[]{"characterVoiceSection", ctx.getCharacterVoiceSection()},
                new String[]{"volumeOutlineSection", ctx.getVolumeOutlineSection()},
                new String[]{"recentSummarySection", ctx.getRecentSummarySection()},
                new String[]{"recentTextSection", ctx.getRecentTextSection()},
                new String[]{"relevantForeshadowings", ctx.getRelevantForeshadowings()},
                new String[]{"narrativeThreadSection", ctx.getNarrativeThreadSection()},
                new String[]{"qualityFeedbackSection", ctx.getQualityFeedbackSection()},
                new String[]{"knowledgeQuoteSection", ctx.getKnowledgeQuoteSection()},
                new String[]{"taskSection", ctx.getTaskSection()},
                new String[]{"writingConstraints", ctx.getWritingConstraints()});
    }

    private java.util.Map<String, Integer> sectionTokenSnapshot(WritingContext ctx) {
        java.util.Map<String, Integer> map = new java.util.LinkedHashMap<>();
        for (String[] view : sectionViews(ctx)) {
            String text = view[1];
            map.put(view[0], text == null ? 0 : TokenBudget.estimateTokens(text));
        }
        return map;
    }

    /** NCS-096: 按裁剪后的最终注入内容结算 section token，并标记被削减的 section */
    private void finalizeManifest(InjectionManifest manifest, WritingContext ctx,
                                  java.util.Map<String, Integer> beforeTrim) {
        java.util.Map<String, Integer> after = sectionTokenSnapshot(ctx);
        for (java.util.Map.Entry<String, Integer> e : after.entrySet()) {
            Integer before = beforeTrim.get(e.getKey());
            if (before != null && e.getValue() < before) {
                manifest.addTrimmedSection(e.getKey());
            }
        }
        manifest.setSectionTokens(after);
    }

    /** NCS-096: 清单持久化失败不影响写作（可见性靠告警日志） */
    private void saveManifestQuietly(InjectionManifest manifest) {
        if (manifestStore == null) return;
        try {
            manifestStore.save(manifest);
        } catch (Exception e) {
            System.getLogger(ContextAssembler.class.getName())
                    .log(System.Logger.Level.WARNING,
                            "注入清单持久化失败（不影响写作）: " + e.getMessage());
        }
    }

    private String buildStyleSection(StyleProfile style) {
        StringBuilder sb = new StringBuilder();
        if (style.getTone() != null) sb.append("基调：").append(style.getTone()).append("\n");
        if (style.getVoice() != null) sb.append("叙事：").append(style.getVoice()).append("\n");
        if (style.getDosAndDonts() != null) sb.append("注意事项：").append(style.getDosAndDonts());
        return sb.toString();
    }

    /** 构建故事原旨：总纲 + 构思，作为全书最高纲领。D1: 稳定段缓存 */
    private String buildPremiseSection(String projectId) {
        return cached("premise", projectId, () -> {
            StringBuilder sb = new StringBuilder();
            repository.findProject(projectId).ifPresent(p -> {
                if (p.getConcept() != null) sb.append("构思：").append(p.getConcept()).append("\n");
            });
            repository.findOutlineByProject(projectId).ifPresent(o -> {
                if (o.getPremise() != null && !o.getPremise().isBlank()) {
                    sb.append("总纲：").append(o.getPremise());
                }
            });
            return sb.toString().trim();
        });
    }

    /** p10-w2-07: 注入故事真典仓库（@Bean 装配用）；不调则保持 null，真典段不注入 */
    public void setStoryCanon(com.writing.novel.spi.StoryCanon storyCanon) {
        this.storyCanon = storyCanon;
    }

    /** p10-w2-05: 注入检索收窄门面（@Bean 装配用）；不调则回退全量（向后兼容） */
    public void setRelevanceGateway(RelevanceGateway relevanceGateway) {
        this.relevanceGateway = relevanceGateway;
    }

    private String buildBibleSection(String projectId, int chapterIndex, ChapterSynopsis synopsis,
                                     InjectionManifest manifest) {
        List<String> cast = parseCast(synopsis);
        int factCap = recallFeedbackService != null
                ? recallFeedbackService.recommendedFactCap(projectId, 40) : 40;
        List<StoryFact> facts;
        if (relevanceGateway != null) {
            facts = relevanceGateway.relevantFacts(projectId, cast, chapterIndex, factCap);
        } else if (storyCanon != null) {
            facts = storyCanon.recall(projectId);
            if (facts.size() > factCap) facts = facts.subList(0, factCap);
        } else {
            return "";
        }
        if (facts.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (var f : facts) {
            sb.append("■ [").append(f.getFactType()).append("] ").append(f.getSubject())
                    .append("：").append(f.getAssertion());
            // p32-06: 待核事实明确标注——写作模型不得把角色台词/推测当已确认铁律引用
            if (f.getEvidenceGrade() == StoryFact.EvidenceGrade.UNVERIFIED) {
                sb.append("【待核：来源为角色陈述或推测，未经确认，不得作为铁律引用】");
            }
            sb.append("\n");
            // NCS-096: 真典事实选取点现场记录
            manifest.addEntry("bibleSection", InjectionManifest.Kind.FACT, f.getId(),
                    f.getSubject() + "：" + firstChars(f.getAssertion(), 40),
                    TokenBudget.estimateTokens(sb.substring(sb.lastIndexOf("■"))));
        }
        return sb.toString().trim();
    }

    private static String firstChars(String s, int n) {
        if (s == null) return "";
        return s.length() > n ? s.substring(0, n) : s;
    }

    /** NCS-110: cast 别名扩展——无 RelevanceGateway 时原样返回（向后兼容） */
    private List<String> expandCast(List<String> cast, String projectId) {
        return relevanceGateway != null && cast != null
                ? relevanceGateway.expandCastWithAliases(projectId, cast) : cast;
    }

    private List<String> parseCast(ChapterSynopsis synopsis) {
        if (synopsis == null || synopsis.getCast() == null || synopsis.getCast().isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(synopsis.getCast().split("[,，、\\s]+"))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** p10-w1-01: 使该项目全部稳定段缓存失效（下次组装重算）。
     *  写入方（如 NovelOrchestrator 改 outline.premise）经此触发，memoryRevision==null 时为空操作。 */
    public void invalidateProject(String projectId) {
        if (memoryRevision != null) {
            memoryRevision.bump(projectId);
        }
    }

    /** D1+p10-w1-01: 稳定段缓存读写——比较记忆版本号，写入方 bump 后自动失效重算。
     *  memoryRevision==null（旧构造器）时版本号恒 0，退化为永不失效，向后兼容。 */
    private String cached(String section, String projectId, java.util.function.Supplier<String> builder) {
        String key = projectId + ":" + section;
        long rev = memoryRevision != null ? memoryRevision.current(projectId) : 0L;
        CacheEntry hit = stableSectionCache.get(key);
        if (hit != null && hit.revision == rev) {
            return hit.value;
        }
        String value = builder.get();
        stableSectionCache.put(key, new CacheEntry(value, rev));
        return value;
    }

    /** 世界观按类型多样选样：每种 type 最多 2 条，优先级排序 */
    private static final java.util.Map<String, Integer> TYPE_PRIORITY = java.util.Map.of(
            "POWER_SYSTEM", 1, "FACTION", 2, "GEOGRAPHY", 3, "RULE", 4,
            "HISTORY", 5, "CULTURE", 6, "ECONOMY", 7, "RACE", 8, "CUSTOM", 9
    );

    private String buildWorldbuildingSection(String projectId) {
        return cached("worldbuilding", projectId, () -> {
            List<KnowledgeEntry> entries = knowledgeBase.findAllEntries(projectId);
            if (entries.isEmpty()) return "";
            // 按 type 分组，每组最多 2 条，按优先级排序
            java.util.Map<String, List<KnowledgeEntry>> byType = new java.util.LinkedHashMap<>();
            for (KnowledgeEntry e : entries) {
                byType.computeIfAbsent(e.getType(), k -> new java.util.ArrayList<>()).add(e);
            }
            // 按类型优先级排序
            List<String> sortedTypes = new java.util.ArrayList<>(byType.keySet());
            sortedTypes.sort(java.util.Comparator.comparingInt(
                    t -> TYPE_PRIORITY.getOrDefault(t, 99)));

            StringBuilder sb = new StringBuilder();
            for (String type : sortedTypes) {
                List<KnowledgeEntry> group = byType.get(type);
                int limit = Math.min(group.size(), 2); // 每类最多 2 条
                for (int i = 0; i < limit; i++) {
                    KnowledgeEntry e = group.get(i);
                    sb.append("【").append(e.getName()).append("】").append(e.getContent()).append("\n");
                }
            }
            return sb.toString().trim();
        });
    }

    private String buildVolumeOutline(String projectId, int chapterIndex) {
        Optional<Outline> outlineOpt = repository.findOutlineByProject(projectId);
        if (outlineOpt.isEmpty()) return "";
        for (Volume v : outlineOpt.get().getVolumes()) {
            boolean contains = v.getSynopses().stream()
                    .anyMatch(s -> s.getChapterIndex() == chapterIndex);
            if (contains) {
                StringBuilder sb = new StringBuilder();
                sb.append("卷：").append(v.getTitle()).append("\n");
                if (v.getSummary() != null) sb.append(v.getSummary()).append("\n");
                for (ChapterSynopsis s : v.getSynopses()) {
                    sb.append("  第").append(s.getChapterIndex()).append("章 ")
                            .append(s.getTitle()).append("：").append(s.getSynopsis()).append("\n");
                }
                return sb.toString().trim();
            }
        }
        return "";
    }

    private String buildRecentSummary(String projectId, int chapterIndex) {
        // p19-03: 全书结构化快照——主线进度/阵营/人物状态/时间线的状态板，注入最顶
        StringBuilder snapshotSb = new StringBuilder();
        repository.findProject(projectId).map(p -> p.getBookSnapshot()).ifPresent(snap -> {
            if (snap != null && !snap.isBlank()) {
                snapshotSb.append("【全书快照】").append(snap).append("\n\n");
            }
        });
        // C2: 全书摘要——注入最顶部，不受 VOLUME_DETAIL_LIMIT 裁剪（修复记忆地平线坍塌）
        String bookSummary = repository.findProject(projectId)
                .map(p -> p.getBookSummary()).orElse(null);

        int lookBackTo = Math.max(1, chapterIndex - 1);
        // 细粒度摘要：最近 window 章
        List<Summary> recentSummaries = summaryService.findRecent(projectId, lookBackTo, RECENT_SUMMARY_WINDOW);
        // 卷级摘要：p12-K4 限制最多注入最近 3 卷，更早的只取摘要首句
        List<Summary> allSummaries = summaryService.findAll(projectId);

        // mc-p2-01: 过滤 dirty 摘要——源章节被重写后未重算的旧摘要不注入上下文
        allSummaries = allSummaries.stream().filter(s -> !s.isDirty()).toList();

        List<Summary> fineGrained = new java.util.ArrayList<>();
        List<Summary> volumeLevel = new java.util.ArrayList<>();
        for (Summary s : allSummaries) {
            int span = s.getToChapter() - s.getFromChapter() + 1;
            if (span > 3) {
                volumeLevel.add(s); // 卷级
            } else if (s.getToChapter() >= lookBackTo - RECENT_SUMMARY_WINDOW + 1) {
                fineGrained.add(s); // 细粒度仅最近 window
            }
        }
        if (fineGrained.isEmpty() && volumeLevel.isEmpty() && (bookSummary == null || bookSummary.isBlank())
                && snapshotSb.length() == 0) return "";

        // p12-K4: 卷摘要按章节倒序排列，最近 3 卷详细，更早的截断
        volumeLevel.sort((a, b) -> Integer.compare(b.getFromChapter(), a.getFromChapter()));
        int VOLUME_DETAIL_LIMIT = 3;

        StringBuilder sb = new StringBuilder();
        // C2: 全书摘要——最顶部注入（不受裁剪），确保第 100 章后仍知道全书主线
        if (bookSummary != null && !bookSummary.isBlank()) {
            sb.append("【全书梗概（截至当前）】").append(bookSummary).append("\n\n");
        }
        // p16-06-A: 分层组装，顺序 = book_summary → 最近 3 卷全文 → 细粒度摘要 → 更早卷 stub 追加在尾。
        // applyTokenBudget 对 recentSummary 超预算时整体截尾（truncateToTokens 保头部），
        // 字符串尾部内容最先被牺牲：把"最老卷 stub"放在最后，衔接最关键的近期细粒度摘要
        // 位于头部保护区，天然不被远期摘要挤掉（修复"远期挤死近期"）。
        // 信息语义不变：最近 3 卷详细 + 更早卷截 300 字。
        if (!volumeLevel.isEmpty()) {
            sb.append("【前卷概览】\n");
            // 最近 3 卷全文（远期剧情概览中最高优先级）
            for (int i = 0; i < volumeLevel.size() && i < VOLUME_DETAIL_LIMIT; i++) {
                Summary s = volumeLevel.get(i);
                sb.append("第").append(s.getFromChapter()).append("-")
                        .append(s.getToChapter()).append("章：")
                        .append(s.getContent())
                        .append("\n");
            }
            sb.append("\n");
        }
        // 近期细粒度摘要——紧随最近 3 卷之后（高保护位，整体截尾时最后才波及）
        if (!fineGrained.isEmpty()) {
            for (Summary s : fineGrained) {
                sb.append("第").append(s.getFromChapter()).append("-")
                        .append(s.getToChapter()).append("章：")
                        .append(s.getContent()).append("\n");
            }
        }
        // 更早卷 stub 追加在尾部（裁剪牺牲区：volumeLevel 按新→旧排序，越老的卷越靠后、越先被整体截尾丢弃）
        if (volumeLevel.size() > VOLUME_DETAIL_LIMIT) {
            sb.append("\n");
            for (int i = VOLUME_DETAIL_LIMIT; i < volumeLevel.size(); i++) {
                Summary s = volumeLevel.get(i);
                sb.append("第").append(s.getFromChapter()).append("-")
                        .append(s.getToChapter()).append("章：");
                // C2: 更早的卷截断放宽到 300 字（原 100 字信息损失过大）
                String content = s.getContent();
                sb.append(content.length() > 300 ? content.substring(0, 300) + "..." : content);
                sb.append("（早期卷，已精简）");
                sb.append("\n");
            }
        }
        return (snapshotSb.length() > 0 ? snapshotSb.toString() : "") + sb.toString().trim();
    }

    private String buildRecentText(String projectId, int chapterIndex) {
        if (chapterIndex <= 1) return "";
        Optional<Chapter> prevOpt = repository.findChapterByIndex(projectId, chapterIndex - 1);
        if (prevOpt.isEmpty()) return "";
        String content = prevOpt.get().getContent();
        if (content == null || content.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();

        // 截取上章末尾作为参考上下文
        if (content.length() > RECENT_TEXT_CHARS) {
            sb.append("...").append(content.substring(content.length() - RECENT_TEXT_CHARS));
        } else {
            sb.append(content);
        }

        // 精确衔接点：提取最后 3 句话
        String[] sentences = content.split("(?<=[。！？])");
        String lastThree = "";
        if (sentences.length >= 3) {
            lastThree = sentences[sentences.length - 3].trim()
                    + sentences[sentences.length - 2].trim()
                    + sentences[sentences.length - 1].trim();
        } else {
            lastThree = content.length() > 200 ? content.substring(content.length() - 200) : content;
        }

        sb.append("\n\n【精确衔接点（本章从这里开始）】\n")
          .append(lastThree)
          .append("\n\n⚠️ 本章正文必须从上文最后一句话的下一时刻/下一场景直接开始，"
                  + "不得重复描述上一章结尾已经写过的场景。");

        // p8-4: preventTransitionOverlap 偏好控制
        var prefs = repository.findWritingPreferences(projectId);
        if (prefs.isPresent() && prefs.get().isPreventTransitionOverlap()) {
            sb.append("\n⚠️ 特别注意：本章开头不得与最近章节的开头句式雷同，"
                    + "必须使用完全不同的开场方式（如从不同角色视角、不同场景切入）。");
        }

        return sb.toString();
    }

    private void assembleRetrievedContext(String projectId, String queryText, List<String> cast,
                                          List<String> foreshadowHints, WritingContext ctx,
                                          int maxContextTokens, InjectionManifest manifest) {
        List<Character> allChars = knowledgeBase.findAllCharacters(projectId);
        if (allChars.isEmpty()) return;

        // p10-w1-03: top-K 随组装预算动态（5~20），大窗口多召回历史相关情节，修复固定 5 召回率过低
        int topK = Math.max(RETRIEVAL_TOP_K, Math.min(20, maxContextTokens / 4000));

        // NCS-110: 多路查询混合检索——大纲/cast/伏笔锚点各路独立检索，id 去重合并保最高分
        List<String> queries = RecallQueryPlanner.queries(queryText, null, cast, foreshadowHints);
        java.util.Map<String, Double> scored = new java.util.LinkedHashMap<>();
        boolean vectorFailed = false;
        for (String query : queries) {
            try {
                int perQueryK = Math.max(3, topK / queries.size());
                for (var r : vectorRetrieval.search(query, projectId, perQueryK, MIN_RELEVANCE_SCORE)) {
                    scored.merge(r.id(), r.score(), Math::max);
                }
            } catch (Exception e) {
                // p10-w1-03: embedding 失败不再静默——记录告警，便于发现检索层故障（此前静默丢历史召回）
                vectorFailed = true;
                System.getLogger(ContextAssembler.class.getName())
                        .log(System.Logger.Level.WARNING,
                                "向量检索失败(查询路数" + queries.size() + ")，本次组装降级词法通道: "
                                        + e.getMessage());
                break;
            }
        }
        List<String> relevantIds = scored.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .map(java.util.Map.Entry::getKey)
                .toList();
        // NCS-111: 正文 chunk 与设定/角色两桶配额（各半），防正文 chunk 数量级压没设定条目；
        // 同章相邻 chunk 只保留首位（语义重叠去重）
        relevantIds = applySourceQuotaAndAdjacency(relevantIds, topK);
        // NCS-110: 词法兜底——向量失败或全部低于阈值（0 分）时按实体名精确匹配知识条目，
        // 消除静默空召回
        if (relevantIds.isEmpty() && (vectorFailed || cast != null)) {
            relevantIds = lexicalFallback(projectId, cast, topK);
            if (!relevantIds.isEmpty()) {
                manifest.addEntry("relevantSettings", InjectionManifest.Kind.KNOWLEDGE,
                        "lexical-fallback:" + relevantIds.size(), "词法兜底通道", 0);
            }
        }

        final List<String> finalRelevantIds = relevantIds;
        List<Character> relevantChars = allChars.stream()
                .filter(c -> finalRelevantIds.contains(c.getId()))
                .collect(Collectors.toList());

        // 强制注入主角（主角不依赖检索命中，避免名字漂移）
        for (Character c : allChars) {
            if (isMainCharacter(c) && !relevantChars.contains(c)) {
                relevantChars.add(0, c);  // 主角放第一位
            }
        }

        // 回退：检索完全没命中时，注入全部人物（最多 8 个）
        if (relevantChars.isEmpty()) {
            relevantChars = allChars.stream().limit(8).collect(Collectors.toList());
        }

        ctx.setRelevantCharacters(relevantChars);
        // NCS-096: 出场角色选取点记录（含主角强制锚定与回退注入）
        for (Character c : relevantChars) {
            manifest.addEntry("relevantCharacters", InjectionManifest.Kind.CHARACTER,
                    c.getId(), c.getName() + "（" + c.getRole() + "）", 0);
        }

        StringBuilder settings = new StringBuilder();
        for (String id : relevantIds) {
            if (id.contains("#chunk")) {
                // 章节正文 chunk：从向量库取文本（findKnowledgeEntry 查不到）
                String chunkText = vectorRetrieval.getText(id);
                if (chunkText != null) {
                    settings.append(chunkText).append("\n");
                    // NCS-096: 正文 chunk 选取点记录
                    manifest.addEntry("relevantSettings", InjectionManifest.Kind.CHUNK, id,
                            firstChars(chunkText, 40), TokenBudget.estimateTokens(chunkText));
                }
            } else {
                // 知识条目：从 DB 取
                repository.findKnowledgeEntry(id).ifPresent(e -> {
                    settings.append("【").append(e.getName()).append("】")
                            .append(e.getContent()).append("\n");
                    // NCS-096: 知识条目选取点记录
                    manifest.addEntry("relevantSettings", InjectionManifest.Kind.KNOWLEDGE, id,
                            e.getName(), TokenBudget.estimateTokens(e.getName() + e.getContent()));
                });
            }
        }
        String settingsText = settings.toString().trim();
        if (settingsText.length() > RELEVANT_SETTINGS_MAX_CHARS) {
            settingsText = settingsText.substring(0, RELEVANT_SETTINGS_MAX_CHARS) + "\n…（相关设定已达注入上限）";
        }
        ctx.setRelevantSettings(settingsText);
    }

    /** NCS-112: 最近一次组装的预算溢出估算（0=未溢出；测试与诊断用） */
    private volatile int lastBudgetOverflowEstimate = 0;

    public int getLastBudgetOverflowEstimate() { return lastBudgetOverflowEstimate; }

    /**
     * NCS-112: 截断常量随预算缩放——大窗口（&gt; 基准 16000）线性放大不浪费；小窗口保持基准值
     * 不向下折损（摘要/衔接等记忆核心段的既有裁剪语义不变，小预算压力由 settings 截断与
     * 溢出信号承担）。
     */
    static int scaled(int base, int budget) {
        int reference = TokenBudget.DEFAULT_BUDGET;
        if (budget <= reference) return base;
        return (int) ((long) base * budget / reference);
    }

    /** NCS-111: 绝对相似度阈值——低于此分数的 chunk 不注入（可按 embedding 模型调整） */
    static final double MIN_RELEVANCE_SCORE = 0.35;
    /** NCS-111: relevantSettings 注入字数上限（防长 chunk 挤爆上下文） */
    static final int RELEVANT_SETTINGS_MAX_CHARS = 6000;

    /**
     * NCS-111: 两桶配额 + 相邻 chunk 去重——
     * chunk（id 含 #chunk）与非 chunk（知识条目/角色卡）各占 topK 一半（不足半额时另一桶可补足）；
     * 同章相邻 chunk（chapterId#chunkN 与 #chunkN+1 同时命中）只保留前一块。
     */
    static List<String> applySourceQuotaAndAdjacency(List<String> sortedIds, int topK) {
        List<String> chunks = new java.util.ArrayList<>();
        List<String> others = new java.util.ArrayList<>();
        for (String id : sortedIds) {
            (id.contains("#chunk") ? chunks : others).add(id);
        }
        // 相邻 chunk 去重：同章按块号升序，与前一个保留块相邻（n = lastKept+1）才丢弃——链式不传染
        java.util.Set<String> adjacentDropped = new java.util.HashSet<>();
        java.util.Map<String, java.util.TreeMap<Integer, String>> byChapter = new java.util.HashMap<>();
        for (String id : chunks) {
            int hash = id.lastIndexOf('#');
            if (hash <= 0) continue;
            try {
                int n = Integer.parseInt(id.substring(hash + "chunk".length() + 1));
                byChapter.computeIfAbsent(id.substring(0, hash), k -> new java.util.TreeMap<>()).put(n, id);
            } catch (NumberFormatException ignored) {}
        }
        for (java.util.TreeMap<Integer, String> series : byChapter.values()) {
            Integer lastKept = null;
            for (var e : series.entrySet()) {
                if (lastKept != null && e.getKey() == lastKept + 1) {
                    adjacentDropped.add(e.getValue());
                } else {
                    lastKept = e.getKey();
                }
            }
        }
        chunks.removeAll(adjacentDropped);
        int quota = Math.max(2, topK / 2);
        List<String> keptChunks = chunks.size() > quota ? chunks.subList(0, quota) : chunks;
        List<String> keptOthers = others.size() > quota ? others.subList(0, quota) : others;
        List<String> out = new java.util.ArrayList<>(keptChunks);
        out.addAll(keptOthers);
        // 补足：某桶不足半额时另一桶依序补到 topK
        java.util.Iterator<String> extra = sortedIds.stream()
                .filter(id -> !out.contains(id) && !adjacentDropped.contains(id)).iterator();
        while (out.size() < topK && extra.hasNext()) out.add(extra.next());
        return out;
    }

    /**
     * NCS-110: 词法兜底通道——实体名/别名精确匹配知识条目（cap），向量层不可用时保底召回。
     */
    private List<String> lexicalFallback(String projectId, List<String> cast, int cap) {
        if (cast == null || cast.isEmpty()) return List.of();
        try {
            List<String> out = new java.util.ArrayList<>();
            for (var e : repository.findKnowledgeEntriesByProject(projectId)) {
                if (out.size() >= cap) break;
                boolean hit = cast.stream().anyMatch(name -> name != null && !name.isBlank()
                        && (e.getName() != null && (e.getName().contains(name)
                            || (e.getContent() != null && e.getContent().contains(name)))));
                if (hit) out.add(e.getId());
            }
            return out;
        } catch (Exception ex) {
            return List.of();
        }
    }

    /** 判断是否主角（用于强制锚定） */
    private boolean isMainCharacter(Character c) {
        String role = c.getRole();
        return role != null && (role.contains("主角") || role.contains("男主") || role.contains("女主"));
    }

    /** NCS-113: 伏笔相关性分——本章 cast 命中 +100（相关但不够老的伏笔不再被纯悬置排序挤出）；僵尸（>25 章且无关）-50 */
    public static int foreshadowScore(Foreshadowing f, List<String> cast, int currentChapter) {
        int score = Math.min(f.suspenseChapters(currentChapter), 25);
        boolean castHit = cast != null && cast.stream().anyMatch(name -> name != null && !name.isBlank()
                && f.getDescription() != null && f.getDescription().contains(name));
        if (castHit) score += 100;
        if (!castHit && f.suspenseChapters(currentChapter) > 25) score -= 50;
        return score;
    }

    /** 兼容旧签名（既有测试反射调用）；生产路径走带 cast 与清单的四参版本 */
    private String buildForeshadowings(String projectId, int chapterIndex) {
        return buildForeshadowings(projectId, chapterIndex, null,
                new InjectionManifest(projectId, chapterIndex, 0, 0));
    }

    private String buildForeshadowings(String projectId, int chapterIndex, List<String> cast,
                                       InjectionManifest manifest) {
        List<Foreshadowing> unresolved = repository.findUnresolvedForeshadowings(projectId).stream()
                .filter(f -> f.getPlantedAtChapter() <= chapterIndex)
                .toList();
        if (unresolved.isEmpty()) return "";

        final int currentChapter = chapterIndex;
        final int LIMIT = 12;
        var mustInclude = unresolved.stream()
                .filter(f -> f.getPriority() == Foreshadowing.Priority.HIGH || f.isMustResolve())
                .sorted((a, b) -> Integer.compare(
                        foreshadowScore(b, cast, currentChapter), foreshadowScore(a, cast, currentChapter)))
                .limit(8)
                .toList();
        var others = unresolved.stream()
                .filter(f -> f.getPriority() != Foreshadowing.Priority.HIGH && !f.isMustResolve())
                .sorted((a, b) -> Integer.compare(
                        foreshadowScore(b, cast, currentChapter), foreshadowScore(a, cast, currentChapter)))
                .limit(Math.max(0, LIMIT - mustInclude.size()))
                .toList();
        // NCS-096: 伏笔注入选取点记录（mustInclude + others 即实际注入集）
        for (Foreshadowing f : mustInclude) {
            manifest.addEntry("relevantForeshadowings", InjectionManifest.Kind.FORESHADOW,
                    f.getId(), firstChars(f.getDescription(), 40), 0);
        }
        for (Foreshadowing f : others) {
            manifest.addEntry("relevantForeshadowings", InjectionManifest.Kind.FORESHADOW,
                    f.getId(), firstChars(f.getDescription(), 40), 0);
        }

        StringBuilder sb = new StringBuilder();

        // 强制回收段：过期的 mustResolve 伏笔
        var overdueCritical = mustInclude.stream()
                .filter(f -> f.isOverdue(currentChapter))
                .toList();
        if (!overdueCritical.isEmpty()) {
            sb.append("🔴 【本章必须回收】以下重要伏笔已超过预计回收章节，务必在本章给出答案：\n");
            for (Foreshadowing f : overdueCritical) {
                sb.append("  ★ ").append(f.getDescription())
                        .append("（埋于第").append(f.getPlantedAtChapter());
                if (f.getExpectedResolveChapter() != null) {
                    sb.append("章，应于第").append(f.getExpectedResolveChapter()).append("章回收");
                }
                sb.append("）\n");
            }
            sb.append("\n");
        }

        // 正常待回收列表
        sb.append("【待回收伏笔】请在后续章节中回收以下伏笔（揭晓谜底、给出答案、或让角色明确提及结论）：\n");
        for (Foreshadowing f : mustInclude) {
            if (overdueCritical.contains(f)) continue; // 已在强制段列出
            sb.append("- ").append(f.getDescription())
                    .append("（埋于第").append(f.getPlantedAtChapter()).append("章");
            if (f.getPriority() == Foreshadowing.Priority.HIGH) sb.append("，★重要");
            sb.append("）\n");
        }
        for (Foreshadowing f : others) {
            sb.append("- ").append(f.getDescription())
                    .append("（埋于第").append(f.getPlantedAtChapter()).append("章，挂起")
                    .append(f.suspenseChapters(currentChapter)).append("章）\n");
        }

        int shown = mustInclude.size() + others.size();
        if (unresolved.size() > shown) {
            sb.append("（还有 ").append(unresolved.size() - shown).append(" 条次要伏笔未列出）\n");
        }
        return sb.toString().trim();
    }

    /** 从 outline 查找当前章节的完整 ChapterSynopsis（含 beats/climax/cast） */
    private ChapterSynopsis findFullSynopsis(String projectId, int chapterIndex) {
        var outline = repository.findOutlineByProject(projectId);
        if (outline.isEmpty()) return null;
        for (Volume v : outline.get().getVolumes()) {
            for (ChapterSynopsis s : v.getSynopses()) {
                if (s.getChapterIndex() == chapterIndex) return s;
            }
        }
        return null;
    }

    private String buildTaskSection(String chapterTitle, String synopsis, String writingNotes,
                                     ChapterSynopsis fullSynopsis) {
        StringBuilder sb = new StringBuilder();
        sb.append("章节：").append(chapterTitle).append("\n");
        // 如果有结构化信息，分段输出
        if (fullSynopsis != null) {
            if (fullSynopsis.getBeats() != null && !fullSynopsis.getBeats().isBlank()) {
                sb.append("场景节拍：").append(fullSynopsis.getBeats()).append("\n");
            } else if (synopsis != null) {
                sb.append("内容：").append(synopsis).append("\n");
            }
            if (fullSynopsis.getClimax() != null && !fullSynopsis.getClimax().isBlank()) {
                sb.append("爽点：").append(fullSynopsis.getClimax()).append("\n");
            }
            if (fullSynopsis.getCast() != null && !fullSynopsis.getCast().isBlank()) {
                sb.append("出场人物：").append(fullSynopsis.getCast()).append("\n");
            }
            if (fullSynopsis.getPlotPurpose() != null && !fullSynopsis.getPlotPurpose().isBlank()) {
                sb.append("剧情目的：").append(fullSynopsis.getPlotPurpose()).append("\n");
            }
            if (fullSynopsis.getTensionLevel() > 0) {
                sb.append("张力等级：").append(fullSynopsis.getTensionLevel()).append("/10\n");
            }
        } else {
            // 回退到扁平模式
            sb.append("内容：").append(synopsis != null ? synopsis : "").append("\n");
        }
        if (writingNotes != null && !writingNotes.isBlank()) {
            sb.append("要求：").append(writingNotes);
        }
        return sb.toString().trim();
    }

    // --- 图数据库：实体一致性注入 ---

    private String buildEntityConsistencySection(String projectId, int chapterIndex, List<String> cast) {
        if (entityGraphService == null) return "";
        return entityGraphService.recallConsistencySection(projectId, chapterIndex, cast);
    }

    // --- 量化写作约束（含对话密度） ---

    private String buildWritingConstraints(String projectId, int chapterIndex, ChapterSynopsis synopsis) {
        StringBuilder sb = new StringBuilder();
        sb.append("【本章刚性约束（逐条检查，遗漏即违规）】\n");

        // p17-02: 题材范式条件化——快节奏题材保留网文硬约束（现状），
        // 慢节奏/严肃题材（LITERARY/HISTORICAL/ROMANCE/SCIFI/FANTASY）换用张力推进指引
        com.writing.novel.domain.GenreProfile genre = repository.findProject(projectId)
                .map(com.writing.novel.domain.Project::getGenre)
                .orElse(com.writing.novel.domain.GenreProfile.GENERAL);
        boolean fastPaced = genre == null || genre.isFastPaced();

        // 0. 对话约束（从 WritingPreferences 读取密度目标）
        var prefs = repository.findWritingPreferences(projectId);
        double density = prefs.map(p -> p.getDialogueDensity()).orElse(0.35);
        boolean enforceDialogue = prefs.map(p -> p.isEnableDialogueEnforcement()).orElse(true);
        if (enforceDialogue) {
            int pct = (int)(density * 100);
            sb.append("□ 本章对话占比不得低于 ").append(pct)
              .append("%（以引号\"标注的对话内容计算），对话必须推动情节或展现角色性格\n")
              .append("□ 每个出场角色至少说 2 句话，且符合其说话风格\n")
              .append("□ 禁止连续 3 段纯叙述无对话\n");
        }

        // 1. 爽点约束（p17-02: 仅快节奏题材；慢节奏题材换张力推进指引）
        boolean rotateClimax = prefs.map(p -> p.isEnableClimaxRotation()).orElse(true);
        if (fastPaced) {
            sb.append("□ 必须包含至少 2 个爽点");
            if (rotateClimax) {
                sb.append("（类型须与前章轮换）");
            }
            sb.append("\n");

            // 1b. 爽点轮换约束（来自 ClimaxDiversityTracker 实际历史）
            if (rotateClimax) {
                String rotation = climaxDiversityTracker.generateRotationConstraint();
                if (rotation != null && !rotation.isBlank()) {
                    sb.append(rotation);
                }
            }
        } else {
            sb.append("□ 本章必须包含至少 1 个核心张力点（冲突升级/情感转折/真相揭示/关键抉择），")
              .append("并在章内完成一次实质推进（不能只铺陈不推进）\n");
        }

        // 2. 世界观约束
        sb.append("□ 必须使用至少 1 个世界观独特设定\n");

        // 3. 节拍覆盖 + 信息密度上限
        if (synopsis != null && synopsis.getBeats() != null && !synopsis.getBeats().isBlank()) {
            String[] beats = synopsis.getBeats().split("→");
            sb.append("□ 场景节拍必须全部覆盖（共 ").append(beats.length)
              .append(" 个：").append(synopsis.getBeats()).append("）\n");
            // p10-b1: 信息密度约束
            int maxBeats = prefs.map(com.writing.novel.domain.WritingPreferences::getMaxBeatsPerChapter).orElse(4);
            if (beats.length > maxBeats) {
                sb.append("□ ⚠️ 本章场景节拍数(").append(beats.length)
                  .append(")超过上限(").append(maxBeats)
                  .append(")，信息密度过高。请合并部分节拍或聚焦核心事件，不要试图在一章内解决所有问题\n");
            }
            sb.append("□ 本章核心事件/转折不超过 ").append(maxBeats)
              .append(" 件——宁可深入刻画少数场景，也不要走马观花堆砌事件\n");
        }

        // 4. 悬念结尾（p17-02: 仅快节奏题材强制钩子；慢节奏题材允许余韵收束）
        if (fastPaced) {
            sb.append("□ 章节结尾必须是一个悬念钩子，不能是场景自然结束\n");
        } else {
            sb.append("□ 章节结尾应留有未解张力或余韵（新问题/未决选择/情感余波），不必是强钩子，但不得是平淡收场\n");
        }

        // 4b. 主角不完美约束（p9-10）
        sb.append("□ 主角在本章中必须面临至少一次真正的困境或误判（不能事事算无遗策）\n");

        // p11.1-J1: 字数上限约束
        int maxLen = prefs.map(p -> p.getMaxChapterLength()).orElse(6000);
        sb.append("□ 本章正文不超过 ").append(maxLen)
          .append(" 字。超过此限请精简次要场景和描写，宁可少写也不要注水\n");

        // 5. 多维度多样性约束（开场/冲突/爽点轮换；p17-02: 慢节奏题材措辞换「高潮类型」）
        if (diversityTracker != null) {
            String diversityConstraints = diversityTracker.generateAllConstraints(fastPaced);
            if (diversityConstraints != null && !diversityConstraints.isBlank()) {
                sb.append(diversityConstraints);
            }
        }

        // 6. p10-d1: 套路词热度控制——禁用最近高频反应词
        if (clicheDetector != null) {
            String forbidden = clicheDetector.generateForbiddenList(projectId, chapterIndex);
            if (forbidden != null && !forbidden.isBlank()) {
                sb.append(forbidden);
            }
        }

        // ec-02: 时代一致性护栏——历史题材禁止其他朝代年号/器物/典籍/官制混用
        String eraConstraint = buildEraConstraint(projectId);
        if (eraConstraint != null && !eraConstraint.isBlank()) {
            sb.append(eraConstraint);
        }

        // cn-03: 主角名锚点——防止正文与人物卡改名漂移
        String nameAnchor = buildProtagonistNameConstraint(projectId);
        if (nameAnchor != null && !nameAnchor.isBlank()) {
            sb.append(nameAnchor);
        }

        // op-01: 开篇节奏加速——前 N 章强制快速抓人（p17-02: 仅快节奏题材爽点式高压；
        // 慢节奏题材仍要求快速进入具体场景，但不强制爽点/钩子密度）
        if (chapterIndex <= OPENING_CHAPTERS) {
            if (fastPaced) {
                sb.append("□ 【开篇加速·硬约束】本章是全书前").append(OPENING_CHAPTERS)
                  .append("章之一，必须快速抓住读者：\n")
                  .append("  - 首 500 字内必须出现冲突/悬念/反转，禁止用大段环境描写或世界观解说开场\n")
                  .append("  - 本章至少 2 个爽点，冲突必须在章内升级并部分解决（不能只铺悬念不推进）\n")
                  .append("  - 禁止「喘息章」节奏——每一段都要推进剧情或深化人物，不得纯氛围渲染\n");
            } else {
                sb.append("□ 【开篇要求】本章是全书前").append(OPENING_CHAPTERS)
                  .append("章之一：前 800 字内进入具体场景与人物处境，禁止大段设定解说/环境铺陈/家族谱系罗列开场；")
                  .append("以事件或人物处境切入，张力随章内递进\n");
            }
        }

        // cd-01: 配角刻画深度——禁止配角沦为功能性工具人
        String depthConstraint = buildCharacterDepthConstraint(projectId, synopsis);
        if (depthConstraint != null && !depthConstraint.isBlank()) {
            sb.append(depthConstraint);
        }

        // pd-01: 事件递进模式多样性——禁止与前章相同的事件触发→展开→结局结构
        if (chapterIndex > 1) {
            sb.append("□ 【事件结构多样性】本章不得与前章使用相同的「事件触发→调查验证→爆发收束」递进模板。\n")
              .append("  如果前章以「异象出现→主角验证→人物死亡/失踪」推进，本章必须换用不同的叙事结构\n")
              .append("  （如：对话博弈推动、倒叙揭秘、多视角交叉、主动设局而非被动反应）。\n");
        }

        return sb.toString();
    }

    /**
     * cd-01: 配角刻画深度约束——要求出场配角展现独立性格/动机，而非纯功能性。
     */
    private String buildCharacterDepthConstraint(String projectId, ChapterSynopsis synopsis) {
        try {
            if (synopsis == null || synopsis.getCast() == null || synopsis.getCast().isBlank()) return "";
            String cast = synopsis.getCast();
            var supporting = knowledgeBase.findAllCharacters(projectId).stream()
                    .filter(c -> !isMainCharacter(c))
                    .filter(c -> cast.contains(c.getName()))
                    .toList();
            if (supporting.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            sb.append("□ 【配角刻画深度】本章出场的配角不得沦为推动情节的功能性工具人：\n");
            for (var c : supporting) {
                sb.append("  ■ ").append(c.getName());
                if (c.getPersonality() != null && !c.getPersonality().isBlank()) {
                    sb.append("（性格：").append(c.getPersonality()).append("）");
                }
                if (c.getMotivation() != null && !c.getMotivation().isBlank()) {
                    sb.append("（动机：").append(c.getMotivation()).append("）");
                }
                sb.append("\n");
            }
            sb.append("  每个出场配角至少有 1 处展现独立性格或个人动机的反应/台词/行为，"
                    + "不得只作为信息提供者或情节推进器。\n");
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * cn-03: 主角名锚点约束。从人物卡取主角名，强制正文使用此名。
     */
    private String buildProtagonistNameConstraint(String projectId) {
        try {
            var mc = knowledgeBase.findAllCharacters(projectId).stream()
                    .filter(this::isMainCharacter)
                    .map(c -> c.getName())
                    .findFirst().orElse(null);
            if (mc == null || mc.isBlank()) return "";
            return "□ 【主角名锚点·硬约束】主角名为「" + mc + "」，正文必须始终使用此名，"
                    + "不得改用其他字、别号或别名指代主角。\n";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * ec-02: 推导时代锚点并生成写作约束。无时代信号（架空/修仙/现代）返回空串。
     */
    private String buildEraConstraint(String projectId) {
        try {
            var entries = repository.findKnowledgeEntriesByProject(projectId);
            String concept = repository.findProject(projectId)
                    .map(p -> p.getConcept()).orElse("");
            String anchor = com.writing.novel.service.EraAnchorService.deriveEraAnchor(entries, concept);
            if (anchor == null || anchor.isBlank()) return "";
            StringBuilder sb = new StringBuilder();
            sb.append("□ 【时代一致性·硬约束】本作品时代设定：").append(anchor).append("。\n");
            if (anchor.startsWith("⚠️")) {
                sb.append("  上方检测到多朝代信号混用，本章必须统一为提示中最高频的朝代，"
                        + "不得再出现其他朝代的年号/器物/典籍/官制/服饰/称谓。\n");
            } else {
                sb.append("  本章正文中出现的年号、钱币、典籍、官制、服饰、称谓、典章制度"
                        + "必须与").append(anchor).append("一致，禁止引入其他朝代的同类名词"
                        + "（如不得在明代背景中出现清代年号或清代机构）。\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // --- 角色声音注入 ---

    private String buildCharacterVoiceSection(String projectId, ChapterSynopsis synopsis) {
        var prefs = repository.findWritingPreferences(projectId);
        if (prefs.isEmpty() || !prefs.get().isEnableCharacterVoice()) return "";

        String differentiation = prefs.get().getDialogueDifferentiation();
        if ("none".equals(differentiation)) return "";

        var allChars = knowledgeBase.findAllCharacters(projectId);
        // p10-w2-05: 经 RelevanceGateway 收窄（cast 匹配 + 主角保底 + 无全量回退），
        // 替代 cast 过滤失败即回退全量的旧逻辑（百万字下 token 爆炸）
        List<Character> relevant;
        if (relevanceGateway != null && synopsis != null && synopsis.getCast() != null && !synopsis.getCast().isBlank()) {
            List<String> castList = java.util.Arrays.asList(synopsis.getCast().split("[,，、\\s]+"));
            relevant = relevanceGateway.relevantCharacters(projectId, castList, 8);
        } else {
            relevant = allChars; // 无 cast 或无 gateway：旧路径（短篇规模可接受）
        }
        var withVoice = relevant.stream()
                .filter(c -> c.getSpeakingStyle() != null && !c.getSpeakingStyle().isBlank())
                .toList();
        if (withVoice.isEmpty()) return "";

        List<Character> filtered = withVoice;

        StringBuilder sb = new StringBuilder();
        sb.append("【角色说话风格要求（必须严格遵守差异化，不同角色的语气/语速/用词须有明显区别）】\n");
        for (var c : filtered) {
            sb.append("■ ").append(c.getName()).append("：").append(c.getSpeakingStyle()).append("\n");
        }

        // 对比模式：当 ≥2 个角色时生成配对对比提示
        if ("contrast".equals(differentiation) && filtered.size() >= 2) {
            sb.append("⚠️ 差异化要求：");
            // 取前 2-3 个角色生成对比
            int pairs = Math.min(filtered.size(), 3);
            List<String> contrastParts = new java.util.ArrayList<>();
            for (int i = 0; i < pairs - 1; i++) {
                var a = filtered.get(i);
                var b = filtered.get(i + 1);
                // 从 speakingStyle 提取关键词对比
                contrastParts.add(a.getName() + "与" + b.getName() + "的说话方式必须形成明显对比");
            }
            sb.append(String.join("；", contrastParts));
            sb.append("——一个角色不能模仿另一个角色的语气\n");
        }

        return sb.toString();
    }

    // --- 引用素材注入 ---

    private String buildKnowledgeQuoteSection(String projectId) {
        return cached("knowledgeQuote", projectId, () -> buildKnowledgeQuoteUncached(projectId));
    }

    private String buildKnowledgeQuoteUncached(String projectId) {
        var prefs = repository.findWritingPreferences(projectId);
        if (prefs.isEmpty() || !prefs.get().isEnableKnowledgeQuotes()) return "";

        // 找有知识领域的角色
        var chars = knowledgeBase.findAllCharacters(projectId);
        var withKnowledge = chars.stream()
                .filter(c -> c.getKnowledgeDomains() != null && !c.getKnowledgeDomains().isBlank())
                .toList();
        if (withKnowledge.isEmpty()) return "";

        // 从 KnowledgeEntry 中匹配关键词
        var entries = repository.findKnowledgeEntriesByProject(projectId);
        if (entries.isEmpty()) return "";

        // p17-04: 消费 preferredQuoteSources——预设声明的引用来源（如悬疑=法医学、武侠=武侠典籍）优先前置
        var preferred = prefs.get().getPreferredQuoteSources() == null
                ? java.util.List.<String>of()
                : java.util.Arrays.stream(prefs.get().getPreferredQuoteSources().split("[,，、]"))
                        .map(String::trim).filter(s -> !s.isEmpty()).toList();
        StringBuilder sb = new StringBuilder();
        sb.append("【可用引用素材（角色可自然引用以下内容展现学识）】\n");
        if (!preferred.isEmpty()) {
            var preferredEntries = entries.stream()
                    .filter(e -> preferred.stream().anyMatch(p ->
                            (e.getName() != null && e.getName().contains(p))
                                    || (e.getType() != null && p.contains(e.getType()))))
                    .limit(3)
                    .toList();
            if (!preferredEntries.isEmpty()) {
                sb.append("题材引用偏好（").append(String.join("、", preferred)).append("）：\n");
                for (var e : preferredEntries) {
                    String quote = e.getContent().length() > 60 ? e.getContent().substring(0, 60) + "..." : e.getContent();
                    sb.append("■ ").append(e.getName()).append("：").append(quote).append("\n");
                }
            }
        }
        for (var c : withKnowledge) {
            String domains = c.getKnowledgeDomains();
            // 找到匹配的知识条目
            var matched = entries.stream()
                    .filter(e -> domains.contains(e.getType()) || domains.contains(e.getName())
                            || e.getName().contains(c.getName().substring(0, Math.min(2, c.getName().length()))))
                    .limit(3)
                    .toList();
            if (!matched.isEmpty()) {
                sb.append("角色「").append(c.getName()).append("」精通").append(domains).append("，可引用：\n");
                for (var e : matched) {
                    String quote = e.getContent().length() > 60 ? e.getContent().substring(0, 60) + "..." : e.getContent();
                    sb.append("■ ").append(e.getName()).append("：").append(quote).append("\n");
                }
            }
        }
        return sb.toString();
    }

    // --- 质量反馈闭环 ---

    private String buildQualityFeedbackSection(String projectId) {
        var eval = repository.findLatestEvaluation(projectId);
        if (eval.isEmpty()) return "";

        EvaluationRecord record = eval.get();
        // 只在低分维度（< 7）时注入反馈
        List<String> lowDims = record.getLowScoreDimensions(7);
        if (lowDims.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("【上卷质量评价反馈——改进方向】\n");
        sb.append(record.formatFeedback());
        return sb.toString();
    }

    // --- 反馈闭环：注入历史 ContinuityIssue ---

    private String buildNarrativeThreadSection(String projectId, List<String> cast) {
        if (narrativeThreadTracker == null) return "";
        return narrativeThreadTracker.getThreadSection(projectId, cast);
    }

    /**
     * p11-H4: 构建角色关系网络注入——展示本章出场角色之间的当前关系。
     * 让 LLM 知道角色 A 和 B 之间是"暗中利用"还是"真心盟友"。
     */
    private String buildRelationshipWeb(String projectId, ChapterSynopsis synopsis) {
        if (synopsis == null || synopsis.getCast() == null || synopsis.getCast().isBlank()) return "";
        String cast = synopsis.getCast();

        var edges = repository.findEdgesByProject(projectId);
        if (edges.isEmpty()) return "";

        // 获取所有 CHARACTER 实体
        var characters = repository.findEntitiesByProject(projectId).stream()
                .filter(e -> e.getType() == EntityType.CHARACTER)
                .toList();
        var nameToId = new java.util.HashMap<String, String>();
        var idToName = new java.util.HashMap<String, String>();
        for (var c : characters) {
            nameToId.put(c.getName(), c.getId());
            idToName.put(c.getId(), c.getName());
        }

        // 找出 cast 中出现的角色之间的关系
        var relevantEdges = edges.stream()
                .filter(EntityEdge::isCurrent)
                .filter(e -> {
                    String srcName = idToName.get(e.getSourceId());
                    String tgtName = idToName.get(e.getTargetId());
                    return srcName != null && tgtName != null
                            && (cast.contains(srcName) || cast.contains(tgtName));
                })
                .toList();

        if (relevantEdges.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("【角色关系网络——本章出场角色之间的当前关系】\n");
        for (var edge : relevantEdges) {
            String srcName = idToName.getOrDefault(edge.getSourceId(), "?");
            String tgtName = idToName.getOrDefault(edge.getTargetId(), "?");
            sb.append("■ ").append(srcName).append(" → ").append(tgtName)
                    .append("：").append(edge.getRelationship());
            if (edge.getValidFromChapter() > 0) {
                sb.append("（自第").append(edge.getValidFromChapter()).append("章）");
            }
            if (edge.getMetadata() != null && !edge.getMetadata().isBlank()) {
                sb.append(" — ").append(edge.getMetadata());
            }
            sb.append("\n");
        }
        sb.append("本章的对话和互动应体现上述关系设定。\n");
        return sb.toString().trim();
    }

    private String buildFeedbackSection(String projectId, int chapterIndex) {
        var prefs = repository.findWritingPreferences(projectId);
        if (prefs.isEmpty() || !prefs.get().isEnableFeedbackInjection()) return "";

        int window = prefs.get().getFeedbackWindow();
        int fromChapter = Math.max(1, chapterIndex - window);

        // 查询近 N 章的未解决 ContinuityIssue
        var issues = repository.findUnresolvedIssues(projectId).stream()
                .filter(i -> i.getDetectedAtChapter() >= fromChapter)
                .filter(i -> i.getDetectedAtChapter() < chapterIndex) // 不含本章
                .sorted(Comparator
                        .comparingInt((ContinuityIssue issue) ->
                                issue.getSeverity() == ContinuityIssue.Severity.HIGH ? 0 : 1)
                        .thenComparing(Comparator.comparingInt(ContinuityIssue::getDetectedAtChapter).reversed())
                        .thenComparing(issue -> issue.getType().name())
                        .thenComparing(ContinuityIssue::getDescription))
                .limit(8) // 最多 8 条
                .toList();

        if (issues.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("【历史反馈（以下问题在近期章节中被发现，本章应避免重犯）】\n");
        for (var issue : issues) {
            sb.append("■ [").append(issue.getSeverity()).append("] ");
            sb.append(issue.getType()).append("：");
            // NCS-101: 风格漂移报告不截断——漂移点与高频词清单是下一章纠正的全部依据（旧 80 字截断恰好截掉）；
            // 其余类型维持 80 字上限控制预算
            String desc = issue.getDescription();
            boolean fullText = issue.getType() == ContinuityIssue.Type.STYLE;
            sb.append(!fullText && desc.length() > 80 ? desc.substring(0, 80) + "..." : desc);
            sb.append("\n");
        }

        // p11-I4: 注入上一章读者评审反馈
        if (chapterIndex > 1) {
            var prevReview = repository.findChapterReview(projectId, chapterIndex - 1);
            if (prevReview.isPresent()) {
                var rv = prevReview.get();
                if (!rv.isPassed()) {
                    sb.append("\n【上一章读者评价（").append(rv.getOverallScore())
                      .append("/10，未达标）改进建议】\n");
                    if (rv.getImprovement() != null && !rv.getImprovement().isBlank()) {
                        String imp = rv.getImprovement();
                        sb.append(imp.length() > 200 ? imp.substring(0, 200) + "..." : imp).append("\n");
                    }
                    if (rv.getIssues() != null && !rv.getIssues().isBlank()) {
                        sb.append("上章问题：").append(rv.getIssues()).append("\n");
                    }
                }
            }
        }

        return sb.toString();
    }

    void applyTokenBudget(WritingContext ctx, int budget) {
        int total = sumTokens(ctx);
        if (total <= budget) return;

        if (total > budget && ctx.getKnowledgeQuoteSection() != null) {
            ctx.setKnowledgeQuoteSection("");
            total = sumTokens(ctx);
        }
        if (total > budget && ctx.getQualityFeedbackSection() != null) {
            ctx.setQualityFeedbackSection("");
            total = sumTokens(ctx);
        }
        if (total > budget && ctx.getCharacterVoiceSection() != null) {
            ctx.setCharacterVoiceSection("");
            total = sumTokens(ctx);
        }
        if (total > budget && ctx.getNarrativeThreadSection() != null) {
            ctx.setNarrativeThreadSection(TokenBudget.truncateToTokens(
                    ctx.getNarrativeThreadSection(), scaled(400, budget)));
            total = sumTokens(ctx);
        }
        // NCS-112: relevantSettings 从「整体置空」改为按预算截断——长程召回在预算紧张时
        // 最先全灭的矛盾修复（保留头部高分配额内容）
        if (total > budget && ctx.getRelevantSettings() != null) {
            ctx.setRelevantSettings(TokenBudget.truncateToTokens(
                    ctx.getRelevantSettings(), scaled(1200, budget)));
            total = sumTokens(ctx);
        }
        if (total > budget && ctx.getWorldbuildingSection() != null) {
            ctx.setWorldbuildingSection(TokenBudget.truncateToTokens(
                    ctx.getWorldbuildingSection(), scaled(2000, budget)));
            total = sumTokens(ctx);
        }
        if (total > budget && ctx.getVolumeOutlineSection() != null) {
            ctx.setVolumeOutlineSection(TokenBudget.truncateToTokens(
                    ctx.getVolumeOutlineSection(), scaled(1500, budget)));
            total = sumTokens(ctx);
        }
        if (total > budget && ctx.getRecentTextSection() != null) {
            ctx.setRecentTextSection(TokenBudget.truncateToTokens(
                    ctx.getRecentTextSection(), scaled(800, budget)));
            total = sumTokens(ctx);
        }
        if (total > budget && ctx.getRecentSummarySection() != null) {
            ctx.setRecentSummarySection(TokenBudget.truncateToTokens(
                    ctx.getRecentSummarySection(), scaled(4000, budget)));
            total = sumTokens(ctx);
        }
        if (total > budget && ctx.getRelevantForeshadowings() != null) {
            ctx.setRelevantForeshadowings(TokenBudget.truncateToTokens(
                    ctx.getRelevantForeshadowings(), scaled(500, budget)));
            total = sumTokens(ctx);
        }
        // p16-06-B: premise（故事原旨/总纲）最后参与裁剪——链尾=最低优先级，仅预算极端紧张时触发。
        // 「【故事原旨】永不裁剪」的设计针对 premise 的核心段（立意/主线位于文本头部）：
        // 这里只对超过 6000 token 的尾部冗余（如 50 卷逐卷累积的总纲）截断，核心原旨在头部自然保留。
        // 注意：截断发生在 stableSectionCache 缓存之后的预算阶段，只改 WritingContext，
        // 不影响 premise 缓存（MemoryRevision 失效逻辑）中保存的完整原文。
        if (total > budget && ctx.getPremiseSection() != null) {
            ctx.setPremiseSection(truncatePremiseHead(ctx.getPremiseSection()));
        }
    }

    /** p16-06-B: premise 参与预算裁剪时的截断上限（token） */
    private static final int PREMISE_MAX_TOKENS = 6000;

    /**
     * p16-06-B: premise 超 6000 token 时保头部截断并加显式标记。
     * 与通用 truncateToTokens 的 "...（截断）" 区分开，让写作侧能识别总纲被裁。
     */
    private static String truncatePremiseHead(String premise) {
        if (premise == null) return premise;
        int maxChars = (int) (PREMISE_MAX_TOKENS * 1.5);
        if (premise.length() <= maxChars) return premise;
        return premise.substring(0, maxChars) + "……[总纲已截断]";
    }

    private int sumTokens(WritingContext ctx) {
        return TokenBudget.estimateTokens(ctx.toSystemPrompt())
                + TokenBudget.estimateTokens(ctx.toUserPrompt());
    }
}
