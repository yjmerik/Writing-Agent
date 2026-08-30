package com.writing.novel.agent;

import com.writing.novel.domain.Chapter;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmTaskType;
import com.writing.novel.llm.prompt.PromptTemplateService;
import com.writing.novel.memory.ContextAssembler;
import com.writing.novel.memory.IndexedText;
import com.writing.novel.memory.TokenBudget;
import com.writing.novel.memory.VectorRetrievalService;
import com.writing.novel.memory.WritingContext;
import com.writing.novel.service.GenreRuleProvider;
import com.writing.novel.spi.NovelRepository;

import java.util.Map;

/**
 * 章节写作 Agent（核心）。
 * 用 ContextAssembler 组装完整上下文 → 调 LLM 写正文 → 保存 + 索引。
 */
public class ChapterWriterAgent {

    /** op-01: 开篇高压章节数（与 ContextAssembler.OPENING_CHAPTERS 一致） */
    public static final int OPENING_CHAPTERS = 5;

    private final LlmGateway gateway;
    private final ContextAssembler contextAssembler;
    private final NovelRepository repository;
    private final VectorRetrievalService vectorRetrieval;
    private final PromptTemplateService promptService;
    private final GenreRuleProvider genreRuleProvider;
    /** mc-p2-03: 模型配置（用于推导上下文组装预算），可为 null（回退默认预算） */
    private final com.writing.novel.llm.config.LlmProperties llmProperties;

    public ChapterWriterAgent(LlmGateway gateway, ContextAssembler contextAssembler,
                               NovelRepository repository, VectorRetrievalService vectorRetrieval,
                               PromptTemplateService promptService, GenreRuleProvider genreRuleProvider,
                               com.writing.novel.llm.config.LlmProperties llmProperties) {
        this.gateway = gateway;
        this.contextAssembler = contextAssembler;
        this.repository = repository;
        this.vectorRetrieval = vectorRetrieval;
        this.promptService = promptService;
        this.genreRuleProvider = genreRuleProvider;
        this.llmProperties = llmProperties;
    }

    /** 兼容测试的六参构造（vectorRetrieval 为 null 时跳过索引） */
    public ChapterWriterAgent(LlmGateway gateway, ContextAssembler contextAssembler,
                               NovelRepository repository, VectorRetrievalService vectorRetrieval,
                               PromptTemplateService promptService, GenreRuleProvider genreRuleProvider) {
        this(gateway, contextAssembler, repository, vectorRetrieval, promptService, genreRuleProvider, null);
    }

    /** 兼容测试的四参构造（vectorRetrieval 为 null 时跳过索引） */
    public ChapterWriterAgent(LlmGateway gateway, ContextAssembler contextAssembler,
                               NovelRepository repository, PromptTemplateService promptService) {
        this(gateway, contextAssembler, repository, null, promptService, null, null);
    }

    /**
     * mc-p3-01: 章节写作 maxTokens 从模型配置读取（默认 20000）。
     * deepseek-v4-flash 是推理模型，reasoning_content 消耗大量输出 token，
     * 8192 上限会导致 3000+ 字正文被截断（completion 触顶、content 0-1800 字）。
     */
    private int chapterWritingMaxTokens() {
        if (llmProperties == null) return 20000;
        var cfg = llmProperties.forTaskType(LlmTaskType.CHAPTER_WRITING.name());
        return cfg != null && cfg.getMaxTokens() > 0 ? cfg.getMaxTokens() : 20000;
    }

    /** mc-p2-03: 按 CHAPTER_WRITING 模型配置推导上下文组装预算（考虑输出预留） */
    private int chapterWritingBudget() {
        if (llmProperties == null) return TokenBudget.DEFAULT_BUDGET;
        var cfg = llmProperties.forTaskType(LlmTaskType.CHAPTER_WRITING.name());
        if (cfg == null) return TokenBudget.DEFAULT_BUDGET;
        return TokenBudget.budgetForModel(cfg.getContextWindow(), cfg.getMaxTokens());
    }

