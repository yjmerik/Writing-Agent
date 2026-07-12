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

        // 3. 调 LLM 写正文
        var resp = gateway.complete(req);

        // 4. 构建 Chapter 并保存
        Chapter chapter = new Chapter(projectId, chapterIndex, chapterTitle);
        chapter.setContent(resp.getContent());
        repository.saveChapter(chapter);

        // 5. 索引章节正文到向量库（供后续语义检索）
        // 截断到 1500 字，避免 embedding API 输入长度限制；失败不中断写作
        if (vectorRetrieval != null) {
            try {
                String indexText = "第" + chapterIndex + "章 " + chapterTitle + "：" + resp.getContent();
                if (indexText.length() > 1500) {
                    indexText = indexText.substring(0, 1500);
                }
                vectorRetrieval.index(new IndexedText(chapter.getId(), projectId, indexText, "CHAPTER"));
            } catch (Exception e) {
                // 索引失败不影响写作流程，章节已保存
            }
        }

        return chapter;
    }
}
