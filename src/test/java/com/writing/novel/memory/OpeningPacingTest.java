package com.writing.novel.memory;

import com.writing.novel.domain.Project;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.agent.EntityExtractorAgent;
import com.writing.novel.service.EntityGraphService;
import com.writing.novel.spi.EmbeddingProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * op-01: 开篇节奏加速测试。
 * 前 N 章不判定喘息、注入开篇加速约束；N 章后恢复正常。
 */
class OpeningPacingTest {

    @TempDir
    Path tempDir;
    private SqliteNovelRepository repo;
    private ContextAssembler assembler;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("op.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        EmbeddingProvider emb = new EmbeddingProvider() {
            @Override public float[] embed(String text) { return new float[]{1.0f}; }
            @Override public int dimension() { return 1; }
        };
        var store = new com.writing.novel.persistence.embedding.EmbeddedVectorStore(tempDir.resolve("op.idx").toString());
        VectorRetrievalService vectorSvc = new VectorRetrievalService(store, emb);
        KnowledgeBaseService kbSvc = new KnowledgeBaseService(repo, vectorSvc);
        SummaryService summarySvc = new SummaryService(repo);
        EntityGraphService entityGraphSvc = new EntityGraphService(repo, null) {
            @Override public String recallConsistencySection(String projectId, int currentChapter) { return ""; }
            @Override public java.util.List<String> getRecentEntityNames(String projectId, int currentChapter) { return java.util.List.of(); }
            @Override public String resolveCanonical(String projectId, String rawName) { return rawName; }
            @Override public EntityExtractorAgent.ExtractionResult extractFromChapter(String projectId, com.writing.novel.domain.Chapter chapter) {
                return new EntityExtractorAgent.ExtractionResult(0, 0, 0, null);
            }
        };
        assembler = new ContextAssembler(repo, kbSvc, summarySvc, vectorSvc, entityGraphSvc,
                new com.writing.novel.service.ClimaxDiversityTracker(), null, null, null);
    }

    @Test
    void 前五章注入开篇加速约束() {
        Project p = new Project("开篇测", "故事", "悬疑");
        repo.saveProject(p);
        for (int ch = 1; ch <= ContextAssembler.OPENING_CHAPTERS; ch++) {
            WritingContext ctx = assembler.assemble(p.getId(), ch, "第" + ch + "章", "剧情", null);
            assertTrue(ctx.getWritingConstraints().contains("开篇加速"),
                    "第" + ch + "章应注入开篇加速约束");
            assertTrue(ctx.getWritingConstraints().contains("至少 2 个爽点"));
        }
    }

    @Test
    void 第六章后不注入开篇约束() {
        Project p = new Project("非开篇", "故事", "悬疑");
        repo.saveProject(p);
        int after = ContextAssembler.OPENING_CHAPTERS + 1;
        WritingContext ctx = assembler.assemble(p.getId(), after, "第" + after + "章", "剧情", null);
        assertFalse(ctx.getWritingConstraints().contains("开篇加速"),
                "第" + after + "章不应再注入开篇约束");
    }

    @Test
    void 开篇章不判定为喘息() {
        // 验证 ChapterWriterAgent.OPENING_CHAPTERS 与 ContextAssembler 一致
        assertEquals(ContextAssembler.OPENING_CHAPTERS,
                com.writing.novel.agent.ChapterWriterAgent.OPENING_CHAPTERS,
                "两个类的 OPENING_CHAPTERS 应一致");
        // 第3章(3%3==0)在旧逻辑下是喘息章, 开篇修复后应为高压章
        assertTrue(3 <= ContextAssembler.OPENING_CHAPTERS,
                "第3章应在开篇范围内(OPENING_CHAPTERS>=3)");
    }
}