    public Chapter write(String projectId, int chapterIndex, String chapterTitle,
                         String chapterSynopsis, String writingNotes) {
        // 1. 组装上下文（mc-p2-03: 预算按 CHAPTER_WRITING 模型窗口 90% 推导）
        WritingContext ctx = contextAssembler.assemble(
                projectId, chapterIndex, chapterTitle, chapterSynopsis, writingNotes,
                chapterWritingBudget());

        // 2. 构建 LLM 请求：ContextAssembler 组装的上下文 + chapter-writing 模板的角色定义
        // styleGuide 从 StyleProfile 动态注入（不再写死空串）
        String dynamicStyleGuide = extractStyleGuide(projectId);
        // p5-d3: 获取题材节奏规则
        String genrePacingRules = "";
        if (genreRuleProvider != null) {
            var project = repository.findProject(projectId).orElse(null);
            if (project != null) {
                genrePacingRules = genreRuleProvider.getChapterWritingRules(project.getGenre());
            }
        }
        // p11.1-J1: 字数约束——从 prefs 读取 maxChapterLength
        String chapterLengthGuide = buildChapterLengthGuide(projectId, chapterIndex);
        // p17-02: 结构指引/高潮技巧按题材生成（替代模板硬编码四段式）
        String structureGuide = buildStructureGuide(projectId);
        String climaxCraft = buildClimaxCraft(projectId);
        String chapterWritingPrompt = promptService.render("chapter-writing", Map.of(
                "styleGuide", dynamicStyleGuide,
                "writingConstraints", ctx.getWritingConstraints() != null ? ctx.getWritingConstraints() : "",
                "characterVoices", ctx.getCharacterVoiceSection() != null ? ctx.getCharacterVoiceSection() : "",
                "knowledgeQuotes", ctx.getKnowledgeQuoteSection() != null ? ctx.getKnowledgeQuoteSection() : "",
                "genrePacingRules", genrePacingRules,
                "chapterLengthGuide", chapterLengthGuide,
                "structureGuide", structureGuide,
                "climaxCraft", climaxCraft,
                "feedbackSection", ctx.getFeedbackSection() != null ? ctx.getFeedbackSection() : ""));
        String systemPrompt = ctx.toSystemPrompt() + "\n\n" + chapterWritingPrompt;

        var req = LlmRequest.builder()
                .taskType(LlmTaskType.CHAPTER_WRITING)
                .systemPrompt(systemPrompt)
                .userMessage(ctx.toUserPrompt())
                .temperature(0.8)
                .maxTokens(chapterWritingMaxTokens())
                .build();

        // 3. 调 LLM 写正文（空内容/过短用变化 prompt 重试；contentFilter 用温和 prompt 重试）
        // p9-1: 最低字数从 prefs 读取（默认2000）
        int minLen = repository.findWritingPreferences(projectId)
                .map(p -> p.getMinChapterLength())
                .orElse(2000);
        com.writing.novel.llm.LlmResponse resp;
        try {
            resp = gateway.complete(req);
        } catch (com.writing.novel.llm.client.ContentFilterException e) {
            // 内容审查拦截：用温和 prompt 重试（避开暴力直白，改用侧面烘托）
            var mildReq = LlmRequest.builder()
                    .taskType(LlmTaskType.CHAPTER_WRITING)
                    .systemPrompt(systemPrompt + "\n\n注意：请避开暴力/血腥的直白描写，改用侧面烘托、心理描写和氛围渲染来表现冲突。")
                    .userMessage(ctx.toUserPrompt())
                    .temperature(0.5) // 大幅降温度
                    .maxTokens(chapterWritingMaxTokens())
                    .build();
            resp = gateway.complete(mildReq);
        }
        if (resp.getContent() == null || resp.getContent().length() < minLen) {
            // 重试：变化 prompt 引导 LLM 输出完整正文
            var retryReq = LlmRequest.builder()
                    .taskType(LlmTaskType.CHAPTER_WRITING)
                    .systemPrompt(systemPrompt)
                    .userMessage(ctx.toUserPrompt()
                            + "\n\n⚠️ 上次输出为空或过短。请严格按四段式结构完整输出不少于2500字的正文。")
                    .temperature(0.7)
                    .maxTokens(chapterWritingMaxTokens())
                    .build();
            try {
                resp = gateway.complete(retryReq);
            } catch (com.writing.novel.llm.client.ContentFilterException e2) {
                // 温和重试也触发审查：最后一次尝试，极低温度 + 最保守 prompt
                var safeReq = LlmRequest.builder()
                        .taskType(LlmTaskType.CHAPTER_WRITING)
                        .systemPrompt(systemPrompt + "\n\n绝对禁止任何直接的暴力、身体伤害、血腥描写。所有冲突用对话和心理活动表现。")
                        .userMessage(ctx.toUserPrompt())
                        .temperature(0.3)
                        .maxTokens(chapterWritingMaxTokens())
                        .build();
                resp = gateway.complete(safeReq);
            }
        }

        // 4. 内容校验：仍不达标则不保存、抛异常（绝不入库空章节）
        if (resp.getContent() == null || resp.getContent().length() < minLen) {
            throw new RuntimeException("章节内容为空或过短（" 
                    + (resp.getContent() == null ? 0 : resp.getContent().length()) + "字，需≥" + minLen + "字），放弃保存");
        }

        // 5. 构建 Chapter 并保存（纠正错误的章节号）
        // p9-4: 复用已存在的同索引章节（避免重复行）
        String cleanContent = sanitizeChapterNumbers(resp.getContent(), chapterIndex);
        Chapter chapter = repository.findChapterByIndex(projectId, chapterIndex)
                .orElse(new Chapter(projectId, chapterIndex, chapterTitle));
        chapter.setContent(cleanContent);
        repository.saveChapter(chapter);

        // 6. 按段落分块索引章节正文
        if (vectorRetrieval != null) {
            try {
                indexChapterInChunks(projectId, chapter, resp.getContent());
                // p12-K3: 每章索引完后 flush 向量库
                vectorRetrieval.flush();
            } catch (Exception e) {
                // 索引失败不影响写作流程，章节已保存
            }
        }

        return chapter;
    }

