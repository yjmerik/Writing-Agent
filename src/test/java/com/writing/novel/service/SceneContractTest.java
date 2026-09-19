package com.writing.novel.service;

import com.writing.novel.agent.SceneContractAgent;
import com.writing.novel.domain.*;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.orchestrator.ChapterProduction;
import com.writing.novel.persistence.sqlite.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * NCS-018（方案 §06/F15）：SceneContract 结构化与条件注入。
 * 合同字段结构化存取；有合同注入【场景合同】段、无合同注入不变（指纹稳定）；
 * 按需生成端点落库；大纲 API 暴露合同。
 */
class SceneContractTest {

    @TempDir
    Path tempDir;

    private SqliteNovelRepository repo;
    private String projectId;
    private ChapterSynopsis synopsis;

    @BeforeEach
    void setUp() {
        var cp = new ConnectionProvider(tempDir.resolve("contract.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        Project project = new Project("合同项目", "构思", "武侠");
        repo.saveProject(project);
        projectId = project.getId();
        Outline outline = new Outline(projectId, "总纲");
        Volume v = new Volume(projectId, 1, "第一卷", "卷摘要");
        synopsis = new ChapterSynopsis(projectId, 8, "第八章 渡口焚信",
                "江砚在渡口被迫处置密信");
        v.getSynopses().add(synopsis);
        outline.getVolumes().add(v);
        repo.saveOutline(outline);
    }

    @Test
    void 合同字段持久化存取() {
        String contract = "{\"purpose\":\"让焚信成为不可逆的信任裂口\",\"cost\":\"沈昭再无法自证\"}";
        var loaded = repo.findOutlineByProject(projectId).orElseThrow();
        loaded.getVolumes().get(0).getSynopses().get(0).setSceneContract(contract);
        repo.saveOutline(loaded);
        var reloaded = repo.findOutlineByProject(projectId).orElseThrow()
                .getVolumes().get(0).getSynopses().get(0);
        assertEquals(contract, reloaded.getSceneContract(), "合同 JSON 原样持久化与回读");
        // 旧大纲（无合同）→ null 兼容
        var loaded2 = repo.findOutlineByProject(projectId).orElseThrow();
        loaded2.getVolumes().get(0).getSynopses().get(0).setSceneContract(null);
        repo.saveOutline(loaded2);
        assertNull(repo.findOutlineByProject(projectId).orElseThrow()
                .getVolumes().get(0).getSynopses().get(0).getSceneContract());
    }

    @Test
    void 按需生成端点_落库并可读() throws Exception {
        LlmGateway gateway = new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest req) {
                return new LlmResponse("{\"purpose\":\"保住线人不暴露\",\"entryState\":\"官差封锁渡口\","
                        + "\"actors\":\"江砚(POV)、沈昭\",\"actorsKnow\":\"江砚知信中名单；沈昭不知江砚身份\","
                        + "\"conflictGoals\":\"江砚要毁证保人；沈昭要拿到信救兄\","
                        + "\"cost\":\"江砚无法自证清白\",\"expectedChange\":\"密信消失，信任出现裂痕\","
                        + "\"readerGains\":\"信已毁且不可逆\",\"readerMustNotKnow\":\"线人的真实身份\","
                        + "\"protectedContent\":\"结尾原句保持不变\"}", 200, 400);
            }
            @Override public void streamComplete(LlmRequest req, com.writing.novel.llm.client.StreamHandler h) {}
        };
        var agent = new SceneContractAgent(gateway,
                com.writing.novel.agent.TestPromptHelper.create());
        var service = new SceneContractService(repo, agent);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                        new com.writing.novel.web.SceneContractController(repo, service))
                .setControllerAdvice(new com.writing.novel.web.GlobalExceptionHandler())
                .build();

        mvc.perform(post("/api/projects/" + projectId + "/outline/scene-contract?chapter=8"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chapterIndex").value(8))
                .andExpect(jsonPath("$.generated").value(true))
                .andExpect(jsonPath("$.contract.purpose").value("保住线人不暴露"))
                .andExpect(jsonPath("$.contract.protectedContent").value("结尾原句保持不变"));

        // 落库：大纲回读含合同
        var reloaded = repo.findOutlineByProject(projectId).orElseThrow()
                .getVolumes().get(0).getSynopses().get(0);
        assertNotNull(reloaded.getSceneContract());
        assertTrue(reloaded.getSceneContract().contains("保住线人不暴露"));

        // GET 端点读回
        mvc.perform(get("/api/projects/" + projectId + "/outline/scene-contract?chapter=8"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contract.cost").value("江砚无法自证清白"));
    }

    @Test
    void 坏输出不落库_明确失败() throws Exception {
        LlmGateway gateway = new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest req) {
                return new LlmResponse("###ERR 截断", 5, 5);
            }
            @Override public void streamComplete(LlmRequest req, com.writing.novel.llm.client.StreamHandler h) {}
        };
        var service = new SceneContractService(repo,
                new SceneContractAgent(gateway, com.writing.novel.agent.TestPromptHelper.create()));
        var result = service.generateForChapter(projectId, 8);
        assertFalse(result.generated(), "坏输出不产生合同");
        assertNull(repo.findOutlineByProject(projectId).orElseThrow()
                .getVolumes().get(0).getSynopses().get(0).getSceneContract(), "落库不变");
    }
}
