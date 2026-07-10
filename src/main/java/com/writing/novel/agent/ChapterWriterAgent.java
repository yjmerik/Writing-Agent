package com.writing.novel.agent;

import com.writing.novel.domain.Chapter;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmTaskType;
import com.writing.novel.memory.ContextAssembler;
import com.writing.novel.memory.IndexedText;
import com.writing.novel.memory.VectorRetrievalService;
import com.writing.novel.memory.WritingContext;
import com.writing.novel.spi.NovelRepository;

/**
 * 章节写作 Agent（核心）。
 * 用 ContextAssembler 组装完整上下文 → 调 LLM 写正文 → 保存 + 索引。
 */
public class ChapterWriterAgent {

    private final LlmGateway gateway;
    private final ContextAssembler contextAssembler;
    private final NovelRepository repository;
    private final VectorRetrievalService vectorRetrieval;

    public ChapterWriterAgent(LlmGateway gateway, ContextAssembler contextAssembler,
                               NovelRepository repository, VectorRetrievalService vectorRetrieval) {
        this.gateway = gateway;
        this.contextAssembler = contextAssembler;
        this.repository = repository;
        this.vectorRetrieval = vectorRetrieval;
    }

    /** 兼容测试的三参构造（vectorRetrieval 为 null 时跳过索引） */
    public ChapterWriterAgent(LlmGateway gateway, ContextAssembler contextAssembler,
                               NovelRepository repository) {
        this(gateway, contextAssembler, repository, null);
    }

    public Chapter write(String projectId, int chapterIndex, String chapterTitle,
                         String chapterSynopsis, String writingNotes) {
        // 1. 组装上下文
        WritingContext ctx = contextAssembler.assemble(
                projectId, chapterIndex, chapterTitle, chapterSynopsis, writingNotes);

        // 2. 构建 LLM 请求
        String systemPrompt = ctx.toSystemPrompt() + "\n\n你是网络小说写作高手。" +
                "请根据提供的上下文写出本章正文，保持文风一致，注意与前文衔接。";

        var req = LlmRequest.builder()
                .taskType(LlmTaskType.CHAPTER_WRITING)
                .systemPrompt(systemPrompt)
                .userMessage(ctx.toUserPrompt())
                .temperature(0.8)
                .maxTokens(3000)
                .build();

        // 3. 调 LLM 写正文
        var resp = gateway.complete(req);

        // 4. 构建 Chapter 并保存
        Chapter chapter = new Chapter(projectId, chapterIndex, chapterTitle);
        chapter.setContent(resp.getContent());
        repository.saveChapter(chapter);

        // 5. 索引章节正文到向量库（供后续语义检索）
        if (vectorRetrieval != null) {
            vectorRetrieval.index(new IndexedText(
                    chapter.getId(), projectId,
                    "第" + chapterIndex + "章 " + chapterTitle + "：" + resp.getContent(),
                    "CHAPTER"));
        }

        return chapter;
    }
}