    /**
     * 带纠偏指令的重写：返回修正后的正文内容（不新建 Chapter，由调用方原地更新）。
     * 用于 ContinuityChecker 发现 HIGH 级矛盾后的纠偏闭环。
     * @return 修正后的正文内容；重写失败返回 null（保留原版本）
     */
    public String rewriteForCorrection(String projectId, int chapterIndex, String chapterTitle,
                           String chapterSynopsis, String writingNotes, String correctionInstructions) {
        WritingContext ctx = contextAssembler.assemble(
                projectId, chapterIndex, chapterTitle,
                chapterSynopsis + "\n\n【纠偏要求】" + correctionInstructions,
                writingNotes, chapterWritingBudget());

        // 修复：rewrite 也传全部模板变量（之前只传 styleGuide=""）
        String dynamicStyleGuide = extractStyleGuide(projectId);
        String genrePacingRules = "";
        if (genreRuleProvider != null) {
            var project = repository.findProject(projectId).orElse(null);
            if (project != null) {
                genrePacingRules = genreRuleProvider.getChapterWritingRules(project.getGenre());
            }
        }
        String chapterLengthGuide = buildChapterLengthGuide(projectId, chapterIndex);
        String chapterWritingPrompt = promptService != null
                ? promptService.render("chapter-writing", java.util.Map.of(
                    "styleGuide", dynamicStyleGuide,
                    "writingConstraints", ctx.getWritingConstraints() != null ? ctx.getWritingConstraints() : "",
                    "characterVoices", ctx.getCharacterVoiceSection() != null ? ctx.getCharacterVoiceSection() : "",
                    "knowledgeQuotes", ctx.getKnowledgeQuoteSection() != null ? ctx.getKnowledgeQuoteSection() : "",
                    "genrePacingRules", genrePacingRules,
                    "chapterLengthGuide", chapterLengthGuide,
                    "structureGuide", buildStructureGuide(projectId),
                    "climaxCraft", buildClimaxCraft(projectId),
                    "feedbackSection", ctx.getFeedbackSection() != null ? ctx.getFeedbackSection() : ""))
                : "你是网络小说写作高手。";

        String systemPrompt = ctx.toSystemPrompt() + "\n\n" + chapterWritingPrompt
                + "\n\n特别提醒：上一版本存在严重问题，本次必须修正：" + correctionInstructions;

        var req = LlmRequest.builder()
                .taskType(LlmTaskType.CHAPTER_WRITING)
                .systemPrompt(systemPrompt)
                .userMessage(ctx.toUserPrompt())
                .temperature(0.7)
                .maxTokens(chapterWritingMaxTokens())
                .build();

        var resp = gateway.complete(req);
        // p11.1-J1: 重写下限从 500 提升到 minChapterLength（与 write() 一致）
        int minLen = repository.findWritingPreferences(projectId)
                .map(p -> p.getMinChapterLength()).orElse(2000);
        if (resp.getContent() == null || resp.getContent().length() < minLen) {
            return null; // 重写也失败，保留原版本
        }

        String cleanContent = sanitizeChapterNumbers(resp.getContent(), chapterIndex);
        // p16-05: 重写改变了正文——立即按章重建向量索引（清旧 chunk + 新正文入库）。
        // 调用方若拒收该重写（长度保护等），需用保留的原正文重放 reindexChapter 恢复一致。
        reindexChapter(projectId, chapterIndex, cleanContent);
        return cleanContent;
    }

