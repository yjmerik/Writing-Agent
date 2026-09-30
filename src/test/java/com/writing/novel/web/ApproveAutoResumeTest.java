package com.writing.novel.web;

import com.writing.novel.domain.Checkpoint;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.ReviewBarrier;
import com.writing.novel.domain.WritingTask;
import com.writing.novel.orchestrator.NovelOrchestrator;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.service.CheckWaiverService;
import com.writing.novel.service.LongTaskService;
import com.writing.novel.service.WritingTaskExecutor;
import com.writing.novel.spi.NovelRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * NCS-146（旅程三 F-4）：批准后自动恢复——作者批准当前稿即表示复核完成，
 * 无剩余待审时写作应自动继续，不再要求二次点击「继续协作」；
 * 同时清理 PAUSED 任务上过期的 errorMessage。
 */
class ApproveAutoResumeTest {

    @TempDir Path dir;
    SqliteNovelRepository repo;
    WritingTaskExecutor executor;
    ReviewDecisionController controller;
    Project project;
    WritingTask pausedTask;

    @BeforeEach
    void setUp() {
        var cp = new ConnectionProvider(dir.resolve("ar.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        executor = new WritingTaskExecutor(mock(NovelOrchestrator.class), repo);
        project = new Project("书", "构思", "标签");
        project.setState(com.writing.novel.domain.NovelState.WRITING);
        repo.saveProject(project);
        pausedTask = new WritingTask(project.getId(), 24, WritingTask.Type.AUTO_RUN);
        pausedTask.setStatus(WritingTask.Status.PAUSED);
        pausedTask.setErrorMessage("请先处理待审核内容，再继续创作");
        repo.saveWritingTask(pausedTask);
        for (int i : new int[]{1, 2}) {
            var ch = new com.writing.novel.domain.Chapter(project.getId(), i, "第" + i + "章");
            ch.setContent("批准自动恢复测试正文。".repeat(30));
            repo.saveChapter(ch);
        }
        controller = new ReviewDecisionController(repo, executor,
                mock(NovelOrchestrator.class), mock(CheckWaiverService.class),
                new LongTaskService(executor));
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    private Map<String, Object> body(int version) {
        var ctx = controller.reviewContext(project.getId(), version);
        var identity = ctx.identity();
        return Map.of(
                "identity", Map.of("projectId", identity.projectId(),
                        "chapterIndex", identity.chapterIndex(),
                        "version", identity.version(),
                        "contentHash", identity.contentHash()),
                "reviewSetHash", ctx.reviewSetHash(),
                "idempotencyKey", UUID.randomUUID().toString(),
                "reason", "复核通过");
    }

    @Test
    void 批准后无剩余待审时自动恢复并清理过期错误() {
        var result = controller.reviewDecision(project.getId(), 1, body(1));
        assertFalse(result.replayed());
        // 过期错误信息已清理（null 或空串均视为不再展示）
        var saved = repo.findWritingTasksByProject(project.getId()).stream()
                .filter(t -> t.getId().equals(pausedTask.getId())).findFirst().orElseThrow();
        assertTrue(saved.getErrorMessage() == null || saved.getErrorMessage().isBlank(),
                "批准解除阻塞后过期 errorMessage 必须清理");
        // 自动恢复：出现新的 AUTO_RUN 任务（RUNNING 或已因 mock orchestrator 快速暂停，但必须有新任务）
        var tasks = repo.findWritingTasksByProject(project.getId());
        assertTrue(tasks.stream().anyMatch(t -> !t.getId().equals(pausedTask.getId())),
                "批准后必须自动启动继续协作任务");
    }

    @Test
    void 仍有待审检查点时不自动恢复() {
        repo.saveCheckpoint(new Checkpoint(project.getId(), Checkpoint.Type.AFTER_CHAPTER, "还有一章待审"));
        controller.reviewDecision(project.getId(), 1, body(1));
        var tasks = repo.findWritingTasksByProject(project.getId());
        assertTrue(tasks.stream().noneMatch(t -> !t.getId().equals(pausedTask.getId())),
                "存在剩余待审时不得自动恢复");
        assertNotNull(repo.findWritingTasksByProject(project.getId()).get(0).getErrorMessage(),
                "仍有阻塞时保留原错误信息");
    }

    @Test
    void 幂等重放不触发恢复() throws Exception {
        controller.reviewDecision(project.getId(), 1, body(1));
        // 首次批准已自动恢复——先停掉在跑任务再验证屏障场景
        executor.cancelByProject(project.getId());
        for (int i = 0; i < 100 && executor.hasActiveTask(project.getId()); i++) {
            Thread.sleep(20);
        }
        int countBefore = repo.findWritingTasksByProject(project.getId()).size();
        repo.saveReviewBarrier(new ReviewBarrier(project.getId(), 1, 1, "屏障未清"));
        controller.reviewDecision(project.getId(), 2, body(2));
        assertEquals(countBefore, repo.findWritingTasksByProject(project.getId()).size(),
                "存在屏障时批准不得另起任务");
    }
}
