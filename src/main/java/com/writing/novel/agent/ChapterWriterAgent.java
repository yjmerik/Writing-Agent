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
        String chapterWritingPrompt = promptService.render("chapter-writing", Map.of(
                "styleGuide", dynamicStyleGuide,
                "writingConstraints", ctx.getWritingConstraints() != null ? ctx.getWritingConstraints() : "",
                "characterVoices", ctx.getCharacterVoiceSection() != null ? ctx.getCharacterVoiceSection() : "",
                "knowledgeQuotes", ctx.getKnowledgeQuoteSection() != null ? ctx.getKnowledgeQuoteSection() : "",
                "genrePacingRules", genrePacingRules,
                "chapterLengthGuide", chapterLengthGuide,
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

        return sanitizeChapterNumbers(resp.getContent(), chapterIndex);
    }

    /** 纠正正文中错误的章节号（LLM 可能自创"第N章"，需统一为系统 chapterIndex） */
    private String sanitizeChapterNumbers(String content, int chapterIndex) {
        if (content == null) return content;
        // 将所有"第\d+章"替换为正确的章节号
        return content.replaceAll("第\\d+章", "第" + chapterIndex + "章");
    }

    /** 按段落分块索引章节正文，每块 ~800 字，确保中后段情节也能被召回 */
    private void indexChapterInChunks(String projectId, Chapter chapter, String content) {
        String prefix = "第" + chapter.getChapterIndex() + "章 " + chapter.getTitle() + "：";
        String fullText = prefix + content;
        int chunkSize = 800;

        if (fullText.length() <= chunkSize) {
            // 短章节整体索引
            vectorRetrieval.index(new IndexedText(
                    chapter.getId(), projectId, fullText, "CHAPTER"));
            return;
        }

        // 按段落分割，合并到 ~800 字一块
        String[] paragraphs = content.split("\n");
        StringBuilder current = new StringBuilder(prefix);
        int chunkNum = 0;
        for (String para : paragraphs) {
            if (current.length() + para.length() > chunkSize && current.length() > prefix.length()) {
                // 当前块已够大，索引它
                vectorRetrieval.index(new IndexedText(
                        chapter.getId() + "#chunk" + chunkNum,
                        projectId, current.toString(), "CHAPTER"));
                chunkNum++;
                current = new StringBuilder("第" + chapter.getChapterIndex() + "章（续）：");
            }
            current.append(para).append("\n");
        }
        // 索引最后一块
        if (current.length() > prefix.length()) {
            vectorRetrieval.index(new IndexedText(
                    chapter.getId() + "#chunk" + chunkNum,
                    projectId, current.toString(), "CHAPTER"));
        }
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
            boolean isLongChapter = chapterIndex % 3 != 0;
            if (isLongChapter) {
                int targetMax = Math.min(maxLen, 4500);
                return "本章为标准/高潮章，目标字数 3500-" + targetMax + " 字（上限 " + maxLen + " 字，超过将被标记为过长）。" +
                        "四段式比例：开场500字→冲突升级1500字→高潮1200字→悬念400字。宁可精简也不要注水。";
            } else {
                return "本章为喘息/过渡章，目标字数 2500-3500 字。不必严格四段式，可以是对话驱动/单场景深挖/角色互动为主，不需要爽点爆发。";
            }
        } catch (Exception e) {
            return "目标字数 3000-4500 字。";
        }
    }

    private String extractStyleGuide(String projectId) {
        if (repository == null) return "";
        var styleOpt = repository.findStyleProfileByProject(projectId);
        if (styleOpt.isEmpty()) return "";
        var style = styleOpt.get();
        StringBuilder sb = new StringBuilder();
        if (style.getTone() != null) sb.append(style.getTone());
        if (style.getDosAndDonts() != null) {
            if (sb.length() > 0) sb.append("。");
            sb.append(style.getDosAndDonts());
        }
        return sb.toString();
    }
}