    /**
     * p16-05: 按章重建向量索引——统一入口。
     * 清掉该章所有旧版本 chunk（chapterId 与 chapterId#chunkN）后按给定正文分块重新入库。
     * 供三条改写路径（rewriteForCorrection / orchestrator.rewriteChapter / orchestrator.editPassage）
     * 修改正文后调用，避免向量库残留被否定的旧情节被后续章节检索召回。
     * 索引失败不影响调用方主流程（与 write() 的容错一致）。
     */
    public void reindexChapter(String projectId, int chapterIndex, String content) {
        if (vectorRetrieval == null || repository == null || content == null) return;
        try {
            Chapter chapter = repository.findChapterByIndex(projectId, chapterIndex).orElse(null);
            if (chapter == null) return; // 章未入库则无从取 chunk id 前缀，跳过
            indexChapterInChunks(projectId, chapter, content);
            vectorRetrieval.flush();
        } catch (Exception e) {
            // 索引失败不影响主流程（章节正文已保存）
        }
    }

    /**
     * p20-09: 章节号归一化——仅归一化正文开头的章节标题。
     * 对话、引文、跨章引用中的"第N章"保持逐字不动（旧实现全文 replaceAll 破坏正文）。
     * 允许前导空白与 markdown 标题井号前缀；包内可见便于直接测试。
     */
    static String sanitizeChapterNumbers(String content, int chapterIndex) {
        if (content == null) return content;
        int leadingWs = 0;
        while (leadingWs < content.length() && Character.isWhitespace(content.charAt(leadingWs))) {
            leadingWs++;
        }
        int headingStart = leadingWs;
        while (headingStart < content.length() && content.charAt(headingStart) == '#') {
            headingStart++;
        }
        if (headingStart < content.length() && content.charAt(headingStart) == '第'
                && headingStart + 1 < content.length()
                && Character.isDigit(content.charAt(headingStart + 1))) {
            int digitsEnd = headingStart + 1;
            while (digitsEnd < content.length() && Character.isDigit(content.charAt(digitsEnd))) {
                digitsEnd++;
            }
            if (digitsEnd < content.length() && content.charAt(digitsEnd) == '章') {
                return content.substring(0, headingStart)
                        + "第" + chapterIndex + "章"
                        + content.substring(digitsEnd + 1);
            }
        }
        return content;
    }

