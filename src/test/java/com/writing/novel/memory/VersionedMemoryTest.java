package com.writing.novel.memory;

import com.writing.novel.agent.*;
import com.writing.novel.domain.*;
import com.writing.novel.domain.Character;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.orchestrator.NovelOrchestrator;
import com.writing.novel.orchestrator.ProjectService;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.service.EntityGraphService;
import com.writing.novel.spi.EmbeddingProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * mc-p2-01: versioned memory——派生产物（摘要）带 sourceChapterVersion + dirty 过滤。
 * 复现 D2：章节重写后，覆盖该章的摘要失效，ContextAssembler 不得注入过期摘要。
 */
class VersionedMemoryTest {

    @TempDir
    Path tempDir;
    private SqliteNovelRepository repo;
    private ContextAssembler assembler;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("vm.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);

        EmbeddingProvider emb = new EmbeddingProvider() {
            @Override public float[] embed(String text) { return new float[]{1.0f}; }
            @Override public int dimension() { return 1; }
        };
        var store = new com.writing.novel.persistence.embedding.EmbeddedVectorStore(
                tempDir.resolve("vm.idx").toString());
        VectorRetrievalService vectorSvc = new VectorRetrievalService(store, emb);
        KnowledgeBaseService kbSvc = new KnowledgeBaseService(repo, vectorSvc);
        SummaryService summarySvc = new SummaryService(repo);
        EntityGraphService entityGraphSvc = new EntityGraphService(repo, null) {
            @Override public String recallConsistencySection(String projectId, int currentChapter) { return ""; }
            @Override public java.util.List<String> getRecentEntityNames(String projectId, int currentChapter) { return java.util.List.of(); }
            @Override public String resolveCanonical(String projectId, String rawName) { return rawName; }
            @Override public com.writing.novel.agent.EntityExtractorAgent.ExtractionResult extractFromChapter(String projectId, com.writing.novel.domain.Chapter chapter) {
                return new EntityExtractorAgent.ExtractionResult(0, 0, 0, null);
            }
        };
        assembler = new ContextAssembler(repo, kbSvc, summarySvc, vectorSvc, entityGraphSvc,
                new com.writing.novel.service.ClimaxDiversityTracker(), null, null, null);
    }

    @Test
    void 摘要版本往返入库() {
        Project p = new Project("往返", "测试", "玄幻");
        repo.saveProject(p);
        Summary s = new Summary(p.getId(), 1, 3, "摘要内容");
        s.setSourceChapterVersion(2);
        repo.saveSummary(s);

        Summary loaded = repo.findSummariesByProject(p.getId()).get(0);
        assertEquals(2, loaded.getSourceChapterVersion());
        assertFalse(loaded.isDirty());
    }

    @Test
    void 组装时过滤dirty摘要() {
        Project p = new Project("过滤", "测试", "玄幻");
        repo.saveProject(p);
        Chapter c = new Chapter(p.getId(), 5, "第5章");
        c.setContent("正文内容。".repeat(100));
        repo.saveChapter(c);

        Summary fine = new Summary(p.getId(), 4, 5, "【旧摘要】张三在宗门修炼。");
        fine.setSourceChapterVersion(1);
        repo.saveSummary(fine);

        // dirty=false 时注入
        WritingContext ctx = assembler.assemble(p.getId(), 6, "第6章", "继续", null);
        assertTrue(ctx.getRecentSummarySection().contains("张三在宗门修炼"),
                "干净摘要应注入上下文");

        // 源章节重写 → 摘要置 dirty → 组装时过滤
        fine.setDirty(true);
        repo.saveSummary(fine);
        ctx = assembler.assemble(p.getId(), 6, "第6章", "继续", null);
        assertFalse(ctx.getRecentSummarySection().contains("张三在宗门修炼"),
                "dirty 摘要不得注入上下文");
    }

