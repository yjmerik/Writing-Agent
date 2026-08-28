package com.writing.novel.service;

import com.writing.novel.domain.*;
import com.writing.novel.orchestrator.NovelOrchestrator;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * p18-01: write-all 遇 INTERACTIVE checkpoint 暂停时任务终态应为 PAUSED 而非 FAILED。
 * 原判定不识别 PAUSED_AT_CHECKPOINT，落入 FAILED「写作未完成: 写了1/50章」——用户把正常审核点当失败。
 */
class PausedTerminalStateTest {

    @TempDir
    Path tempDir;
    private SqliteNovelRepository repo;
    private String pid;

    @BeforeEach
    void setUp() {
        System.setProperty(com.writing.novel.telemetry.EventLog.PATH_PROPERTY,
                tempDir.resolve("events.jsonl").toString());
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("paused.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);

        Project p = new Project("交互测试", "构思", "武侠");
        p.setState(NovelState.PAUSED_AT_CHECKPOINT); // 写完 1 章后挂起审核点
        p.setCurrentChapterIndex(1);
        repo.saveProject(p);
        pid = p.getId();

        // 大纲还有未写章节（chapterIndex 2 > current 1）——排除 outlineExhausted 分支
        Outline outline = new Outline(pid, "总纲");
        Volume v = new Volume(pid, 1, "第一卷", "卷摘要");
        v.getSynopses().add(new ChapterSynopsis(pid, 1, "第1章", "开局"));
        v.getSynopses().add(new ChapterSynopsis(pid, 2, "第2章", "待写"));
        outline.getVolumes().add(v);
        repo.saveOutline(outline);

        WritingPreferences prefs = new WritingPreferences(pid);
        prefs.setCostBudgetYuan(0.0); // 不启用预算
        repo.saveWritingPreferences(prefs);
    }

    @AfterEach
    void tearDown() {
        com.writing.novel.telemetry.EventContext.clear();
    }

    @Test
    void 交互模式审核点暂停终态为PAUSED而非FAILED() throws Exception {
        NovelOrchestrator orch = mock(NovelOrchestrator.class);
        when(orch.runWritingLoop(eq(pid), eq(5), any(), any())).thenReturn(1); // 写 1 章后循环遇暂停退出

        WritingTaskExecutor executor = new WritingTaskExecutor(orch, repo, null);
        WritingTask task = new WritingTask(pid, 5);
        repo.saveWritingTask(task);

        Method m = WritingTaskExecutor.class.getDeclaredMethod("executeWriting", WritingTask.class, int.class);
        m.setAccessible(true);
        m.invoke(executor, task, 5);

        WritingTask reloaded = repo.findWritingTask(task.getId()).orElseThrow();
        assertEquals(WritingTask.Status.PAUSED, reloaded.getStatus(),
                "审核点暂停应置 PAUSED，实际=" + reloaded.getStatus());
        assertNotNull(reloaded.getErrorMessage());
        assertTrue(reloaded.getErrorMessage().contains("审核") || reloaded.getErrorMessage().contains("审批"),
                "message 应说明等待人工审批: " + reloaded.getErrorMessage());
    }

    @Test
    void 非暂停状态下未写满仍FAILED_正常失败语义不变() throws Exception {
        Project p = repo.findProject(pid).orElseThrow();
        p.setState(NovelState.WRITING);
        repo.saveProject(p);

        NovelOrchestrator orch = mock(NovelOrchestrator.class);
        when(orch.runWritingLoop(eq(pid), eq(5), any(), any())).thenReturn(1);

        WritingTaskExecutor executor = new WritingTaskExecutor(orch, repo, null);
        WritingTask task = new WritingTask(pid, 5);
        repo.saveWritingTask(task);

        Method m = WritingTaskExecutor.class.getDeclaredMethod("executeWriting", WritingTask.class, int.class);
        m.setAccessible(true);
        m.invoke(executor, task, 5);

        WritingTask reloaded = repo.findWritingTask(task.getId()).orElseThrow();
        assertEquals(WritingTask.Status.FAILED, reloaded.getStatus(),
                "非暂停状态下未写满应保持 FAILED 语义");
    }
}
