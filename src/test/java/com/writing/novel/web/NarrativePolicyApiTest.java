package com.writing.novel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.domain.GenreProfile;
import com.writing.novel.domain.Project;
import com.writing.novel.spi.NovelRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * NCS-190: 叙事策略 REST 读写回路——GET 展示题材默认+覆盖+生效值；
 * PUT 部分覆盖（校验）；DELETE 清除回到题材默认。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(StubLlmGatewayTestConfig.class)
@TestPropertySource(properties = {
        "persistence.embedding=stub",
        "persistence.sqlite.db-path=./data/test-narrative-policy.db",
        "persistence.vector-store.index-path=./data/test-narrative-policy-vecs.idx"
})
class NarrativePolicyApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired NovelRepository repository;

    private String seedHistoricalProject() {
        Project p = new Project("叙事策略测试", "庙堂权谋", "历史");
        p.setGenre(GenreProfile.HISTORICAL);
        repository.saveProject(p);
        return p.getId();
    }

    @Test
    void 叙事策略REST读写回路() throws Exception {
        String pid = seedHistoricalProject();

        // GET：无覆盖——题材默认与生效值一致
        mvc.perform(get("/api/projects/{id}/narrative-policy", pid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.genre").value("HISTORICAL"))
                .andExpect(jsonPath("$.effective.beatsStandardMax").value(3))
                .andExpect(jsonPath("$.effective.dialogueParagraphRatioMax").value(0.55))
                .andExpect(jsonPath("$.overrides").isEmpty());

        // PUT：部分覆盖
        mvc.perform(put("/api/projects/{id}/narrative-policy", pid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("transitionMinChars", 100))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effective.transitionMinChars").value(100))
                .andExpect(jsonPath("$.effective.beatsStandardMax").value(3));

        // GET：覆盖持久化
        mvc.perform(get("/api/projects/{id}/narrative-policy", pid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overrides.transitionMinChars").value(100));

        // PUT 非法值 → 400
        mvc.perform(put("/api/projects/{id}/narrative-policy", pid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("beatsStandardMin", 9))))
                .andExpect(status().isBadRequest());

        // DELETE：清除覆盖回到默认
        mvc.perform(delete("/api/projects/{id}/narrative-policy", pid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effective.transitionMinChars").value(80))
                .andExpect(jsonPath("$.overrides").isEmpty());
    }

    @Test
    void 项目不存在返回400() throws Exception {
        mvc.perform(get("/api/projects/{id}/narrative-policy", "no-such-project"))
                .andExpect(status().isBadRequest());
    }
}