    @Test
    void 重写章节后细粒度摘要重算且卷级摘要保持dirty() {
        // 完整编排：重写章节 → 覆盖摘要失效
        Project p = new Project("重写", "测试", "玄幻");
        repo.saveProject(p);

        // 大纲：第1章
        Outline outline = new Outline(p.getId(), "总纲");
        Volume v = new Volume(p.getId(), 1, "第一卷", "卷摘要");
        v.getSynopses().add(new ChapterSynopsis(p.getId(), 1, "第一章 入门", "主角入门"));
        outline.getVolumes().add(v);
        repo.saveOutline(outline);

        // 第1章正文（version=1）
        Chapter c1 = new Chapter(p.getId(), 1, "第一章 入门");
        c1.setContent("旧正文内容。".repeat(100));
        repo.saveChapter(c1);

        // 细粒度摘要 [1,1] + 卷级摘要 [1,5]
        Summary fine = new Summary(p.getId(), 1, 1, "【旧摘要】第一章内容");
        fine.setSourceChapterVersion(1);
        repo.saveSummary(fine);
        Summary volume = new Summary(p.getId(), 1, 5, "【卷级摘要】第一卷内容");
        volume.setSourceChapterVersion(1);
        repo.saveSummary(volume);

        // LLM 桩：MEMORY_SUMMARY 返回新摘要；CHAPTER_WRITING 返回长正文（≥2000 字）
        LlmGateway gateway = new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest req) {
                return switch (req.getTaskType()) {
                    case CHAPTER_WRITING -> new LlmResponse("重写后的全新正文内容，主角踏上新征程。".repeat(200), 100, 500);
                    case MEMORY_SUMMARY -> new LlmResponse("【新摘要】第一章重写后的内容", 50, 50);
                    default -> new LlmResponse("ok", 1, 1);
                };
            }
            @Override public void streamComplete(LlmRequest req, StreamHandler h) {}
        };
        var promptSvc = TestPromptHelper.create();
        SummaryService summarySvc = new SummaryService(repo);
        ContextAssembler mockAssembler = mock(ContextAssembler.class);
        when(mockAssembler.assemble(any(), anyInt(), any(), any(), any(), anyInt()))
                .thenReturn(new WritingContext());

        NovelOrchestrator orchestrator = new NovelOrchestrator(repo, new ProjectService(repo),
                new WorldBuilderAgent(gateway, null, promptSvc),
                new CharacterDesignerAgent(gateway, null, promptSvc),
                new PlotterAgent(gateway, repo, promptSvc),
                new ChapterWriterAgent(gateway, mockAssembler, repo, promptSvc),
                new ContinuityCheckerAgent(gateway, repo, promptSvc),
                new StyleGuardianAgent(gateway, repo, promptSvc),
                new MemoryManagerAgent(gateway, summarySvc, promptSvc),
                summarySvc, mockAssembler,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null);

        // 执行重写
        Chapter rewritten = orchestrator.rewriteChapter(p.getId(), 1, "重写得更精彩");

        assertNotNull(rewritten);
        // 初写 v1 + setContent 一次 → v2；重写 setContent 再次 → v3
        assertEquals(3, rewritten.getVersion(), "重写后章节版本应自增");

        List<Summary> after = repo.findSummariesByProject(p.getId());

        // 细粒度摘要：已重算 → dirty=false 且 sourceChapterVersion 跟随新版本
        Summary fineAfter = after.stream()
                .filter(s -> s.getFromChapter() == 1 && s.getToChapter() == 1 && !s.isDirty())
                .findFirst().orElseThrow(() -> new AssertionError("细粒度摘要应重算为干净摘要"));
        assertFalse(fineAfter.isDirty(), "细粒度摘要重算后应清 dirty");
        assertTrue(fineAfter.getContent().contains("重写后"), "细粒度摘要应含重算内容: " + fineAfter.getContent());
        assertEquals(3, fineAfter.getSourceChapterVersion(), "摘要来源版本应跟随章节新版本");

        // 卷级摘要：p10-w1-02 现在也重算（原地更新、不清细粒度）→ dirty=false 且内容更新
        Summary volumeAfter = after.stream()
                .filter(s -> s.getToChapter() == 5 && !s.isDirty())
                .findFirst().orElseThrow(() -> new AssertionError("卷级摘要应重算为干净摘要"));
        assertFalse(volumeAfter.isDirty(), "卷级摘要重算后应清 dirty（修复 GAP#10 静默历史丢失）");
        assertTrue(volumeAfter.getContent().contains("重写后"),
                "卷级摘要应含重算内容: " + volumeAfter.getContent());
        // 注：本测试只建第1章，卷摘要 toChapter=5 无对应章节，sourceChapterVersion=0 是预期；
        // 关键验证是 dirty 已清 + 内容已更新（不再静默消失）。
    }
}
