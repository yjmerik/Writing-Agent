package com.writing.novel.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.writing.novel.agent.ContentRevisionAgent;
import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.ChapterSynopsis;
import com.writing.novel.domain.Character;
import com.writing.novel.domain.KnowledgeEntry;
import com.writing.novel.domain.Outline;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.Volume;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.service.WritingTaskExecutor;
import com.writing.novel.spi.NovelRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(AgentRevisionApiTest.RevisionGatewayConfig.class)
@TestPropertySource(properties = {
        "persistence.embedding=stub",
        "persistence.sqlite.db-path=./data/test-agent-revision-api.db",
        "persistence.vector-store.index-path=./data/test-agent-revision-api-vecs.idx"
})
class AgentRevisionApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired NovelRepository repository;
    @Autowired WritingTaskExecutor writingTaskExecutor;

    @BeforeAll
    static void createDataDir() {
        new File("./data").mkdirs();
    }

    @Test
    void 五类目标预览零写入且应用只修改选中对象() throws Exception {
        Seed worldSeed = seed("世界观修订");
        KnowledgeEntry untouchedWorld = new KnowledgeEntry(worldSeed.projectId(), "HISTORY", "旧史", "不应改变");
        repository.saveKnowledgeEntry(untouchedWorld);
        JsonNode worldPreview = preview(worldSeed.projectId(), "WORLD", worldSeed.world().getId(), null, null);
        assertEquals("原世界", findWorld(worldSeed.world().getId()).getName(), "预览不得落库");
        apply(worldSeed.projectId(), worldPreview.path("previewId").asText(), 200);
        assertEquals("修订后的世界观", findWorld(worldSeed.world().getId()).getName());
        assertEquals("不应改变", findWorld(untouchedWorld.getId()).getContent());

        Seed characterSeed = seed("人物修订");
        Character untouchedCharacter = new Character(characterSeed.projectId(), "未选人物", "配角");
        untouchedCharacter.setAbility("不应改变");
        repository.saveCharacter(untouchedCharacter);
        JsonNode characterPreview = preview(characterSeed.projectId(), "CHARACTER",
                characterSeed.character().getId(), null, null);
        assertEquals("原能力", findCharacter(characterSeed.character().getId()).getAbility());
        apply(characterSeed.projectId(), characterPreview.path("previewId").asText(), 200);
        assertEquals("修订后的能力", findCharacter(characterSeed.character().getId()).getAbility());
        assertEquals("不应改变", findCharacter(untouchedCharacter.getId()).getAbility());

        Seed volumeSeed = seed("卷修订");
        String selectedVolumeId = volume(volumeSeed.projectId(), 1).getId();
        String untouchedVolumeId = volume(volumeSeed.projectId(), 2).getId();
        JsonNode volumePreview = preview(volumeSeed.projectId(), "VOLUME",
                volumeSeed.volume().getId(), null, null);
        assertEquals("原卷", volume(volumeSeed.projectId(), 1).getTitle());
        apply(volumeSeed.projectId(), volumePreview.path("previewId").asText(), 200);
        assertEquals("修订后的卷名", volume(volumeSeed.projectId(), 1).getTitle());
        assertEquals("未选卷", volume(volumeSeed.projectId(), 2).getTitle());
        assertEquals(selectedVolumeId, volume(volumeSeed.projectId(), 1).getId());
        assertEquals(untouchedVolumeId, volume(volumeSeed.projectId(), 2).getId());

        Seed chapterSeed = seed("章节修订");
        JsonNode chapterPreview = preview(chapterSeed.projectId(), "CHAPTER",
                chapterSeed.chapter().getId(), 1, null);
        assertEquals("原章", chapter(chapterSeed.projectId(), 1).getTitle());
        apply(chapterSeed.projectId(), chapterPreview.path("previewId").asText(), 200);
        assertEquals("修订后的章名", chapter(chapterSeed.projectId(), 1).getTitle());
        assertEquals("未选章节正文", chapter(chapterSeed.projectId(), 2).getContent());

        Seed passageSeed = seed("段落修订");
        JsonNode passagePreview = preview(passageSeed.projectId(), "PASSAGE",
                passageSeed.chapter().getId(), 1, 1);
        assertEquals("第一段\n第二段\n第三段", chapter(passageSeed.projectId(), 1).getContent());
        apply(passageSeed.projectId(), passagePreview.path("previewId").asText(), 200);
        assertEquals("第一段\n修订后的第二段\n第三段", chapter(passageSeed.projectId(), 1).getContent());
        assertEquals("未选章节正文", chapter(passageSeed.projectId(), 2).getContent());
    }

    @Test
    void 来源变化后应用返回冲突且预览保持未应用() throws Exception {
        Seed seed = seed("陈旧预览");
        JsonNode preview = preview(seed.projectId(), "WORLD", seed.world().getId(), null, null);

        mvc.perform(put("/api/projects/{id}/worldbuilding/{entryId}", seed.projectId(), seed.world().getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("type", "RULE", "name", "人工新值", "content", "人工修改"))))
                .andExpect(status().isOk());

        String previewId = preview.path("previewId").asText();
        apply(seed.projectId(), previewId, 409);
        apply(seed.projectId(), previewId, 409);
        assertEquals("人工新值", findWorld(seed.world().getId()).getName());
    }

    @Test
    void 预览只能由原项目成功应用一次() throws Exception {
        Seed seed = seed("一次应用");
        Seed other = seed("其他项目");
        JsonNode preview = preview(seed.projectId(), "WORLD", seed.world().getId(), null, null);
        String previewId = preview.path("previewId").asText();

        apply(other.projectId(), previewId, 400);
        assertEquals("原世界", findWorld(seed.world().getId()).getName());
        apply(seed.projectId(), previewId, 200);
        String appliedContent = findWorld(seed.world().getId()).getContent();
        apply(seed.projectId(), previewId, 409);
        assertEquals(appliedContent, findWorld(seed.world().getId()).getContent());
    }

    @Test
    void 活动写作时拒绝应用且释放后同一预览仍可应用() throws Exception {
        Seed seed = seed("工作权冲突");
        JsonNode preview = preview(seed.projectId(), "WORLD", seed.world().getId(), null, null);
        String previewId = preview.path("previewId").asText();

        writingTaskExecutor.beginWork(seed.projectId(), "active-writer");
        try {
            apply(seed.projectId(), previewId, 409);
        } finally {
            writingTaskExecutor.endWork(seed.projectId(), "active-writer");
        }
        assertEquals("原世界", findWorld(seed.world().getId()).getName());

        apply(seed.projectId(), previewId, 200);
        assertEquals("修订后的世界观", findWorld(seed.world().getId()).getName());
    }

    @Test
    void Agent候选篡改目标身份时预览失败且不写入() throws Exception {
        Seed seed = seed("身份保护");
        ObjectNode request = objectMapper.createObjectNode()
                .put("targetType", "WORLD")
                .put("targetId", seed.world().getId())
                .put("instructions", "篡改目标身份");

        mvc.perform(post("/api/projects/{id}/revisions/preview", seed.projectId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request.toString()))
                .andExpect(status().isConflict());

        assertEquals("原世界", findWorld(seed.world().getId()).getName());
        assertEquals("原规则", findWorld(seed.world().getId()).getContent());
    }

    private JsonNode preview(String projectId, String targetType, String targetId,
                             Integer chapterIndex, Integer passageIndex) throws Exception {
        ObjectNode request = objectMapper.createObjectNode()
                .put("targetType", targetType)
                .put("targetId", targetId)
                .put("instructions", "只修改所选内容");
        if (chapterIndex != null) request.put("chapterIndex", chapterIndex);
        if (passageIndex != null) request.put("passageIndex", passageIndex);
        MvcResult result = mvc.perform(post("/api/projects/{id}/revisions/preview", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previewId").isNotEmpty())
                .andExpect(jsonPath("$.sourceVersion").isNotEmpty())
                .andExpect(jsonPath("$.before").exists())
                .andExpect(jsonPath("$.after").exists())
                .andExpect(jsonPath("$.summary").value("仅修改所选目标"))
                .andReturn();
        JsonNode response = objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertNotEquals(response.path("before"), response.path("after"));
        return response;
    }

    private void apply(String projectId, String previewId, int expectedStatus) throws Exception {
        mvc.perform(post("/api/projects/{id}/revisions/{previewId}/apply", projectId, previewId))
                .andExpect(status().is(expectedStatus));
    }

    private Seed seed(String title) {
        Project project = new Project(title, "概念", "现实");
        repository.saveProject(project);

        KnowledgeEntry world = new KnowledgeEntry(project.getId(), "RULE", "原世界", "原规则");
        repository.saveKnowledgeEntry(world);

        Character character = new Character(project.getId(), "原人物", "主角");
        character.setAbility("原能力");
        repository.saveCharacter(character);

        Outline outline = new Outline(project.getId(), "总纲");
        Volume volume = new Volume(project.getId(), 1, "原卷", "原卷摘要");
        volume.addSynopsis(new ChapterSynopsis(project.getId(), 1, "原章", "原章梗概"));
        outline.addVolume(volume);
        Volume untouchedVolume = new Volume(project.getId(), 2, "未选卷", "不应改变");
        untouchedVolume.addSynopsis(new ChapterSynopsis(project.getId(), 2, "未选章", "不应改变"));
        outline.addVolume(untouchedVolume);
        repository.saveOutline(outline);
        Outline savedOutline = repository.findOutlineByProject(project.getId()).orElseThrow();

        Chapter chapter = new Chapter(project.getId(), 1, "原章");
        chapter.setContent("第一段\n第二段\n第三段");
        chapter.setStatus(Chapter.Status.DONE);
        repository.saveChapter(chapter);
        Chapter untouchedChapter = new Chapter(project.getId(), 2, "未选章");
        untouchedChapter.setContent("未选章节正文");
        untouchedChapter.setStatus(Chapter.Status.DONE);
        repository.saveChapter(untouchedChapter);

        return new Seed(project.getId(), world, character, savedOutline.getVolumes().get(0), chapter);
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private KnowledgeEntry findWorld(String id) {
        return repository.findKnowledgeEntriesByProject(findProjectOfWorld(id)).stream()
                .filter(entry -> id.equals(entry.getId())).findFirst().orElseThrow();
    }

    private String findProjectOfWorld(String id) {
        return repository.findAllProjects().stream()
                .map(Project::getId)
                .filter(projectId -> repository.findKnowledgeEntriesByProject(projectId).stream()
                        .anyMatch(entry -> id.equals(entry.getId())))
                .findFirst().orElseThrow();
    }

    private Character findCharacter(String id) {
        return repository.findAllProjects().stream()
                .flatMap(project -> repository.findCharactersByProject(project.getId()).stream())
                .filter(character -> id.equals(character.getId())).findFirst().orElseThrow();
    }

    private Volume volume(String projectId, int volumeIndex) {
        return repository.findOutlineByProject(projectId).orElseThrow().getVolumes().stream()
                .filter(value -> value.getVolumeIndex() == volumeIndex).findFirst().orElseThrow();
    }

    private Chapter chapter(String projectId, int chapterIndex) {
        return repository.findChapterByIndex(projectId, chapterIndex).orElseThrow();
    }

    private record Seed(String projectId, KnowledgeEntry world, Character character,
                        Volume volume, Chapter chapter) {}

    @TestConfiguration
    static class RevisionGatewayConfig {
        @Bean
        @Primary
        LlmGateway revisionGateway(ObjectMapper objectMapper) {
            return new LlmGateway() {
                @Override
                public LlmResponse complete(LlmRequest request) {
                    try {
                        String prompt = request.getSystemPrompt();
                        if (!prompt.contains("目标类型：")) {
                            return new LlmResponse("[]", 10, 10);
                        }
                        String typeName = between(prompt, "目标类型：", "\n").trim();
                        ContentRevisionAgent.TargetType type = ContentRevisionAgent.TargetType.valueOf(typeName);
                        String currentJson = between(prompt, "当前选中对象（JSON）：\n", "\n\n用户修改要求：");
                        ObjectNode candidate = (ObjectNode) objectMapper.readTree(currentJson);
                        String title;
                        String content;
                        switch (type) {
                            case WORLD -> {
                                candidate.put("name", "修订后的世界观");
                                candidate.put("content", "修订后的规则");
                                title = candidate.path("name").asText();
                                content = candidate.path("content").asText();
                            }
                            case CHARACTER -> {
                                candidate.put("ability", "修订后的能力");
                                title = candidate.path("name").asText();
                                content = candidate.path("ability").asText();
                            }
                            case VOLUME -> {
                                candidate.put("title", "修订后的卷名");
                                candidate.put("summary", "修订后的卷摘要");
                                title = candidate.path("title").asText();
                                content = candidate.path("summary").asText();
                            }
                            case CHAPTER -> {
                                candidate.put("title", "修订后的章名");
                                candidate.put("content", "修订后的章节正文");
                                title = candidate.path("title").asText();
                                content = candidate.path("content").asText();
                            }
                            case PASSAGE -> {
                                candidate.put("content", "修订后的第二段");
                                title = "第2段";
                                content = candidate.path("content").asText();
                            }
                            default -> throw new IllegalArgumentException("unsupported target");
                        }
                        if (prompt.contains("篡改目标身份")) candidate.put("id", "other-target");
                        ObjectNode response = objectMapper.createObjectNode()
                                .put("targetType", type.name())
                                .put("title", title)
                                .put("content", content)
                                .put("structuredJson", candidate.toString())
                                .put("summary", "仅修改所选目标");
                        return new LlmResponse(response.toString(), 50, 100);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }

                @Override
                public void streamComplete(LlmRequest request, StreamHandler handler) {
                }
            };
        }

        private static String between(String value, String start, String end) {
            int from = value.indexOf(start);
            if (from < 0) throw new IllegalArgumentException("missing marker: " + start);
            from += start.length();
            int to = value.indexOf(end, from);
            if (to < 0) throw new IllegalArgumentException("missing marker: " + end);
            return value.substring(from, to);
        }
    }
}
