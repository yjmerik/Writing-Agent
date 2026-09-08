package com.writing.novel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.Project;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.LlmTaskType;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.spi.NovelRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.File;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * p32-11（UI 验证发现，体验报告 #7）：全书质量评价必须持久化并可读回。
 * 复现：POST /evaluate 只返回结果不落库、无 GET 端点，前端切 tab/刷新即「尚未评价」。
 * 修复后：评价历史落库，GET /evaluate 返回最近一次评价（含时间戳），未评价时 evaluation=null。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(QualityEvaluationPersistenceTest.EvalJsonGatewayConfig.class)
@TestPropertySource(properties = {
        "persistence.embedding=stub",
        "persistence.sqlite.db-path=./data/test-quality-eval-persistence.db",
        "persistence.vector-store.index-path=./data/test-quality-eval-persistence-vecs.idx"
})
class QualityEvaluationPersistenceTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired NovelRepository repository;

    /** GENERAL（质量评价所用任务类型）返回可解析的评价 JSON */
    @TestConfiguration
    static class EvalJsonGatewayConfig {
        @org.springframework.context.annotation.Primary
        @org.springframework.context.annotation.Bean
        LlmGateway evalJsonGateway() {
            return new LlmGateway() {
                @Override
                public LlmResponse complete(LlmRequest req) {
                    if (req.getTaskType() == LlmTaskType.GENERAL) {
                        return new LlmResponse("""
                                {"scores":{"consistency":8,"style":9,"plot":8,"characters":8,
                                 "worldbuilding":9,"dialogue":9,"pattern_diversity":7},
                                 "overallScore":8,
                                 "summary":"设定成熟、文风统一。",
                                 "issues":[{"dimension":"plot","severity":"medium","description":"爽点偏慢热"}]}
                                """, 100, 200);
                    }
                    return new LlmResponse(
                            "这是桩 LLM 生成的正文内容，足够长用于测试章节写入和存储。".repeat(80), 50, 100);
                }

                @Override
                public void streamComplete(LlmRequest req, StreamHandler handler) {
                }
            };
        }
    }

    @BeforeAll
    static void createDataDir() {
        new File("./data").mkdirs();
    }

    private String seededProject() {
        Project project = new Project("评价持久化", "概念", "现实");
        repository.saveProject(project);
        Chapter chapter = new Chapter(project.getId(), 1, "第一章");
        chapter.setContent("正文采样。".repeat(200));
        repository.saveChapter(chapter);
        return project.getId();
    }

    @Test
    void 未评价时读回为空评价后可跨请求读回同一结果() throws Exception {
        String id = seededProject();

        mvc.perform(get("/api/projects/" + id + "/evaluate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evaluation").value(org.hamcrest.Matchers.nullValue()));

        mvc.perform(post("/api/projects/" + id + "/evaluate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overallScore").value(8));

        // 跨请求读回（模拟切 tab/刷新后重新加载）
        mvc.perform(get("/api/projects/" + id + "/evaluate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evaluation.overallScore").value(8))
                .andExpect(jsonPath("$.evaluation.summary").value("设定成熟、文风统一。"))
                .andExpect(jsonPath("$.evaluation.issues[0].description").value("爽点偏慢热"))
                .andExpect(jsonPath("$.evaluatedAt").exists());
    }

    @Test
    void 再次评价后读回返回最新一次() throws Exception {
        String id = seededProject();
        mvc.perform(post("/api/projects/" + id + "/evaluate")).andExpect(status().isOk());
        mvc.perform(post("/api/projects/" + id + "/evaluate")).andExpect(status().isOk());
        mvc.perform(get("/api/projects/" + id + "/evaluate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evaluation.overallScore").value(8));
    }
}
