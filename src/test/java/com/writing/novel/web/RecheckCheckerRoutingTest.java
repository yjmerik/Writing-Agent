package com.writing.novel.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.ChapterSynopsis;
import com.writing.novel.domain.NovelState;
import com.writing.novel.domain.Outline;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.Volume;
import com.writing.novel.persistence.sqlite.CheckResultRepository;
import com.writing.novel.spi.NovelRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * NCS-087：重新检查按检查器路由——信封按 (chapter, checker) 存储，只重跑 continuity
 * 覆盖不了 consistency-guard 的 UNKNOWN（第二轮旅程 A-2）。
 * 1) recheck 携带 checkers=["consistency-guard"] 真实重跑一致性守卫并覆盖该键信封；
 * 2) checkers=["correction-verification"] 409 明示走担责豁免（复验依赖当时原问题清单，不可重建）；
 * 3) 不带 checkers 保持向后兼容（仅 continuity）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(StubLlmGatewayTestConfig.class)
@TestPropertySource(properties = {
        "persistence.embedding=stub",
        "persistence.sqlite.db-path=./data/test-recheck-checker-routing.db",
        "persistence.vector-store.index-path=./data/test-recheck-checker-routing-vecs.idx"
})
class RecheckCheckerRoutingTest {

    private static final String LONG_BODY = "按检查器路由重检的正文内容，长度满足已完成章节口径。".repeat(20);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired NovelRepository repository;
    @Autowired CheckResultRepository checkResultRepository;

    private String pid;

    @BeforeAll
    static void createDataDir() { new File("./data").mkdirs(); }

    @BeforeEach
    void setUp() {
        Project project = new Project("重检路由", "概念", "武侠");
        project.setState(NovelState.WRITING);
        repository.saveProject(project);
        pid = project.getId();

        Outline outline = new Outline(pid, "总纲：重检路由验证");
        Volume volume = new Volume(pid, 1, "卷一", "卷摘要");
        volume.addSynopsis(new ChapterSynopsis(pid, 1, "第一章", "章节梗概"));
        outline.addVolume(volume);
        repository.saveOutline(outline);

        Chapter chapter = new Chapter(pid, 1, "第一章");
        chapter.setContent(LONG_BODY);
        chapter.setStatus(Chapter.Status.NEEDS_MANUAL_REVIEW);
        repository.saveChapter(chapter);
        repository.findProject(pid).ifPresent(p -> {
            p.setCurrentChapterIndex(1);
            repository.saveProject(p);
        });
    }

    private JsonNode reviewContext() throws Exception {
        MvcResult result = mvc.perform(get("/api/projects/" + pid + "/chapters/1/review-context"))
                .andExpect(status().isOk()).andReturn();
        return om.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private String recheckBody(JsonNode context, List<String> checkers) {
        var body = om.createObjectNode();
        var identity = body.putObject("identity");
        identity.put("projectId", pid);
        identity.put("chapterIndex", 1);
        identity.put("version", context.path("identity").path("version").asInt());
        identity.put("contentHash", context.path("identity").path("contentHash").asText());
        if (checkers != null) {
            var arr = body.putArray("checkers");
            checkers.forEach(arr::add);
        }
        return body.toString();
    }

    private CheckResultRepository.LatestCheck latest(int chapter, String checker) {
        return checkResultRepository.latestForChapter(pid, chapter).stream()
                .filter(c -> c.checker().equals(checker)).findFirst().orElseThrow();
    }

    @Test
    void 指定consistencyGuard重检真实覆盖该键信封() throws Exception {
        checkResultRepository.save(pid, 1, "consistency-guard",
                com.writing.novel.agent.CheckResultEnvelope.Status.UNKNOWN, "守卫输出不可解析", LONG_BODY);
        checkResultRepository.save(pid, 1, "continuity",
                com.writing.novel.agent.CheckResultEnvelope.Status.UNKNOWN, "连续性中断", LONG_BODY);
        assertEquals(2, checkResultRepository.unknownCount(pid));

        JsonNode context = reviewContext();
        mvc.perform(post("/api/projects/" + pid + "/chapters/1/recheck")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(recheckBody(context, List.of("consistency-guard"))))
                .andExpect(status().isOk());

        assertEquals(com.writing.novel.agent.CheckResultEnvelope.Status.VALID_EMPTY,
                latest(1, "consistency-guard").status(), "守卫键被真实重检覆盖");
        assertEquals(com.writing.novel.agent.CheckResultEnvelope.Status.UNKNOWN,
                latest(1, "continuity").status(), "未指定的检查器不受影响");
        assertEquals(1, checkResultRepository.unknownCount(pid), "未查清数仅减去被重检的键");
    }

    @Test
    void correctionVerification不可重跑并明示豁免出路() throws Exception {
        checkResultRepository.save(pid, 1, "correction-verification",
                com.writing.novel.agent.CheckResultEnvelope.Status.UNKNOWN, "复验中断", LONG_BODY);
        JsonNode context = reviewContext();
        mvc.perform(post("/api/projects/" + pid + "/chapters/1/recheck")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(recheckBody(context, List.of("correction-verification"))))
                .andExpect(status().isConflict());
        assertEquals(com.writing.novel.agent.CheckResultEnvelope.Status.UNKNOWN,
                latest(1, "correction-verification").status(), "不可重跑的信封保持原状");
    }

    @Test
    void 不带checkers保持向后兼容仅重检连续性() throws Exception {
        checkResultRepository.save(pid, 1, "continuity",
                com.writing.novel.agent.CheckResultEnvelope.Status.UNKNOWN, "连续性中断", LONG_BODY);
        JsonNode context = reviewContext();
        String response = mvc.perform(post("/api/projects/" + pid + "/chapters/1/recheck")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(recheckBody(context, null)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(response.contains("grade") || response.contains("reportCurrent"),
                "返回章节质量报告: " + response.substring(0, Math.min(120, response.length())));
        assertEquals(0, checkResultRepository.unknownCount(pid), "continuity 重检覆盖该键");
    }
}
