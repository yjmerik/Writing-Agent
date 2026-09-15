package com.writing.novel.web;

import com.writing.novel.domain.WritingTask;
import com.writing.novel.spi.NovelRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** p40-02：运行详情端点——实际步骤/耗时/失败位置。 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(StubLlmGatewayTestConfig.class)
@TestPropertySource(properties = {
        "persistence.embedding=stub",
        "persistence.sqlite.db-path=./data/test-run-details.db",
        "persistence.vector-store.index-path=./data/test-run-details-vecs.idx"
})
class RunDetailsApiTest {

    @Autowired MockMvc mvc;
    @Autowired NovelRepository repository;

    @Test
    void 无任务时返回NO_TASK() throws Exception {
        String pid = "p-nonexistent-" + System.currentTimeMillis();
        mvc.perform(get("/api/projects/{id}/run-details", pid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NO_TASK"));
    }

    @Test
    void 有任务时返回状态与章节进度() {
        String pid = "p-rd-" + System.currentTimeMillis();
        com.writing.novel.domain.Project project = new com.writing.novel.domain.Project(pid, "c", "t");
        repository.saveProject(project);
        WritingTask task = new WritingTask();
        task.setId("task-rd-1");
        task.setProjectId(pid);
        task.setStatus(WritingTask.Status.RUNNING);
        task.setCurrentChapter(3);
        task.setTotalChapters(20);
        task.setChaptersWritten(2);
        repository.saveWritingTask(task);

        try {
            mvc.perform(get("/api/projects/{id}/run-details", pid))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("RUNNING"))
                    .andExpect(jsonPath("$.currentChapter").value(3))
                    .andExpect(jsonPath("$.totalChapters").value(20))
                    .andExpect(jsonPath("$.chaptersWritten").value(2))
                    .andExpect(jsonPath("$.pendingReviewCount").isNumber());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
