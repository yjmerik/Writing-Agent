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
import java.util.stream.Collectors;

/**
 * 上下文组装器 — 实现 spec 第 5 节的上下文组装公式。
 * 把三层记忆 + 风格 + 任务拼装成 ChapterWriter 所需的完整上下文。
 */
public class ContextAssembler {

    private static final int RETRIEVAL_TOP_K = 5;
    private static final int RECENT_TEXT_CHARS = 2000;
    private static final int RECENT_SUMMARY_WINDOW = 5;

    private final NovelRepository repository;
    private final KnowledgeBaseService knowledgeBase;
    private final SummaryService summaryService;
    private final VectorRetrievalService vectorRetrieval;
    private final EntityGraphService entityGraphService;
    private final NarrativeThreadTracker narrativeThreadTracker;
    private final DiversityTracker diversityTracker;
    private final ClimaxDiversityTracker climaxDiversityTracker;
    private final ClicheDetector clicheDetector;

    public ContextAssembler(NovelRepository repository,
                            KnowledgeBaseService knowledgeBase,
                            SummaryService summaryService,
                            VectorRetrievalService vectorRetrieval,
                            EntityGraphService entityGraphService,
                            ClimaxDiversityTracker climaxDiversityTracker,
                            NarrativeThreadTracker narrativeThreadTracker,
                            DiversityTracker diversityTracker,
                            ClicheDetector clicheDetector) {
        this.repository = repository;
        this.knowledgeBase = knowledgeBase;
        this.summaryService = summaryService;
        this.vectorRetrieval = vectorRetrieval;
        this.entityGraphService = entityGraphService;
        this.narrativeThreadTracker = narrativeThreadTracker;
        this.diversityTracker = diversityTracker;
        this.climaxDiversityTracker = climaxDiversityTracker;
        this.clicheDetector = clicheDetector;
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

        // 【故事原旨】最高优先级注入——让 LLM 永远知道这本书讲什么
        ctx.setPremiseSection(buildPremiseSection(projectId));

        repository.findStyleProfileByProject(projectId)
                .ifPresent(style -> ctx.setStyleSection(buildStyleSection(style)));

        ctx.setWorldbuildingSection(buildWorldbuildingSection(projectId));
        ctx.setVolumeOutlineSection(buildVolumeOutline(projectId, chapterIndex));
        ctx.setRecentSummarySection(buildRecentSummary(projectId, chapterIndex));
        ctx.setRecentTextSection(buildRecentText(projectId, chapterIndex));

        String queryText = chapterSynopsis != null ? chapterSynopsis : chapterTitle;
        assembleRetrievedContext(projectId, queryText, ctx);
        ctx.setRelevantForeshadowings(buildForeshadowings(projectId, chapterIndex));
        // 查找当前章节的结构化大纲信息（beats/climax/cast）
        ChapterSynopsis fullSynopsis = findFullSynopsis(projectId, chapterIndex);
        ctx.setTaskSection(buildTaskSection(chapterTitle, chapterSynopsis, writingNotes, fullSynopsis));

        // 实体一致性注入
        ctx.setEntityConsistencySection(buildEntityConsistencySection(projectId, chapterIndex));

        // 写作偏好：对话约束（含密度目标）+ 原有的量化约束
        ctx.setWritingConstraints(buildWritingConstraints(projectId, chapterIndex, fullSynopsis));

        // 角色声音注入（说话风格 + 知识领域）
        ctx.setCharacterVoiceSection(buildCharacterVoiceSection(projectId, fullSynopsis));

        // 引用素材注入（与角色知识领域匹配的世界观条目）
        ctx.setKnowledgeQuoteSection(buildKnowledgeQuoteSection(projectId));

        // 叙事线索追踪
        ctx.setNarrativeThreadSection(buildNarrativeThreadSection(projectId));

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
        applyTokenBudget(ctx, maxContextTokens);

        return ctx;
    }

    private String buildStyleSection(StyleProfile style) {
        StringBuilder sb = new StringBuilder();
        if (style.getTone() != null) sb.append("基调：").append(style.getTone()).append("\n");
        if (style.getVoice() != null) sb.append("叙事：").append(style.getVoice()).append("\n");
        if (style.getDosAndDonts() != null) sb.append("注意事项：").append(style.getDosAndDonts());
        return sb.toString();
    }

    /** 构建故事原旨：总纲 + 构思，作为全书最高纲领 */
    private String buildPremiseSection(String projectId) {
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
    }

    /** 世界观按类型多样选样：每种 type 最多 2 条，优先级排序 */
    private static final java.util.Map<String, Integer> TYPE_PRIORITY = java.util.Map.of(
            "POWER_SYSTEM", 1, "FACTION", 2, "GEOGRAPHY", 3, "RULE", 4,
            "HISTORY", 5, "CULTURE", 6, "ECONOMY", 7, "RACE", 8, "CUSTOM", 9
    );

