package com.writing.novel.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.domain.KnowledgeEntry;
import com.writing.novel.memory.MemoryRevision;
import com.writing.novel.spi.NovelRepository;
import com.writing.novel.spi.StoryCanon;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(StubLlmGatewayTestConfig.class)
@TestPropertySource(properties = {
        "persistence.embedding=stub",
        "persistence.sqlite.db-path=./data/test-world-character-full-edit.db",
        "persistence.vector-store.index-path=./data/test-world-character-full-edit-vecs.idx"
})
class WorldCharacterFullEditTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired NovelRepository repository;
    @Autowired StoryCanon canon;
    @Autowired MemoryRevision memoryRevision;

    @BeforeAll
    static void createDataDir() {
        new File("./data").mkdirs();
    }

    @Test
    void 世界观和人物完整字段更新后通过查询接口精确重读() throws Exception {
        String projectId = createProject("完整字段更新");
        JsonNode world = createWorld(projectId, "GEOGRAPHY", "旧城", "旧城墙");
        JsonNode character = createCharacter(projectId, Map.of(
                "name", "旧名",
                "role", "配角",
                "personality", "寡言",
                "ability", "识字",
                "motivation", "守家",
                "appearance", "青衣",
                "relationships", "独居",
                "growthArc", "退隐"));

        Map<String, String> worldPatch = Map.of(
                "type", "ECONOMY",
                "name", "漕运税则",
                "content", "每十船抽一船作为官税");
        mvc.perform(put("/api/projects/{id}/worldbuilding/{entryId}", projectId, world.get("id").asText())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(worldPatch)))
                .andExpect(status().isOk());

        Map<String, String> characterPatch = Map.of(
                "name", "沈砚",
                "role", "主角",
                "personality", "谨慎而执拗",
                "ability", "辨认暗账",
                "motivation", "查清漕粮亏空",
                "appearance", "左眉有浅疤",
                "relationships", "与顾清舟互为盟友",
                "growthArc", "从自保走向担当");
        mvc.perform(put("/api/projects/{id}/characters/{charId}", projectId, character.get("id").asText())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(characterPatch)))
                .andExpect(status().isOk());

        JsonNode reloadedWorld = findById(getArray("/api/projects/" + projectId + "/worldbuilding"),
                world.get("id").asText());
        assertEquals("ECONOMY", reloadedWorld.get("type").asText());
        assertEquals("漕运税则", reloadedWorld.get("name").asText());
        assertEquals("每十船抽一船作为官税", reloadedWorld.get("content").asText());

        JsonNode reloadedCharacter = findById(getArray("/api/projects/" + projectId + "/characters"),
                character.get("id").asText());
        characterPatch.forEach((field, expected) -> assertEquals(expected,
                reloadedCharacter.get(field).asText(), field));
    }

    @Test
    void 合法世界观类型矩阵可持久化且旧ITEM与未知类型被拒绝() throws Exception {
        String projectId = createProject("世界观类型矩阵");
        List<String> allowed = List.of(
                "GEOGRAPHY", "POWER_SYSTEM", "FACTION", "HISTORY", "RULE",
                "CULTURE", "ECONOMY", "RACE", "CUSTOM");

        for (String type : allowed) {
            createWorld(projectId, type, type + "名称", type + "内容");
        }
        JsonNode entries = getArray("/api/projects/" + projectId + "/worldbuilding");
        for (String type : allowed) {
            assertTrue(containsWorld(entries, type, type + "名称", type + "内容"), type);
        }

        for (String rejected : List.of("ITEM", "INVALID")) {
            mvc.perform(post("/api/projects/{id}/worldbuilding", projectId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "type", rejected, "name", rejected + "名称", "content", rejected + "内容"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("无效的世界观类型"));
        }
    }

    @Test
    void 非法类型更新返回400且实体真典和记忆版本均无副作用() throws Exception {
        String projectId = createProject("非法类型无副作用");
        JsonNode created = createWorld(projectId, "RULE", "宵禁", "三更后闭坊");
        String entryId = created.get("id").asText();
        KnowledgeEntry before = repository.findKnowledgeEntry(entryId).orElseThrow();
        List<String> factsBefore = factSnapshot(projectId);
        long revisionBefore = memoryRevision.current(projectId);

        mvc.perform(put("/api/projects/{id}/worldbuilding/{entryId}", projectId, entryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "type", "INVALID",
                                "name", "被污染的名称",
                                "content", "被污染的正文"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("无效的世界观类型"));

        KnowledgeEntry after = repository.findKnowledgeEntry(entryId).orElseThrow();
        assertEquals(before.getType(), after.getType());
        assertEquals(before.getName(), after.getName());
        assertEquals(before.getContent(), after.getContent());
        assertEquals(before.getVersion(), after.getVersion());
        assertEquals(factsBefore, factSnapshot(projectId));
        assertEquals(revisionBefore, memoryRevision.current(projectId));
    }

    @Test
    void 不能借其他项目路径修改世界观或人物且两个项目均无副作用() throws Exception {
        String ownerProject = createProject("内容所有者");
        String attackerProject = createProject("错误路径项目");
        JsonNode world = createWorld(ownerProject, "CULTURE", "乡约", "每月初一议事");
        JsonNode character = createCharacter(ownerProject, Map.of(
                "name", "顾清舟", "role", "配角", "personality", "爽直", "ability", "验粮",
                "motivation", "护住乡民", "appearance", "短打", "relationships", "沈砚盟友", "growthArc", "学会取舍"));
        List<String> ownerFactsBefore = factSnapshot(ownerProject);
        List<String> attackerFactsBefore = factSnapshot(attackerProject);
        long ownerRevisionBefore = memoryRevision.current(ownerProject);
        long attackerRevisionBefore = memoryRevision.current(attackerProject);

        mvc.perform(put("/api/projects/{id}/worldbuilding/{entryId}", attackerProject, world.get("id").asText())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "type", "RACE", "name", "越权世界", "content", "越权正文"))))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/projects/{id}/characters/{charId}", attackerProject, character.get("id").asText())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "越权人物", "role", "反派", "personality", "越权性格",
                                "ability", "越权能力", "motivation", "越权动机", "appearance", "越权外貌",
                                "relationships", "越权关系", "growthArc", "越权成长"))))
                .andExpect(status().isBadRequest());

        JsonNode worldAfter = findById(getArray("/api/projects/" + ownerProject + "/worldbuilding"),
                world.get("id").asText());
        assertEquals("CULTURE", worldAfter.get("type").asText());
        assertEquals("乡约", worldAfter.get("name").asText());
        assertEquals("每月初一议事", worldAfter.get("content").asText());
        JsonNode characterAfter = findById(getArray("/api/projects/" + ownerProject + "/characters"),
                character.get("id").asText());
        assertEquals("顾清舟", characterAfter.get("name").asText());
        assertEquals("配角", characterAfter.get("role").asText());
        assertEquals(ownerFactsBefore, factSnapshot(ownerProject));
        assertEquals(attackerFactsBefore, factSnapshot(attackerProject));
        assertEquals(ownerRevisionBefore, memoryRevision.current(ownerProject));
        assertEquals(attackerRevisionBefore, memoryRevision.current(attackerProject));
    }

    private String createProject(String title) throws Exception {
        CreateProjectRequest request = new CreateProjectRequest(title, "确定性概念", "mystery", "SOLO", null);
        String json = mvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(json).get("id").asText();
    }

    private JsonNode createWorld(String projectId, String type, String name, String content) throws Exception {
        String json = mvc.perform(post("/api/projects/{id}/worldbuilding", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "type", type, "name", name, "content", content))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(json);
    }

    private JsonNode createCharacter(String projectId, Map<String, String> fields) throws Exception {
        String json = mvc.perform(post("/api/projects/{id}/characters", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(fields)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(json);
    }

    private JsonNode getArray(String path) throws Exception {
        String json = mvc.perform(get(path))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(json);
    }

    private JsonNode findById(JsonNode array, String id) {
        for (JsonNode node : array) {
            if (id.equals(node.get("id").asText())) return node;
        }
        throw new AssertionError("没有找到实体: " + id);
    }

    private boolean containsWorld(JsonNode entries, String type, String name, String content) {
        for (JsonNode entry : entries) {
            if (type.equals(entry.get("type").asText())
                    && name.equals(entry.get("name").asText())
                    && content.equals(entry.get("content").asText())) return true;
        }
        return false;
    }

    private List<String> factSnapshot(String projectId) {
        return canon.recall(projectId).stream()
                .map(fact -> String.join("|", fact.getId(), fact.getFactType().name(), fact.getSubject(),
                        fact.getAssertion(), fact.getStatus().name(), String.valueOf(fact.getImportance())))
                .sorted()
                .toList();
    }
}
