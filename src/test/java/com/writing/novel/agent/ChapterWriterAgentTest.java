package com.writing.novel.agent;

import com.writing.novel.domain.Chapter;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.memory.ContextAssembler;
import com.writing.novel.memory.WritingContext;
import com.writing.novel.service.EntityGraphService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChapterWriterAgentTest {

    @Test
    void 组装上下文并写出章节() {
        WritingContext mockCtx = new WritingContext();
        mockCtx.setStyleSection("热血风格");
        mockCtx.setTaskSection("第一章 张三登场");

        // ContextAssembler 是类不是接口，用匿名子类覆盖 assemble
        ContextAssembler mockAssembler = new ContextAssembler(null, null, null, null, null, null, null, null, null) {
            @Override
            public WritingContext assemble(String projectId, int chapterIndex,
                                           String chapterTitle, String chapterSynopsis,
                                           String writingNotes) {
                return mockCtx;
            }
            @Override
            public WritingContext assemble(String projectId, int chapterIndex,
                                           String chapterTitle, String chapterSynopsis,
                                           String writingNotes, int maxContextTokens) {
                return mockCtx;
            }
        };

        LlmGateway mockGateway = new LlmGateway() {
            @Override
            public LlmResponse complete(LlmRequest req) {
                return new LlmResponse("张三踏入青云宗山门，剑意凛然，这是正文内容。".repeat(120), 200, 800);
            }
            @Override
            public void streamComplete(LlmRequest req, StreamHandler handler) {}
        };

        ChapterWriterAgent agent = new ChapterWriterAgent(mockGateway, mockAssembler, new TestNovelRepositoryStub(), TestPromptHelper.create());
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
        ContextAssembler mockAssembler = new ContextAssembler(null, null, null, null, null, null, null, null, null) {
            @Override
            public WritingContext assemble(String pid, int ci, String ct, String cs, String wn) {
                return mockCtx;
            }
            @Override
            public WritingContext assemble(String pid, int ci, String ct, String cs, String wn, int budget) {
                return mockCtx;
            }
        };
        LlmGateway mockGateway = new LlmGateway() {
            @Override
            public LlmResponse complete(LlmRequest req) { return new LlmResponse("这是足够长的正文内容用于测试。".repeat(150), 10, 20); }
            @Override
            public void streamComplete(LlmRequest req, StreamHandler handler) {}
        };

        boolean[] saved = {false};
        com.writing.novel.spi.NovelRepository mockRepo = new TestNovelRepositoryStub() {
            @Override
            public String saveChapter(Chapter c) { saved[0] = true; return c.getId(); }
        };

        ChapterWriterAgent agent = new ChapterWriterAgent(mockGateway, mockAssembler, mockRepo, TestPromptHelper.create());
        agent.write("p1", 1, "ch1", "synopsis", null);

        assertTrue(saved[0], "写完后应保存章节到仓库");
    }

    /** NCS-153（O-3）：章写 prompt 必须携带场景锚点硬约束（抑制对白密度 0.5+ 的剧本腔）。 */
    @Test
    void 章写prompt携带场景锚点硬约束() {
        WritingContext mockCtx = new WritingContext();
        mockCtx.setStyleSection("热血风格");
        mockCtx.setTaskSection("第一章 张三登场");
        ContextAssembler mockAssembler = new ContextAssembler(null, null, null, null, null, null, null, null, null) {
            @Override
            public WritingContext assemble(String projectId, int chapterIndex,
                                           String chapterTitle, String chapterSynopsis,
                                           String writingNotes) {
                return mockCtx;
            }
            @Override
            public WritingContext assemble(String projectId, int chapterIndex,
                                           String chapterTitle, String chapterSynopsis,
                                           String writingNotes, int maxContextTokens) {
                return mockCtx;
            }
        };
        final java.util.List<String> prompts = new java.util.ArrayList<>();
        LlmGateway mockGateway = new LlmGateway() {
            @Override
            public LlmResponse complete(LlmRequest req) {
                prompts.add(req.getSystemPrompt());
                return new LlmResponse("张三踏入青云宗山门，剑意凛然，这是正文内容。".repeat(120), 200, 800);
            }
            @Override
            public void streamComplete(LlmRequest req, StreamHandler handler) {}
        };
        var agent = new ChapterWriterAgent(mockGateway, mockAssembler, new TestNovelRepositoryStub(), TestPromptHelper.create());
        agent.write("proj-1", 1, "第一章 张三登场", "张三拜入山门", "3000字");

        String prompt = prompts.get(0);
        assertTrue(prompt.contains("场景锚点"),
                "章写 prompt 必须含场景锚点硬约束: " + prompt.substring(0, Math.min(400, prompt.length())));
        assertTrue(prompt.contains("连续对白"), "约束应限定连续对白上限");
    }
    /** NCS-191: 章长指导读 NarrativePolicy——历史题材去"宁可精简"措辞、加定场许可；快节奏锁现状 */
    @org.junit.jupiter.api.Test
    void 章长指导_历史题材策略化_快节奏锁现状(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) {
        var cp = new com.writing.novel.persistence.sqlite.ConnectionProvider(tempDir.resolve("len.db").toString());
        new com.writing.novel.persistence.sqlite.SqliteSchemaInitializer(cp).initialize();
        var repo = new com.writing.novel.persistence.sqlite.SqliteNovelRepository(cp);

        var h = new com.writing.novel.domain.Project("权谋", "庙堂", "历史");
        h.setGenre(com.writing.novel.domain.GenreProfile.HISTORICAL);
        repo.saveProject(h);
        repo.saveWritingPreferences(new com.writing.novel.domain.WritingPreferences(h.getId()));

        ChapterWriterAgent agent = new ChapterWriterAgent(null, null, repo, TestPromptHelper.create());
        agent.setNarrativePolicyService(new com.writing.novel.service.NarrativePolicyService(
                repo, new com.writing.novel.persistence.sqlite.NarrativePolicyRepository(cp)));

        String std = agent.buildChapterLengthGuide(h.getId(), 7);
        assertFalse(std.contains("宁可精简也不要注水"), "历史题材去除压缩性措辞");
        assertTrue(std.contains("场景深挖"), "篇幅导向场景深挖");
        String open = agent.buildChapterLengthGuide(h.getId(), 2);
        assertTrue(open.contains("定场"), "开篇章含定场许可");
        assertFalse(open.contains("禁止大段设定解说"), "开篇章解除铺陈禁令");

        var f = new com.writing.novel.domain.Project("修仙", "c", "tag");
        f.setGenre(com.writing.novel.domain.GenreProfile.CULTIVATION);
        repo.saveProject(f);
        repo.saveWritingPreferences(new com.writing.novel.domain.WritingPreferences(f.getId()));
        String fast = agent.buildChapterLengthGuide(f.getId(), 7);
        assertTrue(fast.contains("四段式"), "快节奏保持现状结构文案");
        assertTrue(fast.contains("宁可精简也不要注水"), "快节奏保持现状措辞");
    }

}
