package com.writing.novel.telemetry;

import com.writing.novel.domain.Checkpoint;
import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.ChapterQualityReport;
import com.writing.novel.domain.Project;
import com.writing.novel.orchestrator.NovelOrchestrator;
import com.writing.novel.service.CheckpointTargetService;
import com.writing.novel.service.CheckWaiverService;
import com.writing.novel.service.WritingTaskExecutor;
import com.writing.novel.spi.NovelRepository;
import com.writing.novel.web.CheckpointController;
import com.writing.novel.web.ReviewDecisionController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * NCS-091（第二轮旅程 A-3）：recheck 与 checkpoint 修订两条入口的 LLM 调用
 * 必须携带 projectId（NCS-086 覆盖了 preview/apply/rewriteChapter/editPassage，
 * 本条补齐剩余两个包装入口），完成后 EventContext 清理不串项目。
 */
class RecheckTelemetryProjectIdTest {

    private final NovelRepository repo = mock(NovelRepository.class);
    private final NovelOrchestrator orchestrator = mock(NovelOrchestrator.class);
    private final CheckpointTargetService checkpointTargetService = mock(CheckpointTargetService.class);
    private final WritingTaskExecutor executor =
            new WritingTaskExecutor(mock(NovelOrchestrator.class), repo);

    private final String pid = "11111111-1111-1111-1111-111111111111";

    @BeforeEach
    void cleanContext() {
        EventContext.clear();
    }

    @Test
    void recheck期间EventContext携带projectId() {
        AtomicReference<String> captured = new AtomicReference<>();
        ChapterQualityReport report = new ChapterQualityReport(1, "第一章", 100);
        report.setIssues(java.util.List.of());
        when(orchestrator.recheckChapter(eq(pid), eq(1), any(), any())).thenAnswer(inv -> {
            captured.set(EventContext.projectId());
            return report;
        });
        when(repo.findChapterByIndex(pid, 1)).thenReturn(java.util.Optional.of(chapter()));

        ReviewDecisionController controller = new ReviewDecisionController(
                repo, executor, orchestrator, mock(CheckWaiverService.class));
        controller.recheck(pid, 1, Map.of("identity", Map.of(
                "projectId", pid, "chapterIndex", 1, "version", 1, "contentHash", "h")));

        assertEquals(pid, captured.get(), "recheck 的 LLM 调用必须携带 projectId");
        assertNull(EventContext.projectId(), "完成后 EventContext 必须清理");
    }

    @Test
    void checkpoint修订期间EventContext携带projectId() {
        AtomicReference<String> captured = new AtomicReference<>();
        Checkpoint cp = new Checkpoint(pid, Checkpoint.Type.AFTER_INITIAL_SETUP, "初始设定待审核");
        cp.setPayload("{}");
        when(checkpointTargetService.requirePending(eq(pid), eq("cp-1"))).thenReturn(cp);
        when(orchestrator.reviseCheckpoint(eq(pid), eq(cp), eq("调整设定"))).thenAnswer(inv -> {
            captured.set(EventContext.projectId());
            return null;
        });
        when(repo.findProject(pid)).thenReturn(java.util.Optional.of(project()));

        CheckpointController controller = new CheckpointController(
                repo, orchestrator, checkpointTargetService, executor);
        controller.modify(pid, "cp-1", Map.of("instructions", "调整设定"));

        assertEquals(pid, captured.get(), "checkpoint 修订的 LLM 调用必须携带 projectId");
        assertNull(EventContext.projectId(), "完成后 EventContext 必须清理");
    }

    private Chapter chapter() {
        Chapter chapter = new Chapter(pid, 1, "第一章");
        chapter.setContent("遥测归属验证正文。".repeat(20));
        return chapter;
    }

    private Project project() {
        Project p = new Project("遥测归属", "概念", "武侠");
        p.setState(com.writing.novel.domain.NovelState.WRITING);
        return p;
    }
}
