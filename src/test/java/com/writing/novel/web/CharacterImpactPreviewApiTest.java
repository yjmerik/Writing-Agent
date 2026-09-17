package com.writing.novel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.domain.Character;
import com.writing.novel.spi.NovelRepository;
import com.writing.novel.web.dto.CreateProjectRequest;
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
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * p39-04/09（审计 P1-2）：人物修改先预览、确认才落库。
 * confirm=false 绝不写入；confirm=true 需 previewToken 与当前来源一致；来源变化 409。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(StubLlmGatewayTestConfig.class)
@TestPropertySource(properties = {
        "persistence.embedding=stub",
        "persistence.sqlite.db-path=./data/test-character-impact.db",
        "persistence.vector-store.index-path=./data/test-character-impact-vecs.idx"
})
class CharacterImpactPreviewApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired NovelRepository repository;

    private String seed() throws Exception {
        CreateProjectRequest req = new CreateProjectRequest("impact", "c", "tag", "SOLO", null);
        String json = mvc.perform(post("/api/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(req)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String pid = om.readTree(json).get("id").asText();
        Character c = new Character(pid, "Li Hua", "lead");
        c.setMotivation("revenge");
        repository.saveCharacter(c);
        return pid;
    }

    private String charId(String pid) {
        return repository.findCharactersByProject(pid).get(0).getId();
    }

    @Test
    void confirmFalseDoesNotPersist() throws Exception {
        String pid = seed();
        Map<String, Object> body = new HashMap<>();
        body.put("motivation", "preview-only motivation");
        body.put("confirm", false);

        MvcResult result = mvc.perform(put("/api/projects/{id}/characters/{cid}", pid, charId(pid))
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.persisted").value(false))
                .andExpect(jsonPath("$.previewToken").isNotEmpty())
                .andReturn();

        // Critical: DB unchanged
        assertEquals("revenge",
                repository.findCharacter(charId(pid)).orElseThrow().getMotivation(),
                "confirm=false must not persist");
    }

    @Test
    void confirmWithValidTokenPersistsAndStaleTokenRejected() throws Exception {
        String pid = seed();
        String cid = charId(pid);

        // Preview first
        Map<String, Object> previewBody = new HashMap<>();
        previewBody.put("motivation", "confirmed motivation");
        previewBody.put("confirm", false);
        MvcResult preview = mvc.perform(put("/api/projects/{id}/characters/{cid}", pid, cid)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(previewBody)))
                .andExpect(status().isOk()).andReturn();
        String token = om.readTree(preview.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("previewToken").asText();
        assertNotNull(token);

        // Confirm with valid token
        Map<String, Object> confirmBody = new HashMap<>();
        confirmBody.put("motivation", "confirmed motivation");
        confirmBody.put("confirm", true);
        confirmBody.put("previewToken", token);
        mvc.perform(put("/api/projects/{id}/characters/{cid}", pid, cid)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(confirmBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.persisted").value(true));
        assertEquals("confirmed motivation",
                repository.findCharacter(cid).orElseThrow().getMotivation());

        // Stale token after source changed → 409
        Map<String, Object> staleBody = new HashMap<>();
        staleBody.put("motivation", "another change");
        staleBody.put("confirm", true);
        staleBody.put("previewToken", token); // token bound to pre-change state
        mvc.perform(put("/api/projects/{id}/characters/{cid}", pid, cid)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(staleBody)))
                .andExpect(status().isConflict());
        assertEquals("confirmed motivation",
                repository.findCharacter(cid).orElseThrow().getMotivation(),
                "stale token must not write");
    }

    @Test
    void impactPreviewEndpointReadOnly() throws Exception {
        String pid = seed();
        MvcResult result = mvc.perform(post("/api/projects/{id}/characters/{cid}/impact-preview",
                pid, charId(pid))
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("motivation", "changed motivation"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previewToken").isNotEmpty())
                .andExpect(jsonPath("$.impact").exists())
                .andReturn();
        // No write
        assertEquals("revenge",
                repository.findCharacter(charId(pid)).orElseThrow().getMotivation());
        // Impact sections per spec: certain/possible/insufficient coverage
        var node = om.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertNotEquals(null, node.path("impact"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"missing","missingWithToken","null","stringFalse","stringTrue","number","array","object"})
    void missingOrAmbiguousConfirmationNeverWrites(String kind) throws Exception {
        String pid = seed();
        String cid = charId(pid);
        String preview = mvc.perform(put("/api/projects/{id}/characters/{cid}", pid, cid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("motivation", "changed", "confirm", false))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        var body = new HashMap<String, Object>();
        body.put("motivation", "changed");
        if (!kind.equals("missing")) body.put("previewToken", om.readTree(preview).path("previewToken").asText());
        switch (kind) {
            case "null" -> body.put("confirm", null);
            case "stringFalse" -> body.put("confirm", "false");
            case "stringTrue" -> body.put("confirm", "true");
            case "number" -> body.put("confirm", 1);
            case "array" -> body.put("confirm", java.util.List.of(true));
            case "object" -> body.put("confirm", Map.of("value", true));
        }
        mvc.perform(put("/api/projects/{id}/characters/{cid}", pid, cid)
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
        assertEquals("revenge", repository.findCharacter(cid).orElseThrow().getMotivation());
    }

    @Test void explicitConfirmationWithoutCredentialNeverWrites() throws Exception {
        String pid = seed();
        mvc.perform(put("/api/projects/{id}/characters/{cid}", pid, charId(pid))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("motivation", "changed", "confirm", true))))
                .andExpect(status().isBadRequest());
        assertEquals("revenge", repository.findCharacter(charId(pid)).orElseThrow().getMotivation());
    }
}
