package com.writing.novel.memory;

import com.writing.novel.agent.CheckResultEnvelope;
import com.writing.novel.domain.ContinuityIssue;
import com.writing.novel.domain.Project;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.spi.NovelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * NCS-101: 风格反馈完整回流与 STYLE 类型——
 * 1) 风格漂移 issue 使用 STYLE 类型（不再误标 WORLDBUILDING 被 RepairArbiter 路由到覆写真典）；
 * 2) 信封「文风」类型解析为 STYLE；
 * 3) buildFeedbackSection 对 STYLE 报告不截 80 字（漂移点+高频词清单完整注入下一章）。
 */
class StyleFeedbackFlowTest {

    @TempDir
    Path tempDir;
    private NovelRepository repository;
    private ContextAssembler assembler;
    private String pid;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("style-flow.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repository = new SqliteNovelRepository(cp);

        var vectorRetrieval = mock(VectorRetrievalService.class);
        when(vectorRetrieval.search(anyString(), anyString(), anyInt())).thenReturn(List.of());
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

        assembler = new ContextAssembler(repository,
                new KnowledgeBaseService(repository, vectorRetrieval, new MemoryRevision()),
                mock(SummaryService.class), vectorRetrieval, entityGraph, climax, threads,
                diversity, cliche, new MemoryRevision());

        var project = new Project("风格回流", "构思", "wuxia");
        repository.saveProject(project);
        pid = project.getId();
        // 反馈注入需偏好存在且开启（默认开启）
        repository.saveWritingPreferences(new com.writing.novel.domain.WritingPreferences(pid));
    }

    @Test
    void 信封文风类型解析为STYLE() {
        var envelope = CheckResultEnvelope.of("【文风|中】叙述腔调与基线漂移。");
        assertEquals(CheckResultEnvelope.Status.SUCCESS, envelope.status());
        assertEquals(ContinuityIssue.Type.STYLE, envelope.issues().get(0).getType());
    }

    @Test
    void STYLE类型持久化往返() {
        var issue = new ContinuityIssue(pid, 3, ContinuityIssue.Type.STYLE,
                ContinuityIssue.Severity.MEDIUM, "风格漂移（评分45/100）：报告");
        repository.saveContinuityIssue(issue);
        var loaded = repository.findUnresolvedIssues(pid);
        assertEquals(1, loaded.size());
        assertEquals(ContinuityIssue.Type.STYLE, loaded.get(0).getType());
    }

    @Test
    void 风格报告不截断_其他类型维持80字上限() {
        String tail = "高频反应词清单：目光一凝×6、嘴角勾起×4、心中一凛×3；句长方差由210降至90";
        String longStyleDesc = "风格漂移（评分45/100）：叙述句式显著趋短，口语化比例上升。" + tail;
        StringBuilder longOther = new StringBuilder();
        while (longOther.length() < 200) longOther.append("超长的世界观问题描述。");
        repository.saveContinuityIssue(new ContinuityIssue(pid, 1, ContinuityIssue.Type.STYLE,
                ContinuityIssue.Severity.MEDIUM, longStyleDesc));
        repository.saveContinuityIssue(new ContinuityIssue(pid, 1, ContinuityIssue.Type.WORLDBUILDING,
                ContinuityIssue.Severity.LOW, longOther.toString()));

        WritingContext ctx = assembler.assemble(pid, 2, "第2章", "梗概", null);
        String feedback = ctx.getFeedbackSection();
        assertTrue(feedback.contains(tail), "STYLE 报告必须完整注入（含尾部高频词清单）");
        assertTrue(feedback.contains("..."), "其他类型仍截断到 80 字");
    }
}
