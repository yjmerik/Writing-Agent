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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextAssemblerTest {

    @TempDir
    Path tempDir;
    private ContextAssembler assembler;
    private SqliteNovelRepository repo;
    private KnowledgeBaseService kbService;
    private SummaryService summaryService;
    private EntityGraphService entityGraphService;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("ctx.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);

        EmbeddingProvider emb = new EmbeddingProvider() {
            @Override public float[] embed(String text) { return new float[]{1.0f}; }
            @Override public int dimension() { return 1; }
        };
        var store = new com.writing.novel.persistence.embedding.EmbeddedVectorStore(
                tempDir.resolve("ctx.idx").toString());
        VectorRetrievalService vectorSvc = new VectorRetrievalService(store, emb);
        kbService = new KnowledgeBaseService(repo, vectorSvc);
        summaryService = new SummaryService(repo);
        entityGraphService = new EntityGraphService(repo, null) {
            @Override public String recallConsistencySection(String projectId, int currentChapter) { return ""; }
            @Override public java.util.List<String> getRecentEntityNames(String projectId, int currentChapter) { return java.util.List.of(); }
            @Override public String resolveCanonical(String projectId, String rawName) { return rawName; }
            @Override public EntityExtractorAgent.ExtractionResult extractFromChapter(String projectId, com.writing.novel.domain.Chapter chapter) {
                return new EntityExtractorAgent.ExtractionResult(0, 0, 0, null);
            }
        };
        assembler = new ContextAssembler(repo, kbService, summaryService, vectorSvc, entityGraphService, new com.writing.novel.service.ClimaxDiversityTracker(), null, null, null);
    }

    @Test
    void 组装包含风格锚点与世界规则() {
        Project p = new Project("测试", "简介", "玄幻");
        repo.saveProject(p);
        StyleProfile style = new StyleProfile(p.getId());
        style.setTone("热血、紧凑");
        style.setDosAndDonts("多用短句，避免大段说明");
        repo.saveStyleProfile(style);
        kbService.saveEntry(new KnowledgeEntry(p.getId(), "POWER_SYSTEM", "灵气体系", "修炼九重境界"));

        WritingContext ctx = assembler.assemble(p.getId(), 1, "第一章 灵根测试",
                "张三参加灵根测试", "写 3000 字，节奏紧凑");

        assertNotNull(ctx);
        assertTrue(ctx.getStyleSection().contains("热血"));
        assertTrue(ctx.getWorldbuildingSection().contains("灵气体系"));
    }

    @Test
    void 组装包含近期章节摘要与原文() {
        Project p = new Project("t", "c", "tag");
        repo.saveProject(p);

        Chapter prev = new Chapter(p.getId(), 1, "第一章");
        prev.setContent("上一章的正文内容，张三踏入山门。");
        repo.saveChapter(prev);

        summaryService.save(new Summary(p.getId(), 1, 1, "张三拜入青云宗。"));

        WritingContext ctx = assembler.assemble(p.getId(), 2, "第二章",
                "张三开始修炼", null);

        assertTrue(ctx.getRecentSummarySection().contains("张三拜入青云宗"));
        assertTrue(ctx.getRecentTextSection().contains("上一章的正文内容"));
    }

    @Test
    void 组装包含检索到的人物卡() {
        Project p = new Project("t", "c", "tag");
        repo.saveProject(p);
        Character mc = new Character(p.getId(), "张三", "主角");
        mc.setPersonality("火爆");
        mc.setAbility("剑修");
        kbService.saveCharacter(mc);

        WritingContext ctx = assembler.assemble(p.getId(), 1, "第一章",
                "张三参加灵根测试", null);

        assertFalse(ctx.getRelevantCharacters().isEmpty(), "应检索到相关人物");
        assertEquals("张三", ctx.getRelevantCharacters().get(0).getName());
    }

    @Test
    void 组装包含任务章节梗概() {
        Project p = new Project("t", "c", "tag");
        repo.saveProject(p);

        WritingContext ctx = assembler.assemble(p.getId(), 1, "第一章 灵根测试",
                "本章：张三觉醒上古剑骨，震惊全场。", "字数 3000，结尾留悬念");

        assertTrue(ctx.getTaskSection().contains("张三觉醒上古剑骨"));
        assertTrue(ctx.getTaskSection().contains("3000"));
    }

    @Test
    void 组装为完整的system和user_prompt() {
        Project p = new Project("t", "c", "tag");
        repo.saveProject(p);
        StyleProfile style = new StyleProfile(p.getId());
        style.setTone("热血");
        repo.saveStyleProfile(style);
        kbService.saveEntry(new KnowledgeEntry(p.getId(), "RULE", "天道", "天道酬勤"));
        Character c = new Character(p.getId(), "张三", "主角");
        c.setPersonality("火爆");
        kbService.saveCharacter(c);

        WritingContext ctx = assembler.assemble(p.getId(), 1, "第一章", "张三登场", null);

        String system = ctx.toSystemPrompt();
        String user = ctx.toUserPrompt();

        assertNotNull(system);
        assertNotNull(user);
        assertTrue(system.contains("热血"));
        assertTrue(system.contains("天道"));
        assertTrue(user.contains("张三"));
    }
}
