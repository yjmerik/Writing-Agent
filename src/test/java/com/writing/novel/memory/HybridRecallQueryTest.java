package com.writing.novel.memory;

import com.writing.novel.domain.Character;
import com.writing.novel.domain.KnowledgeEntry;
import com.writing.novel.domain.Outline;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.Volume;
import com.writing.novel.domain.ChapterSynopsis;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.spi.VectorSearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.anyDouble;

/**
 * NCS-110: 多查询混合检索与词法兜底——
 * 1) 查询规划器产出多路去重查询（大纲/cast/伏笔）；
 * 2) 各路独立检索合并去重（第二路命中不被第一路挤掉）；
 * 3) 向量失败 → 词法兜底通道（cast 实体名命中知识条目），不再静默空召回。
 */
class HybridRecallQueryTest {

    @TempDir
    Path tempDir;
    private SqliteNovelRepository repository;
    private KnowledgeBaseService knowledgeBase;
    private VectorRetrievalService vectorRetrieval;
    private String pid;
    private String entryA;
    private String entryB;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("hybrid.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repository = new SqliteNovelRepository(cp);
        vectorRetrieval = mock(VectorRetrievalService.class);
        knowledgeBase = new KnowledgeBaseService(repository, vectorRetrieval, new MemoryRevision());

        var project = new Project("混合检索", "构思", "wuxia");
        repository.saveProject(project);
        pid = project.getId();
        Outline outline = new Outline(pid, "总纲");
        Volume vol = new Volume(pid, 1, "卷一", "概要");
        ChapterSynopsis syn = new ChapterSynopsis(pid, 2, "夜探", "林凡夜探城主府");
        syn.setCast("林凡");
        vol.getSynopses().add(syn);
        outline.getVolumes().add(vol);
        repository.saveOutline(outline);
        knowledgeBase.saveCharacter(new Character(pid, "林凡", "主角"));

        KnowledgeEntry a = new KnowledgeEntry(pid, "GEOGRAPHY", "城主府", "林凡幼年曾居城主府为仆，府邸坐北朝南。");
        knowledgeBase.saveEntry(a);
        entryA = a.getId();
        KnowledgeEntry b = new KnowledgeEntry(pid, "CUSTOM", "江湖切口", "黑话与切口考。");
        knowledgeBase.saveEntry(b);
        entryB = b.getId();

        var entityGraph = mock(com.writing.novel.service.EntityGraphService.class);
        when(entityGraph.recallConsistencySection(anyString(), anyInt())).thenReturn("");
        var climax = mock(com.writing.novel.service.ClimaxDiversityTracker.class);
        when(climax.generateRotationConstraint()).thenReturn("");
        var threads = mock(com.writing.novel.service.NarrativeThreadTracker.class);
        when(threads.getThreadSection(anyString())).thenReturn("");
        var diversity = mock(com.writing.novel.service.DiversityTracker.class);
        when(diversity.generateAllConstraints(anyBoolean())).thenReturn("");
        var cliche = mock(com.writing.novel.service.ClicheDetector.class);
        when(cliche.generateForbiddenList(anyString(), anyInt())).thenReturn("");
        assembler = new ContextAssembler(repository, knowledgeBase, mock(SummaryService.class),
                vectorRetrieval, entityGraph, climax, threads, diversity, cliche, new MemoryRevision());
        when(vectorRetrieval.getText(anyString())).thenReturn(null);
    }

    private ContextAssembler assembler;

    @Test
    void 查询规划器_多路去重() {
        var queries = RecallQueryPlanner.queries("夜探城主府", "第2章",
                List.of("林凡", "赵九"), List.of("断剑的来历"));
        assertTrue(queries.size() >= 3, "大纲+cast+伏笔至少三路: " + queries);
        assertTrue(queries.get(0).contains("夜探城主府"));
        assertTrue(queries.stream().anyMatch(q -> q.contains("林凡")));
        assertTrue(queries.stream().anyMatch(q -> q.contains("断剑")));
        assertEquals(queries.stream().distinct().count(), queries.size(), "查询去重");
    }

    @Test
    void 各路独立检索_合并去重保最高分() {
        List<String> seenQueries = new ArrayList<>();
        AtomicInteger callCount = new AtomicInteger();
        when(vectorRetrieval.search(anyString(), anyString(), anyInt(), anyDouble())).thenAnswer(inv -> {
            seenQueries.add(inv.getArgument(0));
            String q = inv.getArgument(0);
            int n = callCount.incrementAndGet();
            // cast 路（查询恰为规范名串）命中 B；大纲路命中 A——旧单查询实现只有一路
            if (q.equals("林凡")) return List.of(new VectorSearchResult(entryB, 0.8));
            return List.of(new VectorSearchResult(entryA, 0.5 + 0.001 * n));
        });

        WritingContext ctx = assembler.assemble(pid, 2, "第2章", "林凡夜探城主府", null);
        assertTrue(seenQueries.size() >= 2, "至少发起两路查询: " + seenQueries);
        String settings = ctx.getRelevantSettings();
        assertTrue(settings.contains("城主府"), "第一路命中注入");
        assertTrue(settings.contains("江湖切口"), "cast 路命中不被挤掉");
        // 合并去重：A 只注入一次
        assertEquals(1, countOccurrences(settings, "【城主府】"));
    }

    @Test
    void 向量失败_词法兜底非空() {
        when(vectorRetrieval.search(anyString(), anyString(), anyInt(), anyDouble()))
                .thenThrow(new RuntimeException("embedding down"));

        WritingContext ctx = assembler.assemble(pid, 2, "第2章", "林凡夜探城主府", null);
        assertTrue(ctx.getRelevantSettings().contains("城主府"),
                "向量层故障时词法通道应按 cast 实体名召回知识条目");
    }

    private static int countOccurrences(String s, String sub) {
        int count = 0, idx = 0;
        while ((idx = s.indexOf(sub, idx)) >= 0) { count++; idx += sub.length(); }
        return count;
    }
}