    /** 按段落分块索引章节正文，每块 ~800 字，确保中后段情节也能被召回。D2: 批量索引（一次embedding调用） */
    private void indexChapterInChunks(String projectId, Chapter chapter, String content) {
        // p10-w1-03: 重索引前清理该章旧版本 chunk（chapterId 与 chapterId#chunkN），
        // 避免重写后新旧正文并存导致向量检索召回自相矛盾的旧情节（复现 GAP#9）
        if (vectorRetrieval != null) {
            vectorRetrieval.removeByIdPrefix(chapter.getId());
        }
        String prefix = "第" + chapter.getChapterIndex() + "章 " + chapter.getTitle() + "：";
        String fullText = prefix + content;
        int chunkSize = 800;
        java.util.List<IndexedText> chunks = new java.util.ArrayList<>();

        if (fullText.length() <= chunkSize) {
            // 短章节整体索引
            chunks.add(new IndexedText(chapter.getId(), projectId, fullText, "CHAPTER"));
        } else {
            // 按段落分割，合并到 ~800 字一块
            String[] paragraphs = content.split("\n");
            StringBuilder current = new StringBuilder(prefix);
            int chunkNum = 0;
            for (String para : paragraphs) {
                if (current.length() + para.length() > chunkSize && current.length() > prefix.length()) {
                    chunks.add(new IndexedText(
                            chapter.getId() + "#chunk" + chunkNum,
                            projectId, current.toString(), "CHAPTER"));
                    chunkNum++;
                    current = new StringBuilder("第" + chapter.getChapterIndex() + "章（续）：");
                }
                current.append(para).append("\n");
            }
            if (current.length() > prefix.length()) {
                chunks.add(new IndexedText(
                        chapter.getId() + "#chunk" + chunkNum,
                        projectId, current.toString(), "CHAPTER"));
            }
        }
        // D2: 一次性批量索引（embedding 调用 5-8 次 → 1 次）
        vectorRetrieval.indexBatch(chunks);
    }

    /** 从 StyleProfile 提取风格指引（tone + dosAndDonts）作为 styleGuide 变量 */
    /**
     * p11-F2: 根据章节序号和奇偶性生成字数指导（长短交替）。
     * 高潮章（奇数卷末/大纲标注高张力）可写长，喘息章写短。
     */
    /**
     * p11.1-J1: 根据章节序号和 maxChapterLength 生成字数指导。
     * 长短交替 + 从 prefs 读取 maxChapterLength 约束。
     */
    private String buildChapterLengthGuide(String projectId, int chapterIndex) {
        try {
            int maxLen = repository.findWritingPreferences(projectId)
                    .map(p -> p.getMaxChapterLength())
                    .orElse(6000);
            // p17-02: 题材感知——慢节奏/严肃题材不用爽点式四段式措辞
            boolean fastPaced = repository.findProject(projectId)
                    .map(p -> p.getGenre() == null || p.getGenre().isFastPaced())
                    .orElse(true);
            // op-01: 开篇前 5 章强制高压——禁止喘息章判定
            boolean isOpening = chapterIndex <= OPENING_CHAPTERS;
            boolean isLongChapter = isOpening || chapterIndex % 3 != 0;
            if (isOpening) {
                int targetMax = Math.min(maxLen, 4500);
                if (fastPaced) {
                    return "本章为【开篇高压章】（前" + OPENING_CHAPTERS + "章），目标字数 3500-" + targetMax + " 字。"
                            + "开篇必须快速抓人：首 500 字内必须出现冲突/悬念/反转钩子，不得用大段环境描写或背景铺垫开场。"
                            + "四段式比例：开场钩子500字→冲突升级1500字→高潮爽点1200字→悬念400字。"
                            + "本章至少 2 个爽点，冲突必须在章内升级而非只铺悬念。";
                }
                return "本章为开篇章（前" + OPENING_CHAPTERS + "章），目标字数 3500-" + targetMax + " 字。"
                        + "前 800 字内进入具体场景与人物处境，禁止大段设定解说/环境铺陈/谱系罗列开场；"
                        + "以事件或人物处境切入，张力随章内递进。";
            } else if (isLongChapter) {
                int targetMax = Math.min(maxLen, 4500);
                String structure = fastPaced
                        ? "四段式比例：开场500字→冲突升级1500字→高潮1200字→悬念400字。"
                        : "按本章张力自然递进组织段落（切入→发展→高潮点→余韵）。";
                return "本章为标准/高潮章，目标字数 3500-" + targetMax + " 字（上限 " + maxLen + " 字，超过将被标记为过长）。" +
                        structure + "宁可精简也不要注水。";
            } else {
                String burst = fastPaced ? "不需要爽点爆发" : "不需要高潮爆发";
                return "本章为喘息/过渡章，目标字数 2500-3500 字。不必严格四段式，可以是对话驱动/单场景深挖/角色互动为主，" + burst + "。";
            }
        } catch (Exception e) {
            return "目标字数 3000-4500 字。";
        }
    }

