package com.writing.novel.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.domain.ChapterSynopsis;
import com.writing.novel.domain.Outline;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.Volume;
import com.writing.novel.memory.MemoryRevision;
import com.writing.novel.service.CanonPatchService;
import com.writing.novel.service.OutlineStructureService;
import com.writing.novel.spi.NovelRepository;
import com.writing.novel.web.dto.CreateProjectRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.File;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@AutoConfigureMockMvc
@Import(StubLlmGatewayTestConfig.class)
@TestPropertySource(properties = {
        "persistence.embedding=stub",
        "persistence.sqlite.db-path=./data/test-outline-structure-edit.db",
        "persistence.vector-store.index-path=./data/test-outline-structure-edit-vecs.idx"
})
class OutlineStructureEditTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired NovelRepository repository;
    @Autowired MemoryRevision memoryRevision;
    @Autowired CanonPatchService canonPatch;

    @BeforeAll
    static void createDataDir() {
        new File("./data").mkdirs();
    }

    @Test
    void replacesUnwrittenStructureAndReloadsAddedDeletedAndMovedItems() throws Exception {
        String projectId = seedPartialOutline(1);
        Outline incoming = outline(projectId, "更新后的总纲",
                volume(projectId, 1, "卷一已写", "已写卷摘要",
                        synopsis(projectId, 1, "第一章", "已写内容")),
                volume(projectId, 2, "重排后的第二卷", "第二卷新摘要",
                        synopsis(projectId, 2, "移入第二卷", "移动后的第二章"),
                        synopsis(projectId, 4, "新增第四章", "第四章内容")),
                volume(projectId, 3, "新增空卷", "待规划"),
                volume(projectId, 4, "新增第四卷", "第四卷摘要",
                        synopsis(projectId, 5, "新增第五章", "第五章内容")));

        putOutline(projectId, incoming, 200);

        Outline reloaded = repository.findOutlineByProject(projectId).orElseThrow();
        assertEquals("更新后的总纲", reloaded.getPremise());
        assertEquals(List.of(1, 2, 3, 4), reloaded.getVolumes().stream()
                .map(Volume::getVolumeIndex).toList());
        assertEquals(List.of(1, 2, 4, 5), chapterIndices(reloaded));
        assertEquals("第一章", findChapter(reloaded, 1).getTitle());
        assertEquals("已写内容", findChapter(reloaded, 1).getSynopsis());
        assertEquals(2, volumeOf(reloaded, 2).getVolumeIndex(), "未写章应可跨卷移动");
        assertFalse(chapterIndices(reloaded).contains(3), "缺失的未写章应被删除");
        assertTrue(reloaded.getVolumes().stream()
                .filter(v -> v.getVolumeIndex() == 3)
                .findFirst().orElseThrow().getSynopses().isEmpty(), "空卷应可持久化");
    }

    @Test
    void structuralReplacementWithUnchangedPremiseDoesNotCreatePlotFact() throws Exception {
        String projectId = seedPartialOutline(1);
        int factsBefore = canonPatch.listActive(projectId).size();
        Outline incoming = outline(projectId, "原始总纲",
                volume(projectId, 1, "卷一已写", "已写卷摘要",
                        synopsis(projectId, 1, "第一章", "已写内容")),
                volume(projectId, 2, "新第二卷", "新摘要",
                        synopsis(projectId, 2, "第二章新稿", "新未写内容")));

        putOutline(projectId, incoming, 200);

        assertEquals(factsBefore, canonPatch.listActive(projectId).size());
        assertEquals(List.of(1, 2), chapterIndices(repository.findOutlineByProject(projectId).orElseThrow()));
    }

    @Test
    void successfulValidationCallsCanonReplacementExactlyOnce() {
        NovelRepository localRepository = mock(NovelRepository.class);
        CanonPatchService localCanonPatch = mock(CanonPatchService.class);
        Project project = new Project("single-save", "concept", "tag");
        String projectId = project.getId();
        Outline existing = outline(projectId, "原始总纲",
                volume(projectId, 1, "卷一", "摘要",
                        synopsis(projectId, 1, "第一章", "内容")));
        Outline incoming = outline(projectId, "原始总纲",
                volume(projectId, 1, "新卷一", "新摘要",
                        synopsis(projectId, 2, "第二章", "新内容")));
        when(localRepository.findOutlineByProject(projectId)).thenReturn(java.util.Optional.of(existing));
        when(localRepository.findProject(projectId)).thenReturn(java.util.Optional.of(project));
        when(localCanonPatch.replaceOutline(eq(projectId), any(Outline.class), eq(0)))
                .thenAnswer(invocation -> new CanonPatchService.PatchResult(
                        invocation.getArgument(1), List.of(), true));

        new OutlineStructureService(localRepository, localCanonPatch)
                .replaceUnwritten(projectId, incoming);

        verify(localCanonPatch, times(1)).replaceOutline(eq(projectId), any(Outline.class), eq(0));
    }

    @ParameterizedTest(name = "rejects invalid chapter ordering: {0}")
    @MethodSource("invalidOutlines")
    void rejectsInvalidIndicesBeforeAnySideEffect(String label, OutlineFactory factory) throws Exception {
        String projectId = seedPartialOutline(1);
        String before = persistedJson(projectId);
        long revisionBefore = memoryRevision.current(projectId);
        int factsBefore = canonPatch.listActive(projectId).size();

        putOutline(projectId, factory.create(projectId), 400);

        assertEquals(before, persistedJson(projectId), label + " 不得部分写入大纲");
        assertEquals(revisionBefore, memoryRevision.current(projectId), label + " 不得推进记忆版本");
        assertEquals(factsBefore, canonPatch.listActive(projectId).size(), label + " 不得写入真典");
    }

    @Test
    void rejectsChangedWrittenPrefixBeforeAnySideEffect() throws Exception {
        String projectId = seedPartialOutline(1);
        String before = persistedJson(projectId);
        long revisionBefore = memoryRevision.current(projectId);

        Outline changed = outline(projectId, "不得落库的新总纲",
                volume(projectId, 1, "卷一已写", "已写卷摘要",
                        synopsis(projectId, 1, "篡改第一章", "篡改已写内容")),
                volume(projectId, 2, "第二卷", "新摘要",
                        synopsis(projectId, 2, "第二章", "新内容")));

        putOutline(projectId, changed, 400);

        assertEquals(before, persistedJson(projectId));
        assertEquals(revisionBefore, memoryRevision.current(projectId));
    }

    @Test
    void rejectsMissingOrMovedWrittenChapter() throws Exception {
        String projectId = seedPartialOutline(1);
        String before = persistedJson(projectId);

        Outline moved = outline(projectId, "原始总纲",
                volume(projectId, 1, "卷一已写", "已写卷摘要"),
                volume(projectId, 2, "第二卷", "摘要",
                        synopsis(projectId, 1, "第一章", "已写内容"),
                        synopsis(projectId, 2, "第二章", "新内容")));

        putOutline(projectId, moved, 400);
        assertEquals(before, persistedJson(projectId));
    }

    @Test
    void currentChapterZeroOrNegativeTreatsEveryChapterAsUnwritten() throws Exception {
        for (int boundary : List.of(0, -1)) {
            String projectId = seedPartialOutline(boundary);
            Outline incoming = outline(projectId, "无已写前缀",
                    volume(projectId, 1, "全新卷", "全新摘要",
                            synopsis(projectId, 2, "新的第二章", "新的内容")));

            putOutline(projectId, incoming, 200);

            Outline reloaded = repository.findOutlineByProject(projectId).orElseThrow();
            assertEquals(List.of(2), chapterIndices(reloaded));
            assertEquals("全新卷", reloaded.getVolumes().get(0).getTitle());
        }
    }

    @Test
    void rejectsOutlineWithoutAnyChapterBeforeSideEffects() throws Exception {
        String projectId = seedPartialOutline(0);
        String before = persistedJson(projectId);
        long revisionBefore = memoryRevision.current(projectId);
        Outline incoming = outline(projectId, "空大纲不应保存",
                volume(projectId, 1, "空卷", "没有章节"));

        putOutline(projectId, incoming, 400);

        assertEquals(before, persistedJson(projectId));
        assertEquals(revisionBefore, memoryRevision.current(projectId));
    }

    private static Stream<org.junit.jupiter.params.provider.Arguments> invalidOutlines() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("duplicate", (OutlineFactory) projectId ->
                        outline(projectId, "非法重复章号",
                                volume(projectId, 1, "卷一已写", "已写卷摘要",
                                        synopsis(projectId, 1, "第一章", "已写内容"),
                                        synopsis(projectId, 2, "第二章", "内容")),
                                volume(projectId, 2, "卷二", "摘要",
                                        synopsis(projectId, 2, "重复第二章", "重复")))),
                org.junit.jupiter.params.provider.Arguments.of("descending", (OutlineFactory) projectId ->
                        outline(projectId, "非法降序章号",
                                volume(projectId, 1, "卷一已写", "已写卷摘要",
                                        synopsis(projectId, 1, "第一章", "已写内容"),
                                        synopsis(projectId, 3, "第三章", "内容")),
                                volume(projectId, 2, "卷二", "摘要",
                                        synopsis(projectId, 2, "第二章", "内容")))),
                org.junit.jupiter.params.provider.Arguments.of("nonpositive", (OutlineFactory) projectId ->
                        outline(projectId, "非法非正章号",
                                volume(projectId, 1, "卷一已写", "已写卷摘要",
                                        synopsis(projectId, 0, "第零章", "内容"),
                                        synopsis(projectId, 1, "第一章", "已写内容"))))
        );
    }

    private String seedPartialOutline(int currentChapterIndex) throws Exception {
        CreateProjectRequest request = new CreateProjectRequest(
                "outline-structure", "concept", "tag", "SOLO", null);
        String response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String projectId = objectMapper.readTree(response).path("id").asText();

        Outline original = outline(projectId, "原始总纲",
                volume(projectId, 1, "卷一已写", "已写卷摘要",
                        synopsis(projectId, 1, "第一章", "已写内容"),
                        synopsis(projectId, 2, "第二章旧稿", "旧未写内容")),
                volume(projectId, 2, "旧第二卷", "旧第二卷摘要",
                        synopsis(projectId, 3, "第三章旧稿", "待删除内容")));
        repository.saveOutline(original);
        Project project = repository.findProject(projectId).orElseThrow();
        project.setCurrentChapterIndex(currentChapterIndex);
        repository.saveProject(project);
        return projectId;
    }

    private void putOutline(String projectId, Outline outline, int expectedStatus) throws Exception {
        mvc.perform(put("/api/projects/{id}/outline", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(outline)))
                .andExpect(status().is(expectedStatus));
    }

    private String persistedJson(String projectId) throws Exception {
        return objectMapper.writeValueAsString(repository.findOutlineByProject(projectId).orElseThrow());
    }

    private static Outline outline(String projectId, String premise, Volume... volumes) {
        Outline outline = new Outline(projectId, premise);
        for (Volume volume : volumes) outline.addVolume(volume);
        return outline;
    }

    private static Volume volume(String projectId, int index, String title, String summary,
                                 ChapterSynopsis... synopses) {
        Volume volume = new Volume(projectId, index, title, summary);
        for (ChapterSynopsis synopsis : synopses) volume.addSynopsis(synopsis);
        return volume;
    }

    private static ChapterSynopsis synopsis(String projectId, int index, String title, String synopsis) {
        return new ChapterSynopsis(projectId, index, title, synopsis);
    }

    private static List<Integer> chapterIndices(Outline outline) {
        return outline.getVolumes().stream()
                .flatMap(volume -> volume.getSynopses().stream())
                .map(ChapterSynopsis::getChapterIndex)
                .toList();
    }

    private static ChapterSynopsis findChapter(Outline outline, int chapterIndex) {
        ChapterSynopsis result = outline.getVolumes().stream()
                .flatMap(volume -> volume.getSynopses().stream())
                .filter(synopsis -> synopsis.getChapterIndex() == chapterIndex)
                .findFirst().orElse(null);
        assertNotNull(result, "缺少第 " + chapterIndex + " 章");
        return result;
    }

    private static Volume volumeOf(Outline outline, int chapterIndex) {
        return outline.getVolumes().stream()
                .filter(volume -> volume.getSynopses().stream()
                        .anyMatch(synopsis -> synopsis.getChapterIndex() == chapterIndex))
                .findFirst().orElseThrow();
    }

    @FunctionalInterface
    private interface OutlineFactory {
        Outline create(String projectId);
    }
}
