package com.writing.novel.agent;

import com.writing.novel.domain.Chapter;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmTaskType;
import com.writing.novel.llm.prompt.PromptTemplateService;
import com.writing.novel.memory.ContextAssembler;
import com.writing.novel.memory.IndexedText;
import com.writing.novel.memory.VectorRetrievalService;
import com.writing.novel.memory.WritingContext;
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

    public ChapterWriterAgent(LlmGateway gateway, ContextAssembler contextAssembler,
                               NovelRepository repository, VectorRetrievalService vectorRetrieval,
                               PromptTemplateService promptService) {
        this.gateway = gateway;
        this.contextAssembler = contextAssembler;
        this.repository = repository;
        this.vectorRetrieval = vectorRetrieval;
        this.promptService = promptService;
    }

    /** 兼容测试的四参构造（vectorRetrieval 为 null 时跳过索引） */
    public ChapterWriterAgent(LlmGateway gateway, ContextAssembler contextAssembler,
                               NovelRepository repository, PromptTemplateService promptService) {
        this(gateway, contextAssembler, repository, null, promptService);
    }

    public Chapter write(String projectId, int chapterIndex, String chapterTitle,
                         String chapterSynopsis, String writingNotes) {
        // 1. 组装上下文
        WritingContext ctx = contextAssembler.assemble(
                projectId, chapterIndex, chapterTitle, chapterSynopsis, writingNotes);

        // 2. 构建 LLM 请求：ContextAssembler 组装的上下文 + chapter-writing 模板的角色定义
        String chapterWritingPrompt = promptService.render("chapter-writing", Map.of("styleGuide", ""));
        String systemPrompt = ctx.toSystemPrompt() + "\n\n" + chapterWritingPrompt;

        var req = LlmRequest.builder()
                .taskType(LlmTaskType.CHAPTER_WRITING)
                .systemPrompt(systemPrompt)
                .userMessage(ctx.toUserPrompt())
                .temperature(0.8)
                .maxTokens(3000)
                .build();

        // 3. 调 LLM 写正文（空内容或 <500 字时重试 1 次）
        var resp = gateway.complete(req);
        if (resp.getContent() == null || resp.getContent().length() < 500) {
            resp = gateway.complete(req); // 重试
        }

        // 4. 构建 Chapter 并保存
        Chapter chapter = new Chapter(projectId, chapterIndex, chapterTitle);
        chapter.setContent(resp.getContent());
        repository.saveChapter(chapter);

        // 5. 按段落分块索引章节正文（替换之前的前 1500 字截断）
        // 每块 ~800 字，带 chunk 编号，确保中后段情节也能被召回
        if (vectorRetrieval != null) {
            try {
                indexChapterInChunks(projectId, chapter, resp.getContent());
            } catch (Exception e) {
                // 索引失败不影响写作流程，章节已保存
            }
        }

        return chapter;
    }

    /**
     * 带纠偏指令的重写：把连续性问题加入 prompt，要求 LLM 修正后重新生成。
     * 用于 ContinuityChecker 发现 HIGH 级矛盾后的纠偏闭环。
     */
    public Chapter rewrite(String projectId, int chapterIndex, String chapterTitle,
                           String chapterSynopsis, String writingNotes, String correctionInstructions) {
        WritingContext ctx = contextAssembler.assemble(
                projectId, chapterIndex, chapterTitle,
                chapterSynopsis + "\n\n【纠偏要求】" + correctionInstructions,
                writingNotes);

        String chapterWritingPrompt = promptService != null
                ? promptService.render("chapter-writing", java.util.Map.of("styleGuide", ""))
                : "你是网络小说写作高手。";

        String systemPrompt = ctx.toSystemPrompt() + "\n\n" + chapterWritingPrompt
                + "\n\n特别提醒：上一版本存在严重问题，本次必须修正：" + correctionInstructions;

        var req = LlmRequest.builder()
                .taskType(LlmTaskType.CHAPTER_WRITING)
                .systemPrompt(systemPrompt)
                .userMessage(ctx.toUserPrompt())
                .temperature(0.7) // 降温度，更忠实约束
                .maxTokens(3000)
                .build();

        var resp = gateway.complete(req);
        if (resp.getContent() == null || resp.getContent().length() < 500) {
            return null; // 重写也失败，保留原版本
        }

        Chapter chapter = new Chapter(projectId, chapterIndex, chapterTitle);
        chapter.setContent(resp.getContent());
        repository.saveChapter(chapter);

        if (vectorRetrieval != null) {
            try {
                String indexText = "第" + chapterIndex + "章 " + chapterTitle + "：" + resp.getContent();
                if (indexText.length() > 1500) indexText = indexText.substring(0, 1500);
                vectorRetrieval.index(new IndexedText(chapter.getId(), projectId, indexText, "CHAPTER"));
            } catch (Exception ignored) {}
        }

        return chapter;
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
}
