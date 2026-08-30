package com.writing.novel.memory;

import com.writing.novel.domain.Outline;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.StoryFact;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteStoryCanon;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.persistence.sqlite.StoryFactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * p10-w2-07: 真典消费——ContextAssembler 注入 ACTIVE 事实到写作上下文（长程记忆替代级联摘要）。
 */
class StoryBibleConsumptionTest {

    @TempDir
    Path tempDir;
    private SqliteNovelRepository repo;
    private StoryFactRepository factRepo;
    private ContextAssembler assembler;
    private String pid;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("consume.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        factRepo = new StoryFactRepository(cp);

        VectorRetrievalService vr = mock(VectorRetrievalService.class);
        when(vr.search(anyString(), anyString(), anyInt())).thenReturn(java.util.List.of());
        com.writing.novel.service.EntityGraphService eg = mock(com.writing.novel.service.EntityGraphService.class);
        when(eg.recallConsistencySection(anyString(), anyInt())).thenReturn("");
        com.writing.novel.service.ClimaxDiversityTracker cl = mock(com.writing.novel.service.ClimaxDiversityTracker.class);
        when(cl.generateRotationConstraint()).thenReturn("");
        com.writing.novel.service.NarrativeThreadTracker nt = mock(com.writing.novel.service.NarrativeThreadTracker.class);
        when(nt.getThreadSection(anyString())).thenReturn("");
        com.writing.novel.service.DiversityTracker dv = mock(com.writing.novel.service.DiversityTracker.class);
        when(dv.generateAllConstraints()).thenReturn("");
        com.writing.novel.service.ClicheDetector cd = mock(com.writing.novel.service.ClicheDetector.class);
        when(cd.generateForbiddenList(anyString(), anyInt())).thenReturn("");

        assembler = new ContextAssembler(repo, new KnowledgeBaseService(repo, vr),
                mock(SummaryService.class), vr, eg, cl, nt, dv, cd, null);
        assembler.setStoryCanon(new SqliteStoryCanon(factRepo));

        Project p = new Project("消费书", "构思", "玄幻");
        repo.saveProject(p);
        pid = p.getId();
        repo.saveOutline(new Outline(pid, "总纲"));
    }

    @Test
    void 真典有事实时注入写作上下文() {
        factRepo.save(new StoryFact(pid, StoryFact.FactType.CHARACTER_STATE, "季衡", "金丹期", 30));
        StoryFact rule = new StoryFact(pid, StoryFact.FactType.WORLD_RULE, "时代锚点", "明代背景", 1);
        rule.setImportance(5);
        factRepo.save(rule);

        WritingContext ctx = assembler.assemble(pid, 35, "第35章", "梗概", null);
        assertNotNull(ctx.getBibleSection());
        assertTrue(ctx.getBibleSection().contains("季衡"), "bibleSection 应含角色事实");
        assertTrue(ctx.getBibleSection().contains("金丹期"));
        assertTrue(ctx.getBibleSection().contains("时代锚点"), "应含世界铁律");
        assertTrue(ctx.toSystemPrompt().contains("故事真典"), "system prompt 应含【故事真典】段");
    }

    @Test
    void 真典为空时bibleSection为空_不破坏上下文() {
        WritingContext ctx = assembler.assemble(pid, 1, "第1章", "梗概", null);
        // 无事实 → bibleSection 空/blank
        assertTrue(ctx.getBibleSection() == null || ctx.getBibleSection().isBlank());
        assertFalse(ctx.toSystemPrompt().contains("故事真典"), "无事实不应注入真典段");
    }

    @Test
    void SUPERSEDED事实不注入_仅ACTIVE() {
        StoryFact old = new StoryFact(pid, StoryFact.FactType.CHARACTER_STATE, "季衡", "筑基期", 1);
        factRepo.save(old);
        StoryFact newer = new StoryFact(pid, StoryFact.FactType.CHARACTER_STATE, "季衡", "金丹期", 30);
        factRepo.supersede(old, newer);

        WritingContext ctx = assembler.assemble(pid, 35, "第35章", "梗概", null);
        assertTrue(ctx.getBibleSection().contains("金丹期"), "应注入 ACTIVE 新事实");
        assertFalse(ctx.getBibleSection().contains("筑基期"), "不应注入 SUPERSEDED 旧事实");
    }
}
