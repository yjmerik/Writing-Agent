package com.writing.novel.memory;

import com.writing.novel.domain.KnowledgeEntry;
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
 * ec-02: ChapterWriter 时代一致性约束注入测试。
 * 历史题材（世界观含朝代信号）→ 写作约束含时代一致性硬约束；架空/无信号 → 不注入。
 */
class EraConstraintInjectionTest {

    @TempDir
    Path tempDir;
    private SqliteNovelRepository repo;
    private ContextAssembler assembler;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("era.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);

        EmbeddingProvider emb = new EmbeddingProvider() {
            @Override public float[] embed(String text) { return new float[]{1.0f}; }
            @Override public int dimension() { return 1; }
        };
        var store = new com.writing.novel.persistence.embedding.EmbeddedVectorStore(
                tempDir.resolve("era.idx").toString());
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
    void 历史题材注入时代一致性约束() {
        Project p = new Project("明代权谋", "落魄账房学徒的故事", "悬疑,古代");
        repo.saveProject(p);
        repo.saveKnowledgeEntry(new KnowledgeEntry(p.getId(), "ECONOMY", "大明宝钞",
                "朝廷发行大明宝钞，洪武年间通行天下。"));
        repo.saveKnowledgeEntry(new KnowledgeEntry(p.getId(), "FACTION", "锦衣卫",
                "皇帝亲军锦衣卫巡查百官。"));

        WritingContext ctx = assembler.assemble(p.getId(), 1, "第1章", "开局", null);
        String constraints = ctx.getWritingConstraints();
        assertNotNull(constraints);
        assertTrue(constraints.contains("时代一致性"), "历史题材应注入时代一致性约束: " + constraints);
        assertTrue(constraints.contains("明代"), "约束应含时代锚点 明代");
    }

    @Test
    void 架空修仙世界不注入时代约束() {
        Project p = new Project("玄幻", "少年修仙", "玄幻,热血");
        repo.saveProject(p);
        repo.saveKnowledgeEntry(new KnowledgeEntry(p.getId(), "POWER_SYSTEM", "灵气",
                "修仙界以灵气修炼，架空世界。"));

        WritingContext ctx = assembler.assemble(p.getId(), 1, "第1章", "开局", null);
        String constraints = ctx.getWritingConstraints();
        assertFalse(constraints.contains("时代一致性"), "架空世界不应注入历史时代约束");
    }
}