    private String buildWorldbuildingSection(String projectId) {
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
        if (fineGrained.isEmpty() && volumeLevel.isEmpty()) return "";

        // p12-K4: 卷摘要按章节倒序排列，最近 3 卷详细，更早的截断
        volumeLevel.sort((a, b) -> Integer.compare(b.getFromChapter(), a.getFromChapter()));
        int VOLUME_DETAIL_LIMIT = 3;

        StringBuilder sb = new StringBuilder();
        // 先注入卷级摘要（远期剧情概览）
        if (!volumeLevel.isEmpty()) {
            sb.append("【前卷概览】\n");
            for (int i = 0; i < volumeLevel.size(); i++) {
                Summary s = volumeLevel.get(i);
                sb.append("第").append(s.getFromChapter()).append("-")
                        .append(s.getToChapter()).append("章：");
                if (i < VOLUME_DETAIL_LIMIT) {
                    // 最近 3 卷详细
                    sb.append(s.getContent());
                } else {
                    // 更早的卷截断到前 100 字
                    String content = s.getContent();
                    sb.append(content.length() > 100 ? content.substring(0, 100) + "..." : content);
                    sb.append("（早期卷，已精简）");
                }
                sb.append("\n");
            }
            sb.append("\n");
        }
        // 再注入近期细粒度摘要
        if (!fineGrained.isEmpty()) {
            for (Summary s : fineGrained) {
                sb.append("第").append(s.getFromChapter()).append("-")
                        .append(s.getToChapter()).append("章：")
                        .append(s.getContent()).append("\n");
            }
        }
        return sb.toString().trim();
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

    private void assembleRetrievedContext(String projectId, String queryText, WritingContext ctx) {
        List<Character> allChars = knowledgeBase.findAllCharacters(projectId);
        if (allChars.isEmpty()) return;

        // 向量检索相关人物（try-catch 防止 embedding API 失败中断写作）
        List<String> relevantIds;
        try {
            var searchResults = vectorRetrieval.search(queryText, projectId, RETRIEVAL_TOP_K);
            relevantIds = searchResults.stream().map(r -> r.id()).toList();
        } catch (Exception e) {
            relevantIds = List.of();
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

        StringBuilder settings = new StringBuilder();
        for (String id : relevantIds) {
            if (id.contains("#chunk")) {
                // 章节正文 chunk：从向量库取文本（findKnowledgeEntry 查不到）
                String chunkText = vectorRetrieval.getText(id);
                if (chunkText != null) {
                    settings.append(chunkText).append("\n");
                }
            } else {
                // 知识条目：从 DB 取
                repository.findKnowledgeEntry(id).ifPresent(e -> {
                    settings.append("【").append(e.getName()).append("】")
                            .append(e.getContent()).append("\n");
                });
            }
        }
        ctx.setRelevantSettings(settings.toString().trim());
    }

    /** 判断是否主角（用于强制锚定） */
    private boolean isMainCharacter(Character c) {
        String role = c.getRole();
        return role != null && (role.contains("主角") || role.contains("男主") || role.contains("女主"));
    }

    private String buildForeshadowings(String projectId, int chapterIndex) {
        List<Foreshadowing> unresolved = repository.findUnresolvedForeshadowings(projectId);
        if (unresolved.isEmpty()) return "";

        final int currentChapter = chapterIndex;
        final int LIMIT = 10;

        // p10-c1: 排序策略改革——
        // 1. HIGH priority 或 mustResolve 的伏笔优先注入
        // 2. p12-K4: HIGH 伏笔也设上限（最多 15 条），超过的按挂得最久优先
        // 3. 其余按"挂得最久"（currentChapter - plantedAtChapter）降序，取 top LIMIT
        var mustInclude = unresolved.stream()
                .filter(f -> f.getPriority() == Foreshadowing.Priority.HIGH || f.isMustResolve())
                .sorted((a, b) -> Integer.compare(
                        b.suspenseChapters(currentChapter), a.suspenseChapters(currentChapter)))
                .limit(15)
                .toList();
        var others = unresolved.stream()
                .filter(f -> f.getPriority() != Foreshadowing.Priority.HIGH && !f.isMustResolve())
                .sorted((a, b) -> Integer.compare(
                        b.suspenseChapters(currentChapter), a.suspenseChapters(currentChapter)))
                .limit(Math.max(0, LIMIT - mustInclude.size()))
                .toList();

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

    private String buildEntityConsistencySection(String projectId, int chapterIndex) {
        return entityGraphService.recallConsistencySection(projectId, chapterIndex);
    }

    // --- 量化写作约束（含对话密度） ---

    private String buildWritingConstraints(String projectId, int chapterIndex, ChapterSynopsis synopsis) {
        StringBuilder sb = new StringBuilder();
        sb.append("【本章刚性约束（逐条检查，遗漏即违规）】\n");

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

        // 1. 爽点约束
        boolean rotateClimax = prefs.map(p -> p.isEnableClimaxRotation()).orElse(true);
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

        // 4. 悬念结尾
        sb.append("□ 章节结尾必须是一个悬念钩子，不能是场景自然结束\n");

        // 4b. 主角不完美约束（p9-10）
        sb.append("□ 主角在本章中必须面临至少一次真正的困境或误判（不能事事算无遗策）\n");

        // p11.1-J1: 字数上限约束
        int maxLen = prefs.map(p -> p.getMaxChapterLength()).orElse(6000);
        sb.append("□ 本章正文不超过 ").append(maxLen)
          .append(" 字。超过此限请精简次要场景和描写，宁可少写也不要注水\n");

        // 5. 多维度多样性约束（开场/冲突/爽点轮换）
        if (diversityTracker != null) {
            String diversityConstraints = diversityTracker.generateAllConstraints();
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

        return sb.toString();
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
        var withVoice = allChars.stream()
                .filter(c -> c.getSpeakingStyle() != null && !c.getSpeakingStyle().isBlank())
                .toList();
        if (withVoice.isEmpty()) return "";

        // 按 cast 过滤（如果 synopsis 有 cast 信息）
        List<Character> filtered = withVoice;
        if (synopsis != null && synopsis.getCast() != null && !synopsis.getCast().isBlank()) {
            String cast = synopsis.getCast();
            filtered = withVoice.stream()
                    .filter(c -> cast.contains(c.getName()) || c.getName().contains(cast))
                    .toList();
            // 如果 cast 过滤后为空（可能 cast 格式不匹配），回退到全部
            if (filtered.isEmpty()) filtered = withVoice;
        }

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

        StringBuilder sb = new StringBuilder();
        sb.append("【可用引用素材（角色可自然引用以下内容展现学识）】\n");
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

    private String buildNarrativeThreadSection(String projectId) {
        if (narrativeThreadTracker == null) return "";
        return narrativeThreadTracker.getThreadSection(projectId);
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
                .sorted((a, b) -> {
                    // HIGH 优先，然后按章节倒序
                    int sa = a.getSeverity() == ContinuityIssue.Severity.HIGH ? 0 : 1;
                    int sb2 = b.getSeverity() == ContinuityIssue.Severity.HIGH ? 0 : 1;
                    if (sa != sb2) return sa - sb2;
                    return Integer.compare(b.getDetectedAtChapter(), a.getDetectedAtChapter());
                })
                .limit(8) // 最多 8 条
                .toList();

        if (issues.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("【历史反馈（以下问题在近期章节中被发现，本章应避免重犯）】\n");
        for (var issue : issues) {
            sb.append("■ [").append(issue.getSeverity()).append("] ");
            sb.append(issue.getType()).append("：");
            // 截取描述前 80 字
            String desc = issue.getDescription();
            sb.append(desc.length() > 80 ? desc.substring(0, 80) + "..." : desc);
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

    /** Token 预算控制：超限时按优先级从低到高裁剪 */
    private void applyTokenBudget(WritingContext ctx, int budget) {
        int total = sumTokens(ctx);
        if (total <= budget) return;

        // p5-c4: 调整优先级——伏笔不再最先丢弃（长程连续性需要）
        // 新优先级（从低到高）：设定>世界观>大纲>上章原文>伏笔
        // 伏笔保留到最后，因为它是长程连续性的核心
        if (total > budget && ctx.getRelevantSettings() != null) {
            ctx.setRelevantSettings("");
            total = sumTokens(ctx);
        }
        if (total > budget && ctx.getWorldbuildingSection() != null) {
            // 世界观截断到 2000 token
            ctx.setWorldbuildingSection(TokenBudget.truncateToTokens(ctx.getWorldbuildingSection(), 2000));
            total = sumTokens(ctx);
        }
        if (total > budget && ctx.getVolumeOutlineSection() != null) {
            ctx.setVolumeOutlineSection(TokenBudget.truncateToTokens(ctx.getVolumeOutlineSection(), 1500));
            total = sumTokens(ctx);
        }
        if (total > budget && ctx.getRecentTextSection() != null) {
            // 上章原文截断到 800 token
            ctx.setRecentTextSection(TokenBudget.truncateToTokens(ctx.getRecentTextSection(), 800));
            total = sumTokens(ctx);
        }
        // 伏笔最后才截断（截断到 500 token 而非清空）
        if (total > budget && ctx.getRelevantForeshadowings() != null) {
            ctx.setRelevantForeshadowings(TokenBudget.truncateToTokens(ctx.getRelevantForeshadowings(), 500));
        }
    }

    private int sumTokens(WritingContext ctx) {
        return TokenBudget.estimateTokens(ctx.toSystemPrompt())
                + TokenBudget.estimateTokens(ctx.toUserPrompt());
    }
}
