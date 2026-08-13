package com.writing.novel.memory;

import com.writing.novel.domain.*;
import com.writing.novel.domain.Character;
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
 * cd-01 + pd-01: 配角刻画深度 + 事件模式多样性约束注入测试。
 */
class CharacterDepthDiversityTest {

    @TempDir
    Path tempDir;
    private SqliteNovelRepository repo;
    private ContextAssembler assembler;
    private KnowledgeBaseService kbSvc;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("cdd.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        EmbeddingProvider emb = new EmbeddingProvider() {
            @Override public float[] embed(String text) { return new float[]{1.0f}; }
            @Override public int dimension() { return 1; }
        };
        var store = new com.writing.novel.persistence.embedding.EmbeddedVectorStore(tempDir.resolve("cdd.idx").toString());
        VectorRetrievalService vectorSvc = new VectorRetrievalService(store, emb);
        kbSvc = new KnowledgeBaseService(repo, vectorSvc);
        SummaryService summarySvc = new SummaryService(repo);
        EntityGraphService entityGraphSvc = new EntityGraphService(repo, null) {
            @Override public String recallConsistencySection(String projectId, int currentChapter) { return ""; }
            @Override public java.util.List<String> getRecentEntityNames(String projectId, int currentChapter) { return java.util.List.of(); }
            @Override public String resolveCanonical(String projectId, String rawName) { return rawName; }
            @Override public EntityExtractorAgent.ExtractionResult extractFromChapter(String projectId, Chapter chapter) {
                return new EntityExtractorAgent.ExtractionResult(0, 0, 0, null);
            }
        };
        assembler = new ContextAssembler(repo, kbSvc, summarySvc, vectorSvc, entityGraphSvc,
                new com.writing.novel.service.ClimaxDiversityTracker(), null, null, null);
    }

    @Test
    void 有配角的章节注入刻画深度约束() {
        Project p = new Project("配角测", "故事", "悬疑");
        repo.saveProject(p);
        // 主角
        kbSvc.saveCharacter(new Character(p.getId(), "沈砚", "男主角"));
        // 配角
        Character sup = new Character(p.getId(), "杜问儿", "配角");
        sup.setPersonality("机敏多疑");
        sup.setMotivation("查明父亲死因");
        kbSvc.saveCharacter(sup);

        // 大纲含 cast
        Outline outline = new Outline(p.getId(), "总纲");
        Volume v = new Volume(p.getId(), 1, "卷1", "");
        ChapterSynopsis syn = new ChapterSynopsis(p.getId(), 2, "第2章", "剧情");
        syn.setCast("沈砚、杜问儿");
        v.getSynopses().add(syn);
        outline.getVolumes().add(v);
        repo.saveOutline(outline);

        WritingContext ctx = assembler.assemble(p.getId(), 2, "第2章", "剧情", null);
        assertTrue(ctx.getWritingConstraints().contains("配角刻画深度"), "应注入配角深度约束");
        assertTrue(ctx.getWritingConstraints().contains("杜问儿"));
        assertTrue(ctx.getWritingConstraints().contains("机敏多疑"));
    }

    @Test
    void 第二章起注入事件结构多样性约束() {
        Project p = new Project("模式测", "故事", "悬疑");
        repo.saveProject(p);
        WritingContext ctx = assembler.assemble(p.getId(), 2, "第2章", "剧情", null);
        assertTrue(ctx.getWritingConstraints().contains("事件结构多样性"), "第2章应注入事件多样性约束");
    }

    @Test
    void 第一章不注入事件多样性约束() {
        Project p = new Project("首章", "故事", "悬疑");
        repo.saveProject(p);
        WritingContext ctx = assembler.assemble(p.getId(), 1, "第1章", "剧情", null);
        assertFalse(ctx.getWritingConstraints().contains("事件结构多样性"), "第1章不应注入（无前章可比）");
    }
}
