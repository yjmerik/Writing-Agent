package com.writing.novel.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.domain.KnowledgeEntry;
import com.writing.novel.domain.StoryFact;
import com.writing.novel.memory.MemoryRevision;
import com.writing.novel.memory.RelevanceGateway;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    @Autowired RelevanceGateway relevanceGateway;

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

    @Test
    void 世界观改名建立跨主体审计链且清空正文归档旧事实() throws Exception {
        String projectId = createProject("世界观改名与清空");
        JsonNode renamedEntry = createWorld(projectId, "RULE", "旧税则", "每十船抽一船");
        StoryFact oldRenameFact = onlyActive(projectId, StoryFact.FactType.WORLD_RULE, "旧税则");

        updateWorld(projectId, renamedEntry.get("id").asText(), "ECONOMY", "新税则", "每二十船抽一船");

        StoryFact newRenameFact = onlyActive(projectId, StoryFact.FactType.WORLD_RULE, "新税则");
        StoryFact auditedOld = canon.findById(oldRenameFact.getId()).orElseThrow();
        assertEquals(StoryFact.Status.SUPERSEDED, auditedOld.getStatus());
        assertEquals(newRenameFact.getId(), auditedOld.getSupersededBy());
        assertFalse(canon.recall(projectId).stream().anyMatch(f -> "旧税则".equals(f.getSubject())));
        assertFalse(relevanceGateway.relevantFacts(projectId, List.of(), 2, 40).stream()
                .anyMatch(f -> "旧税则".equals(f.getSubject())));

        JsonNode clearedEntry = createWorld(projectId, "CULTURE", "乡约", "每月初一议事");
        StoryFact oldClearFact = onlyActive(projectId, StoryFact.FactType.WORLD_RULE, "乡约");

        updateWorld(projectId, clearedEntry.get("id").asText(), "CULTURE", "乡约", "");

        assertFalse(canon.recall(projectId).stream().anyMatch(f -> "乡约".equals(f.getSubject())));
        assertEquals(StoryFact.Status.RETIRED,
                canon.findById(oldClearFact.getId()).orElseThrow().getStatus());
    }

    @Test
    void 人物改名建立两类跨主体审计链且清空状态与关系归档旧事实() throws Exception {
        String projectId = createProject("人物改名与清空");
        JsonNode character = createCharacter(projectId, characterFields(
                "旧名", "主角", "谨慎", "识账", "查清亏空", "左眉浅疤", "与顾清舟结盟", "学会担当"));
        updateCharacter(projectId, character.get("id").asText(), characterFields(
                "旧名", "主角", "谨慎", "识账", "查清亏空", "左眉浅疤", "与顾清舟结盟", "学会担当"));
        StoryFact oldState = onlyActive(projectId, StoryFact.FactType.CHARACTER_STATE, "旧名");
        StoryFact oldRelationship = onlyActive(projectId, StoryFact.FactType.RELATIONSHIP_STATE, "旧名");

        updateCharacter(projectId, character.get("id").asText(), characterFields(
                "沈砚", "主角", "谨慎而执拗", "辨认暗账", "查清漕粮亏空", "左眉有浅疤", "与顾清舟互为盟友", "从自保走向担当"));

        StoryFact newState = onlyActive(projectId, StoryFact.FactType.CHARACTER_STATE, "沈砚");
        StoryFact newRelationship = onlyActive(projectId, StoryFact.FactType.RELATIONSHIP_STATE, "沈砚");
        StoryFact auditedOldState = canon.findById(oldState.getId()).orElseThrow();
        StoryFact auditedOldRelationship = canon.findById(oldRelationship.getId()).orElseThrow();
        assertEquals(StoryFact.Status.SUPERSEDED, auditedOldState.getStatus());
        assertEquals(newState.getId(), auditedOldState.getSupersededBy());
        assertEquals(StoryFact.Status.SUPERSEDED, auditedOldRelationship.getStatus());
        assertEquals(newRelationship.getId(), auditedOldRelationship.getSupersededBy());
        assertFalse(canon.recall(projectId).stream().anyMatch(f -> "旧名".equals(f.getSubject())));
        assertFalse(relevanceGateway.relevantFacts(projectId, List.of(), 2, 40).stream()
                .anyMatch(f -> "旧名".equals(f.getSubject())));

        updateCharacter(projectId, character.get("id").asText(), characterFields(
                "沈砚", "", "", "", "", "", "", ""));

        assertFalse(canon.recall(projectId).stream().anyMatch(f -> "沈砚".equals(f.getSubject())));
        assertEquals(StoryFact.Status.RETIRED, canon.findById(newState.getId()).orElseThrow().getStatus());
        assertEquals(StoryFact.Status.RETIRED,
                canon.findById(newRelationship.getId()).orElseThrow().getStatus());
    }

    @Test
    void 人物整卡与局部保存均以更新后完整实体生成单条状态事实() throws Exception {
        String projectId = createProject("人物聚合事实");
        JsonNode character = createCharacter(projectId, characterFields(
                "沈砚", "主角", "谨慎", "识字", "守家", "青衣", "与顾清舟结盟", "学会担当"));
        Map<String, String> fullPatch = characterFields(
                "沈砚", "主角", "谨慎而执拗", "辨认暗账", "查清漕粮亏空", "左眉有浅疤", "与顾清舟互为盟友", "从自保走向担当");

        updateCharacter(projectId, character.get("id").asText(), fullPatch);
        assertCompleteCharacterFacts(projectId, "辨认暗账", "查清漕粮亏空", "从自保走向担当");

        updateCharacter(projectId, character.get("id").asText(), fullPatch);
        assertCompleteCharacterFacts(projectId, "辨认暗账", "查清漕粮亏空", "从自保走向担当");

        updateCharacter(projectId, character.get("id").asText(), Map.of("ability", "识破伪账"));
        assertCompleteCharacterFacts(projectId, "识破伪账", "查清漕粮亏空", "从自保走向担当");
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

    private void updateWorld(String projectId, String entryId, String type, String name, String content)
            throws Exception {
        mvc.perform(put("/api/projects/{id}/worldbuilding/{entryId}", projectId, entryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "type", type, "name", name, "content", content))))
                .andExpect(status().isOk());
    }

    private void updateCharacter(String projectId, String characterId, Map<String, String> fields)
            throws Exception {
        mvc.perform(put("/api/projects/{id}/characters/{charId}", projectId, characterId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(fields)))
                .andExpect(status().isOk());
    }

    private Map<String, String> characterFields(String name, String role, String personality, String ability,
                                                 String motivation, String appearance, String relationships,
                                                 String growthArc) {
        return Map.of(
                "name", name, "role", role, "personality", personality, "ability", ability,
                "motivation", motivation, "appearance", appearance,
                "relationships", relationships, "growthArc", growthArc);
    }

    private StoryFact onlyActive(String projectId, StoryFact.FactType type, String subject) {
        List<StoryFact> matching = canon.recall(projectId).stream()
                .filter(f -> f.getFactType() == type && subject.equals(f.getSubject()))
                .toList();
        assertEquals(1, matching.size(), type + "/" + subject + " 应恰好一条 ACTIVE");
        return matching.get(0);
    }

    private void assertCompleteCharacterFacts(String projectId, String ability, String motivation,
                                              String growthArc) {
        StoryFact state = onlyActive(projectId, StoryFact.FactType.CHARACTER_STATE, "沈砚");
        assertTrue(state.getAssertion().contains("角色：主角"));
        assertTrue(state.getAssertion().contains("性格：谨慎而执拗"));
        assertTrue(state.getAssertion().contains("能力/境界：" + ability));
        assertTrue(state.getAssertion().contains("动机：" + motivation));
        assertTrue(state.getAssertion().contains("外貌：左眉有浅疤"));
        assertTrue(state.getAssertion().contains("成长弧：" + growthArc));
        StoryFact relationship = onlyActive(projectId, StoryFact.FactType.RELATIONSHIP_STATE, "沈砚");
        assertTrue(relationship.getAssertion().contains("与顾清舟互为盟友"));
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
