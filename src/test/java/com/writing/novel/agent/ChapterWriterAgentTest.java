package com.writing.novel.agent;

import com.writing.novel.domain.Chapter;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.memory.ContextAssembler;
import com.writing.novel.memory.WritingContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChapterWriterAgentTest {

    @Test
    void 组装上下文并写出章节() {
        WritingContext mockCtx = new WritingContext();
        mockCtx.setStyleSection("热血风格");
        mockCtx.setTaskSection("第一章 张三登场");

        // ContextAssembler 是类不是接口，用匿名子类覆盖 assemble
        ContextAssembler mockAssembler = new ContextAssembler(null, null, null, null) {
            @Override
            public WritingContext assemble(String projectId, int chapterIndex,
                                           String chapterTitle, String chapterSynopsis,
                                           String writingNotes) {
                return mockCtx;
            }
        };

        LlmGateway mockGateway = new LlmGateway() {
            @Override
            public LlmResponse complete(LlmRequest req) {
                return new LlmResponse("张三踏入青云宗山门，剑意凛然，这是正文内容……", 200, 800);
            }
            @Override
            public void streamComplete(LlmRequest req, StreamHandler handler) {}
        };

        ChapterWriterAgent agent = new ChapterWriterAgent(mockGateway, mockAssembler, new TestNovelRepositoryStub());
        Chapter ch = agent.write("proj-1", 1, "第一章 张三登场",
                "张三拜入山门", "3000字");

        assertNotNull(ch);
        assertEquals(1, ch.getChapterIndex());
        assertEquals("第一章 张三登场", ch.getTitle());
        assertTrue(ch.getContent().contains("张三"));
        assertTrue(ch.getWordCount() > 0);
    }

    @Test
    void 写完后保存章节到仓库() {
        WritingContext mockCtx = new WritingContext();
        ContextAssembler mockAssembler = new ContextAssembler(null, null, null, null) {
            @Override
            public WritingContext assemble(String pid, int ci, String ct, String cs, String wn) {
                return mockCtx;
            }
        };
        LlmGateway mockGateway = new LlmGateway() {
            @Override
            public LlmResponse complete(LlmRequest req) { return new LlmResponse("正文内容", 10, 20); }
            @Override
            public void streamComplete(LlmRequest req, StreamHandler handler) {}
        };

        boolean[] saved = {false};
        com.writing.novel.spi.NovelRepository mockRepo = new TestNovelRepositoryStub() {
            @Override
            public String saveChapter(Chapter c) { saved[0] = true; return c.getId(); }
        };

        ChapterWriterAgent agent = new ChapterWriterAgent(mockGateway, mockAssembler, mockRepo);
        agent.write("p1", 1, "ch1", "synopsis", null);

        assertTrue(saved[0], "写完后应保存章节到仓库");
    }
}
