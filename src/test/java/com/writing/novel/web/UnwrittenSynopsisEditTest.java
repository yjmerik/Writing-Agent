package com.writing.novel.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.domain.ChapterSynopsis;
import com.writing.novel.domain.Outline;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.Volume;
import com.writing.novel.spi.NovelRepository;
import com.writing.novel.web.dto.CreateProjectRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(UnwrittenSynopsisEditTest.ReplanRangeAwareGatewayConfig.class)
@TestPropertySource(properties = {
        "persistence.embedding=stub",
        "persistence.sqlite.db-path=./data/test-hitl-synopsis.db",
        "persistence.vector-store.index-path=./data/test-hitl-synopsis-vecs.idx"
})
class UnwrittenSynopsisEditTest {

    /**
     * p32-03: replan 请求区间为当前章+1 起——桩 PLOTTING 输出必须落在区间内
     * （原共享桩返回第1章，正是报告 #1 的越界章号形态，现被区间门正确拒绝）。
     */
    @org.springframework.boot.test.context.TestConfiguration
    static class ReplanRangeAwareGatewayConfig {
        @org.springframework.context.annotation.Primary
        @org.springframework.context.annotation.Bean
        com.writing.novel.llm.LlmGateway replanRangeAwareGateway() {
            return new com.writing.novel.llm.LlmGateway() {
                @Override
                public com.writing.novel.llm.LlmResponse complete(com.writing.novel.llm.LlmRequest req) {
                    String content = switch (req.getTaskType()) {
                        case PLOTTING -> "【总纲】重规划。\n\n【卷1】新未写卷\n梗概。\n第2章 重规划新章：灵根测试后续。";
                        default -> "这是桩 LLM 生成的正文内容，足够长用于测试章节写入和存储。".repeat(80);
                    };
                    return new com.writing.novel.llm.LlmResponse(content, 50, 100);
                }

                @Override
                public void streamComplete(com.writing.novel.llm.LlmRequest req,
                                           com.writing.novel.llm.client.StreamHandler handler) {
                }
            };
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired NovelRepository repository;

    @BeforeAll
    static void createDataDir() { new File("./data").mkdirs(); }

    @Test
    void putUnwrittenSynopsis_rejectsChangedWrittenPrefix() throws Exception {
        String id = seedProjectWithPartialOutline();

        String body = """
                {
                  "projectId": "%s",
                  "premise": "原始总纲",
                  "volumes": [{
                    "projectId": "%s",
                    "volumeIndex": 1,
                    "title": "篡改已写卷名",
                    "summary": "篡改已写卷摘要",
                    "synopses": [
                      {"projectId":"%s","chapterIndex":1,"title":"HACKED","synopsis":"篡改已写梗概"},
                      {"projectId":"%s","chapterIndex":2,"title":"人改未写","synopsis":"新未写梗概"}
                    ]
                  }]
                }
                """.formatted(id, id, id, id);

        mvc.perform(put("/api/projects/" + id + "/outline")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        String json = mvc.perform(get("/api/projects/" + id + "/outline"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode root = om.readTree(json);

        assertEquals(1, root.path("volumes").size(), "PUT must not append a second volume");
        assertEquals("原始总纲", root.path("premise").asText());
        assertEquals("卷一已写", firstVolume(root).path("title").asText());
        assertEquals("已写卷摘要", firstVolume(root).path("summary").asText());
        assertEquals("第1章 旧已写", synopsisAt(root, 1).path("title").asText());
        assertEquals("已写梗概不得改", synopsisAt(root, 1).path("synopsis").asText());
        assertEquals("第2章 旧未写", synopsisAt(root, 2).path("title").asText());
        assertEquals("旧未写梗概", synopsisAt(root, 2).path("synopsis").asText());
    }

    @Test
    void postReplan_replacesOnlyUnwritten() throws Exception {
        String id = seedProjectWithPartialOutline("INTERACTIVE");

        mvc.perform(post("/api/projects/" + id + "/replan"))
                .andExpect(status().isOk());

        String json = mvc.perform(get("/api/projects/" + id + "/outline"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode root = om.readTree(json);

        assertEquals("第1章 旧已写", synopsisAt(root, 1).path("title").asText());
        assertEquals("已写梗概不得改", synopsisAt(root, 1).path("synopsis").asText());
        assertFalse(hasSynopsisText(root, "旧未写梗概"), "unwritten synopsis must be replaced");

        mvc.perform(get("/api/projects/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("PAUSED_AT_CHECKPOINT"));

        mvc.perform(get("/api/projects/" + id + "/checkpoints"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.type=='AFTER_VOLUME_OUTLINE')]").exists());
    }

    private String seedProjectWithPartialOutline() throws Exception {
        return seedProjectWithPartialOutline("SOLO");
    }

    private String seedProjectWithPartialOutline(String mode) throws Exception {
        CreateProjectRequest req = new CreateProjectRequest("synopsis", "c", "tag", mode, null);
        String json = mvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andReturn().getResponse().getContentAsString();
        String id = om.readTree(json).get("id").asText();

        Outline outline = new Outline(id, "原始总纲");
        Volume v1 = new Volume(id, 1, "卷一已写", "已写卷摘要");
        v1.addSynopsis(new ChapterSynopsis(id, 1, "第1章 旧已写", "已写梗概不得改"));
        v1.addSynopsis(new ChapterSynopsis(id, 2, "第2章 旧未写", "旧未写梗概"));
        outline.addVolume(v1);
        repository.saveOutline(outline);

        Project project = repository.findProject(id).orElseThrow();
        project.setCurrentChapterIndex(1);
        repository.saveProject(project);
        return id;
    }

    private static JsonNode firstVolume(JsonNode outline) {
        return outline.path("volumes").get(0);
    }

    private static JsonNode synopsisAt(JsonNode outline, int chapterIndex) {
        for (JsonNode volume : outline.path("volumes")) {
            for (JsonNode syn : volume.path("synopses")) {
                if (syn.path("chapterIndex").asInt() == chapterIndex) {
                    return syn;
                }
            }
        }
        throw new AssertionError("missing chapter " + chapterIndex + " in " + outline);
    }

    private static boolean hasSynopsisText(JsonNode outline, String text) {
        List<String> found = new ArrayList<>();
        for (JsonNode volume : outline.path("volumes")) {
            for (JsonNode syn : volume.path("synopses")) {
                found.add(syn.path("synopsis").asText());
                if (text.equals(syn.path("synopsis").asText())) {
                    return true;
                }
            }
        }
        return false;
    }
}