    /** p17-02: 按题材生成章节结构指引（替代模板硬编码四段式）；包级可见供测试 */
    String buildStructureGuide(String projectId) {
        boolean fastPaced = repository == null || repository.findProject(projectId)
                .map(p -> p.getGenre() == null || p.getGenre().isFastPaced())
                .orElse(true);
        if (fastPaced) {
            return """
                    1. **开场钩子**（约15%篇幅）：用一个悬念/冲突/反常画面直接抓住读者。不要用风景描写或设定说明开场。
                    2. **冲突升级**（约40%篇幅）：将【本章任务】中的「场景节拍」逐一展开为具体场景。每个节拍要有一个明确的动作和一次冲突推进。对话与动作穿插，不要大段独白。
                    3. **高潮/爽点**（约30%篇幅）：执行【本章任务】中的「爽点」设计。爽点要有铺垫→压抑→释放的三拍节奏：先让主角陷入困境(铺垫)，再加剧压力(压抑)，最后以意想不到的方式逆转(释放)。**不要用"路人震惊"来表现爽感，用对手的反应和主角的内心变化来呈现。**
                    4. **悬念结尾**（约15%篇幅）：留下一个让读者必须看下一章的钩子。可以是新危机出现、真相半揭露、或角色做出意外决定。""";
        }
        return """
                1. **场景切入**（约15%篇幅）：直接进入具体场景与人物处境，不要用设定说明或风景描写开场。
                2. **发展与递进**（约50%篇幅）：将【本章任务】中的「场景节拍」展开为具体场景，每个节拍有明确的动作和一次张力推进。对话与叙述穿插。
                3. **高潮点**（约25%篇幅）：本章核心张力点在此释放（真相揭示/冲突爆发/关键抉择），要有铺垫与余波，不要仓促宣告结果。
                4. **收束/余韵**（约10%篇幅）：留有未解张力或余韵，不必强钩子，但不得平淡收场。""";
    }

    /** p17-02: 高潮执行技巧（快节奏题材为爽点技巧；慢节奏题材为通用张力技巧） */
    private String buildClimaxCraft(String projectId) {
        boolean fastPaced = repository == null || repository.findProject(projectId)
                .map(p -> p.getGenre() == null || p.getGenre().isFastPaced())
                .orElse(true);
        if (fastPaced) {
            return """
                    **爽点执行技巧：**
                    - 信息差：让读者知道主角知道而角色不知道的事，制造紧张感
                    - 反预期：建立读者的预期，然后打破它（但必须有逻辑支撑）
                    - 智斗：主角用策略/知识/规则利用获胜，而非纯粹的力量碾压""";
        }
        return """
                **高潮执行技巧：**
                - 张力点的释放要有铺垫→积蓄→爆发的节奏，用具体细节与人物反应呈现，不要直白宣告
                - 反转必须有逻辑支撑与前置伏笔，不为反转而反转""";
    }

    private String extractStyleGuide(String projectId) {
        if (repository == null) return "";
        var styleOpt = repository.findStyleProfileByProject(projectId);
        if (styleOpt.isEmpty()) return "";
        var style = styleOpt.get();
        StringBuilder sb = new StringBuilder();
        if (style.getTone() != null) sb.append(style.getTone());
        // p17-04: voice（叙事视角/节奏）此前被丢弃——预设 styleVoice 现已生效
        if (style.getVoice() != null && !style.getVoice().isBlank()) {
            if (sb.length() > 0) sb.append("。");
            sb.append(style.getVoice());
        }
        if (style.getDosAndDonts() != null) {
            if (sb.length() > 0) sb.append("。");
            sb.append(style.getDosAndDonts());
        }
        return sb.toString();
    }
}
