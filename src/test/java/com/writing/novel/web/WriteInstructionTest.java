package com.writing.novel.web;

import com.writing.novel.domain.NovelState;
import com.writing.novel.domain.Outline;
import com.writing.novel.domain.Project;
import com.writing.novel.orchestrator.NovelOrchestrator;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.service.WritingTaskExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * p18-03: 写前指令通道——POST /write-chapter 接受 instruction，透传本章 writingNotes。
 * 原状：write-chapter / write-all 不接收任何用户指令参数，用户诉求只能间接改梗概传导。
 */
class WriteInstructionTest {

    @TempDir
    Path tempDir;
    private SqliteNovelRepository repo;
    private NovelOrchestrator orchestrator;
    private MockMvc mvc;
    private String projectId;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("wi.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        orchestrator = mock(NovelOrchestrator.class);
        Project project = new Project("写前指令", "测试", "悬疑");
        project.setState(NovelState.WRITING);
        repo.saveProject(project);
        repo.saveOutline(new Outline(project.getId(), "总纲"));
        projectId = project.getId();
        ContentController controller = new ContentController(repo, orchestrator, null, null);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void writeChapter携带instruction透传到Orchestrator() throws Exception {
        mvc.perform(post("/api/projects/" + projectId + "/write-chapter")
                        .contentType("application/json")
                        .content("{\"instruction\":\"本章重点写主角与师父的诀别\"}"))
                .andExpect(status().isOk());

        verify(orchestrator).runWritingOneChapter(eq(projectId),
                eq("本章重点写主角与师父的诀别"));
    }

    @Test
    void 无instruction时走原路径行为不变() throws Exception {
        mvc.perform(post("/api/projects/" + projectId + "/write-chapter"))
                .andExpect(status().isOk());
        verify(orchestrator).runWritingOneChapter(eq(projectId), eq((String) null));
    }

    @Test
    void 指令注入writingNotes_带作者指令标记() {
        String notes = NovelOrchestrator.prependAuthorInstruction(
                "节奏：标准章", "本章写诀别，情感克制");
        assertTrue(notes.startsWith("【作者指令】本章写诀别，情感克制"),
                "作者指令应前置于 writingNotes: " + notes);
        assertTrue(notes.contains("节奏：标准章"), "原 notes 应保留");

        assertEquals("节奏：标准章", NovelOrchestrator.prependAuthorInstruction("节奏：标准章", null),
                "无指令时原样返回");
        assertEquals("节奏：标准章", NovelOrchestrator.prependAuthorInstruction("节奏：标准章", "  "),
                "空白指令视同无指令");
    }
}
