package com.writing.novel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.domain.Project;
import com.writing.novel.spi.NovelRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.File;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * p33-06（设计 5.3 / 体验 #13）：作者意见形成可验证闭环。
 * 意见持久化（内容/范围/状态）；
 * 「系统认为已落实」（附证据）与「作者确认已落实」分开记录；
 * 未确认的意见保持 OPEN，不被静默关闭；硬性要求不能被自动流程关闭。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(StubLlmGatewayTestConfig.class)
@TestPropertySource(properties = {
        "persistence.embedding=stub",
        "persistence.sqlite.db-path=./data/test-directive-loop.db",
        "persistence.vector-store.index-path=./data/test-directive-loop-vecs.idx"
})
class DirectiveLoopTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired NovelRepository repository;

    @BeforeAll
    static void createDataDir() {
        new File("./data").mkdirs();
    }

    private String pid() {
        Project project = new Project("意见闭环", "概念", "悬疑");
        repository.saveProject(project);
        return project.getId();
    }

    private Map<String, Object> directiveBody(String kind, Integer chapterIndex, String content) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("kind", kind);
        body.put("content", content);
        if (chapterIndex != null) body.put("chapterIndex", chapterIndex);
        return body;
    }

    private String createDirective(String pid, String kind, Integer chapterIndex, String content) throws Exception {
        MvcResult r = mvc.perform(post("/api/projects/" + pid + "/directives")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(directiveBody(kind, chapterIndex, content))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andReturn();
        return om.readTree(r.getResponse().getContentAsString()).path("id").asText();
    }

    @Test
    void 意见登记为OPEN且系统落实与作者确认分开记录() throws Exception {
        String pid = pid();
        String id = createDirective(pid, "ONCE", 4, "为旧发夹提前加一句铺垫");

        // 系统记录落实证据 → APPLIED_SYSTEM，仍未确认
        mvc.perform(post("/api/projects/" + pid + "/directives/" + id + "/apply-evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "evidence", "已在第4章第3段为发夹增加铺垫句",
                                "chapterVersion", 5))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED_SYSTEM"))
                .andExpect(jsonPath("$.systemEvidence").value("已在第4章第3段为发夹增加铺垫句"));

        // 作者确认 → CONFIRMED
        mvc.perform(post("/api/projects/" + pid + "/directives/" + id + "/confirm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        var list = om.readTree(mvc.perform(get("/api/projects/" + pid + "/directives"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(1, list.size());
        assertEquals("CONFIRMED", list.get(0).path("status").asText());
        assertNotNull(list.get(0).path("confirmedAt"));
    }

    @Test
    void 系统落实不等于作者确认_未确认意见保持待办() throws Exception {
        String pid = pid();
        String id = createDirective(pid, "HARD", null, "全书不得出现超自然元素");

        mvc.perform(post("/api/projects/" + pid + "/directives/" + id + "/apply-evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "evidence", "写作约束已注入", "chapterVersion", 3))))
                .andExpect(status().isOk());

        // 列表仍将 APPLIED_SYSTEM 视为待作者确认
        mvc.perform(get("/api/projects/" + pid + "/directives?pendingOnly=true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("APPLIED_SYSTEM"))
                .andExpect(jsonPath("$[0].kind").value("HARD"));

        // HARD 要求不允许 dismiss（撤销需显式 confirm 路径之外的作者裁决——本切片仅支持 dismiss 软性意见）
        mvc.perform(post("/api/projects/" + pid + "/directives/" + id + "/dismiss"))
                .andExpect(status().isConflict());
    }

    @Test
    void 软性意见可撤销且撤销留痕() throws Exception {
        String pid = pid();
        String id = createDirective(pid, "PREFERENCE", null, "对话密度可以再高一些");

        mvc.perform(post("/api/projects/" + pid + "/directives/" + id + "/dismiss"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISMISSED"));

        var list = om.readTree(mvc.perform(get("/api/projects/" + pid + "/directives"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals("DISMISSED", list.get(0).path("status").asText());
    }
}
