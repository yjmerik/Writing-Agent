package com.writing.novel.memory;

import com.writing.novel.agent.EntityExtractorAgent;
import com.writing.novel.domain.GenreProfile;
import com.writing.novel.domain.Project;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.service.ClimaxDiversityTracker;
import com.writing.novel.service.EntityGraphService;
import com.writing.novel.spi.EmbeddingProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * p17-02: 爽点/四段式/悬念钩子约束题材条件化。
 * 快节奏题材（CULTIVATION/WUXIA/THRILLER/MYSTERY/GENERAL）保留网文硬约束（现状不变）；
 * 慢节奏/严肃题材（LITERARY/HISTORICAL/ROMANCE 等）替换为题材结构指引，不被爽文范式污染。
 */
class GenreConditionalConstraintsTest {

    @TempDir
    Path tempDir;
    private SqliteNovelRepository repo;
    private ContextAssembler assembler;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("genre.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);

        EmbeddingProvider emb = new EmbeddingProvider() {
            @Override public float[] embed(String text) { return new float[]{1.0f}; }
            @Override public int dimension() { return 1; }
        };
        var store = new com.writing.novel.persistence.embedding.EmbeddedVectorStore(
                tempDir.resolve("genre.idx").toString());
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
                new ClimaxDiversityTracker(), null, null, null);
    }

    private String constraintsFor(GenreProfile genre, int chapterIndex) {
        Project p = new Project("题材测试", "构思", "标签");
        p.setGenre(genre);
        repo.saveProject(p);
        var ctx = assembler.assemble(p.getId(), chapterIndex, "第" + chapterIndex + "章", "梗概", "");
        return ctx.getWritingConstraints() == null ? "" : ctx.getWritingConstraints();
    }

    @Test
    void 快节奏题材保留爽点与悬念钩子约束_现状不变() {
        for (GenreProfile g : new GenreProfile[]{GenreProfile.CULTIVATION, GenreProfile.WUXIA, GenreProfile.GENERAL}) {
            String c = constraintsFor(g, 6);
            assertTrue(c.contains("爽点"), g + " 应保留爽点约束");
            assertTrue(c.contains("悬念钩子"), g + " 应保留悬念钩子约束");
        }
    }

    @Test
    void 慢节奏题材不含爽点四段式悬念钩子_改注入张力指引() {
        for (GenreProfile g : new GenreProfile[]{GenreProfile.HISTORICAL, GenreProfile.LITERARY, GenreProfile.ROMANCE}) {
            String c = constraintsFor(g, 6);
            assertFalse(c.contains("爽点"), g + " 不应强制爽点");
            assertFalse(c.contains("悬念钩子"), g + " 不应强制悬念钩子");
            assertTrue(c.contains("张力"), g + " 应含张力/推进类结构指引");
        }
    }

    @Test
    void 开篇高压仅快节奏题材强制() {
        String historical = constraintsFor(GenreProfile.HISTORICAL, 2);
        assertFalse(historical.contains("开篇加速"), "HISTORICAL 开篇不应强制爽点式高压");

        String cultivation = constraintsFor(GenreProfile.CULTIVATION, 2);
        assertTrue(cultivation.contains("开篇加速"), "CULTIVATION 开篇高压应保留");
    }
}
