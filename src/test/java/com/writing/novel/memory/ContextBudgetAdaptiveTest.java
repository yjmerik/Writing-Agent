package com.writing.novel.memory;

import com.writing.novel.domain.Character;
import com.writing.novel.domain.KnowledgeEntry;
import com.writing.novel.domain.Outline;
import com.writing.novel.domain.Project;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.spi.VectorSearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.*;

/**
 * NCS-112: 上下文预算裁剪自适应——
 * 1) relevantSettings 小预算下截断保留而非整体置空；
 * 2) 截断常量随预算线性缩放（小窗裁得动、大窗放大）；
 * 3) 不可裁盲区超预算 → CONTEXT_BUDGET_EXCEEDED 不再静默（溢出估算暴露）。
 */
class ContextBudgetAdaptiveTest {

    @TempDir
    Path tempDir;
    private SqliteNovelRepository repository;
    private ContextAssembler assembler;
    private String pid;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("budget.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repository = new SqliteNovelRepository(cp);
        var project = new Project("预算自适应", "构思", "wuxia");
        repository.saveProject(project);
        pid = project.getId();

        var vectorRetrieval = mock(VectorRetrievalService.class);
        var kb = new KnowledgeBaseService(repository, vectorRetrieval, new MemoryRevision());
        var longEntry = new KnowledgeEntry(pid, "GEOGRAPHY", "长设定", "很长的相关设定内容。".repeat(400));
        kb.saveEntry(longEntry);
        when(vectorRetrieval.search(anyString(), anyString(), anyInt(), anyDouble()))
                .thenAnswer(inv -> List.of(new VectorSearchResult(longEntry.getId(), 0.9)));
        when(vectorRetrieval.getText(anyString())).thenReturn(null);
        repository.saveOutline(new Outline(pid, "总纲"));
        kb.saveCharacter(new Character(pid, "林凡", "主角"));

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

        assembler = new ContextAssembler(repository, kb, mock(SummaryService.class),
                vectorRetrieval, entityGraph, climax, threads, diversity, cliche, new MemoryRevision());
    }

    @Test
    void 缩放常量_小预算保持基准_大预算线性放大() {
        assertEquals(400, ContextAssembler.scaled(400, 16000), "基准预算原值");
        assertEquals(400, ContextAssembler.scaled(400, 2000), "小预算不向下折损（记忆核心段保护）");
        assertEquals(1600, ContextAssembler.scaled(400, 64000), "大预算放大 4 倍");
        assertEquals(1200, ContextAssembler.scaled(1200, 4000));
    }

    @Test
    void 小预算下_relevantSettings截断保留而非置空() {
        WritingContext ctx = assembler.assemble(pid, 2, "第2章", "梗概", null, 1200);
        String settings = ctx.getRelevantSettings();
        assertNotNull(settings);
        assertFalse(settings.isBlank(), "预算紧张时长程召回内容应截断保留（旧逻辑整体置空）");
        assertTrue(settings.length() <= 1200 * 3 / 2 + 100, "截断后不超预算量级");
        assertTrue(ctx.getManifest().getTrimmedSections().contains("relevantSettings"),
                "截断应记入 manifest trimmed");
    }

    @Test
    void 不可裁盲区超预算_溢出暴露不再静默() {
        // 极小预算：taskSection/constraints 等不可裁段之和必然超出 → 溢出估算 > 0
        WritingContext ctx = assembler.assemble(pid, 2, "第2章", "梗概", null, 300);
        assertTrue(assembler.getLastBudgetOverflowEstimate() > 300,
                "盲区超预算应记录溢出估算（旧逻辑静默发送）");
        assertNotNull(ctx.getTaskSection(), "不可裁段保留");
    }

    @Test
    void 预算充足时_无溢出() {
        assembler.assemble(pid, 2, "第2章", "梗概", null, 32000);
        assertEquals(0, assembler.getLastBudgetOverflowEstimate());
    }
}
