package com.writing.novel.web;

import com.writing.novel.domain.Project;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(StubLlmGatewayTestConfig.class)
@TestPropertySource(properties = {"persistence.embedding=stub", "llm.replay.mode=replay",
        "persistence.sqlite.db-path=./data/test-task-list.db",
        "persistence.vector-store.index-path=./data/test-task-list.idx"})
class WritingTaskListTest {
    @Autowired MockMvc mvc;
    @Autowired NovelRepository repo;

    @Test void listsPersistedHistoryWithProgressAndReasonsOnlyForThisProject() throws Exception {
        Project p = new Project("任务中心", "测试", "mystery");
        Project other = new Project("其他作品", "测试", "mystery");
        repo.saveProject(p); repo.saveProject(other);
        for (WritingTask.Status status : WritingTask.Status.values()) {
            WritingTask t = new WritingTask(p.getId(), 12, WritingTask.Type.AUTO_RUN);
            t.setInstruction("写完第一卷"); t.setStatus(status); t.setCurrentChapter(4);
            t.setChaptersWritten(3); t.setTotalWords(9000); t.setErrorMessage("等待作者决定");
            repo.saveWritingTask(t);
        }
        repo.saveWritingTask(new WritingTask(other.getId(), 99));
        mvc.perform(get("/api/projects/{id}/writing-tasks", p.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[0].projectId").value(p.getId()))
                .andExpect(jsonPath("$[0].instruction").value("写完第一卷"))
                .andExpect(jsonPath("$[0].currentChapter").value(4))
                .andExpect(jsonPath("$[0].chaptersWritten").value(3))
                .andExpect(jsonPath("$[0].totalChapters").value(12))
                .andExpect(jsonPath("$[0].totalWords").value(9000))
                .andExpect(jsonPath("$[0].errorMessage").value("等待作者决定"))
                .andExpect(jsonPath("$[0].createdAt").isNotEmpty());
    }

    @Test void newProjectHasEmptyHistory() throws Exception {
        Project p = new Project("空任务", "测试", "mystery"); repo.saveProject(p);
        mvc.perform(get("/api/projects/{id}/writing-tasks", p.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }
}
