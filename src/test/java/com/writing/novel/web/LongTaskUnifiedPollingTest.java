package com.writing.novel.web;

import com.writing.novel.domain.Project;
import com.writing.novel.orchestrator.NovelOrchestrator;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.service.LongTaskService;
import com.writing.novel.service.WritingTaskExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * NCS-139：统一长任务轮询端点——前端 getLongTask 统一请求
 * GET /api/projects/{id}/long-tasks/{jobId}，后端此前只有
 * /revisions/long-tasks 与 /chapters/{idx}/long-tasks 两个前缀，
 * 导致章级修订预览/读者评审/异步重检轮询全部 404（旅程三 F-1）。
 */
class LongTaskUnifiedPollingTest {

    @TempDir Path dir;
    WritingTaskExecutor executor;
    LongTaskService service;
    MockMvc mvc;
    Project project;

    @BeforeEach
    void setUp() {
        var cp = new ConnectionProvider(dir.resolve("lt.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        var repo = new SqliteNovelRepository(cp);
        executor = new WritingTaskExecutor(mock(NovelOrchestrator.class), repo);
        service = new LongTaskService(executor);
        project = new Project("书", "构思", "标签");
        repo.saveProject(project);
        mvc = MockMvcBuilders.standaloneSetup(new LongTaskController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    private void awaitTerminal(String pid, String jobId) throws Exception {
        for (int i = 0; i < 200; i++) {
            var st = service.get(pid, jobId);
            if (st.isPresent() && !"RUNNING".equals(st.get().status())) return;
            TimeUnit.MILLISECONDS.sleep(20);
        }
    }

    @Test
    void 统一路由可查询任务终态与结果() throws Exception {
        String jobId = service.submit(project.getId(), "agent-revision-preview", "候选生成", () -> "预览内容");
        awaitTerminal(project.getId(), jobId);
        mvc.perform(get("/api/projects/" + project.getId() + "/long-tasks/" + jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(jobId))
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.result").value("预览内容"));
    }

    @Test
    void 运行中任务经同一路由可查询() throws Exception {
        String jobId = service.submit(project.getId(), "reader-review", "读者评审", () -> {
            try {
                TimeUnit.MILLISECONDS.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted");
            }
            return "报告";
        });
        mvc.perform(get("/api/projects/" + project.getId() + "/long-tasks/" + jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"));
        awaitTerminal(project.getId(), jobId);
    }

    @Test
    void 不存在的任务返回404与结构化错误() throws Exception {
        mvc.perform(get("/api/projects/" + project.getId() + "/long-tasks/missing-job"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LONG_TASK_NOT_FOUND"));
    }

    @Test
    void 跨项目任务不可见返回404() throws Exception {
        String jobId = service.submit("other-project", "recheck", "补查", () -> "ok");
        awaitTerminal("other-project", jobId);
        mvc.perform(get("/api/projects/" + project.getId() + "/long-tasks/" + jobId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LONG_TASK_NOT_FOUND"));
    }
}
