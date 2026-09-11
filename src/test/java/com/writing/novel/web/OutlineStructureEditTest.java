package com.writing.novel.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.domain.ChapterSynopsis;
import com.writing.novel.domain.Outline;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.StoryFact;
import com.writing.novel.domain.Volume;
import com.writing.novel.memory.MemoryRevision;
import com.writing.novel.orchestrator.NovelOrchestrator;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.service.CanonPatchService;
import com.writing.novel.service.OutlineStructureService;
import com.writing.novel.service.WritingTaskExecutor;
import com.writing.novel.spi.NovelRepository;
import com.writing.novel.spi.StoryCanon;
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
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    @Autowired StoryCanon storyCanon;
    @Autowired ConnectionProvider connectionProvider;

    @BeforeAll
    static void createDataDir() {
        new File("./data").mkdirs();
    }

    @Test
    void replacesUnwrittenStructureAndReloadsAddedDeletedAndMovedItems() throws Exception {
        String projectId = seedPartialOutline(1);
        Outline before = repository.findOutlineByProject(projectId).orElseThrow();
        Volume writtenVolumeBefore = before.getVolumes().get(0);
        ChapterSynopsis writtenBefore = findChapter(before, 1);
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
        assertEquals(writtenVolumeBefore.getId(), reloaded.getVolumes().get(0).getId(),
                "已写卷必须保留持久化身份");
        ChapterSynopsis writtenReloaded = findChapter(reloaded, 1);
        assertEquals(writtenBefore.getId(), writtenReloaded.getId(), "已写章梗概必须保留持久化身份");
        assertEquals(writtenBefore.getWritingNotes(), writtenReloaded.getWritingNotes());
        assertEquals(writtenBefore.getPlotPurpose(), writtenReloaded.getPlotPurpose());
        assertEquals(writtenBefore.getTensionLevel(), writtenReloaded.getTensionLevel());
        assertEquals(writtenBefore.getVolumeArcPosition(), writtenReloaded.getVolumeArcPosition());
        assertEquals(writtenBefore.getCast(), writtenReloaded.getCast());
        assertEquals(writtenBefore.getBeats(), writtenReloaded.getBeats());
        assertEquals(writtenBefore.getClimax(), writtenReloaded.getClimax());
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

    @Test
    void outlineEditOwnsProjectAcrossReadValidateAndSaveSoWriterConflicts() throws Exception {
        String projectId = "outline-owner-writer";
        NovelRepository localRepository = mock(NovelRepository.class);
        when(localRepository.findAllProjects()).thenReturn(List.of());
        Project project = new Project("owner", "concept", "tag");
        Outline existing = outline(projectId, "总纲",
                volume(projectId, 1, "卷一", "摘要", synopsis(projectId, 1, "第一章", "内容")));
        CountDownLatch readEntered = new CountDownLatch(1);
        CountDownLatch allowRead = new CountDownLatch(1);
        when(localRepository.findOutlineByProject(projectId)).thenAnswer(invocation -> {
            readEntered.countDown();
            assertTrue(allowRead.await(5, TimeUnit.SECONDS));
            return java.util.Optional.of(existing);
        });
        when(localRepository.findProject(projectId)).thenReturn(java.util.Optional.of(project));
        WritingTaskExecutor executor = new WritingTaskExecutor(mock(NovelOrchestrator.class), localRepository);
        CanonPatchService localCanonPatch = mock(CanonPatchService.class);
        when(localCanonPatch.replaceOutline(eq(projectId), any(Outline.class), eq(0)))
                .thenAnswer(invocation -> new CanonPatchService.PatchResult(
                        invocation.getArgument(1), List.of(), true));
        OutlineStructureService service = new OutlineStructureService(localRepository, localCanonPatch, executor);
        Outline incoming = outline(projectId, "总纲",
                volume(projectId, 1, "卷一", "摘要", synopsis(projectId, 1, "第一章", "内容")));

        CompletableFuture<Outline> first = CompletableFuture.supplyAsync(
                () -> service.replaceUnwritten(projectId, incoming));
        assertTrue(readEntered.await(5, TimeUnit.SECONDS));
        try {
            assertThrows(IllegalStateException.class,
                    () -> executor.beginWork(projectId, "concurrent-writer"));
        } finally {
            executor.endWork(projectId, "concurrent-writer");
            allowRead.countDown();
        }
        first.join();

        executor.beginWork(projectId, "writer-after-edit");
        executor.endWork(projectId, "writer-after-edit");
    }

    @Test
    void secondOutlinePutConflictsWhileFirstOwnsProjectAndOwnerIsReleased() throws Exception {
        String projectId = "outline-owner-put";
        NovelRepository localRepository = mock(NovelRepository.class);
        when(localRepository.findAllProjects()).thenReturn(List.of());
        Project project = new Project("owner", "concept", "tag");
        Outline existing = outline(projectId, "总纲",
                volume(projectId, 1, "卷一", "摘要", synopsis(projectId, 1, "第一章", "内容")));
        CountDownLatch readEntered = new CountDownLatch(1);
        CountDownLatch allowRead = new CountDownLatch(1);
        when(localRepository.findOutlineByProject(projectId)).thenAnswer(invocation -> {
            readEntered.countDown();
            assertTrue(allowRead.await(5, TimeUnit.SECONDS));
            return java.util.Optional.of(existing);
        });
        when(localRepository.findProject(projectId)).thenReturn(java.util.Optional.of(project));
        WritingTaskExecutor executor = new WritingTaskExecutor(mock(NovelOrchestrator.class), localRepository);
        CanonPatchService localCanonPatch = mock(CanonPatchService.class);
        when(localCanonPatch.replaceOutline(eq(projectId), any(Outline.class), eq(0)))
                .thenAnswer(invocation -> new CanonPatchService.PatchResult(
                        invocation.getArgument(1), List.of(), true));
        OutlineStructureService service = new OutlineStructureService(localRepository, localCanonPatch, executor);
        ContentController controller = new ContentController(localRepository, null, null, executor, service,
                new com.writing.novel.service.PlanEditionService(localRepository));
        Outline incoming = outline(projectId, "总纲",
                volume(projectId, 1, "卷一", "摘要", synopsis(projectId, 1, "第一章", "内容")));

        CompletableFuture<Outline> first = CompletableFuture.supplyAsync(
                () -> controller.updateOutline(projectId, incoming));
        assertTrue(readEntered.await(5, TimeUnit.SECONDS));
        try {
            assertThrows(IllegalStateException.class,
                    () -> controller.updateOutline(projectId, incoming));
        } finally {
            allowRead.countDown();
        }
        first.join();

        verify(localRepository, times(1)).findOutlineByProject(projectId);
        executor.beginWork(projectId, "work-after-put");
        executor.endWork(projectId, "work-after-put");
    }

    @Test
    void outlineOwnerIsReleasedWhenStructureServiceFails() {
        String projectId = "outline-owner-failure";
        NovelRepository localRepository = mock(NovelRepository.class);
        when(localRepository.findAllProjects()).thenReturn(List.of());
        WritingTaskExecutor executor = new WritingTaskExecutor(mock(NovelOrchestrator.class), localRepository);
        Outline incoming = outline(projectId, "总纲",
                volume(projectId, 1, "卷一", "摘要", synopsis(projectId, 1, "第一章", "内容")));
        when(localRepository.findOutlineByProject(projectId))
                .thenThrow(new IllegalArgumentException("失败"));
        OutlineStructureService failing = new OutlineStructureService(
                localRepository, mock(CanonPatchService.class), executor);

        assertThrows(IllegalArgumentException.class, () -> failing.replaceUnwritten(projectId, incoming));

        executor.beginWork(projectId, "work-after-failure");
        executor.endWork(projectId, "work-after-failure");
    }

    @Test
    void canonFailureRollsBackOutlineFactsAndMemoryRevision() throws Exception {
        String projectId = seedPartialOutline(1);
        StoryFact originalFact = storyCanon.applyHumanPatch(projectId,
                StoryFact.FactType.PLOT_COMMITMENT, "总纲", "原始总纲", 1, 5);
        String outlineBefore = persistedJson(projectId);
        List<String> factsBefore = factSnapshot(projectId);
        long revisionBefore = memoryRevision.current(projectId);
        installPlotFactFailureTrigger(projectId);

        Outline incoming = outline(projectId, "触发失败的新总纲",
                volume(projectId, 1, "卷一已写", "已写卷摘要",
                        synopsis(projectId, 1, "第一章", "已写内容")),
                volume(projectId, 2, "替换第二卷", "新摘要",
                        synopsis(projectId, 2, "第二章新稿", "新未写内容")));

        putOutline(projectId, incoming, 500);

        assertEquals(outlineBefore, persistedJson(projectId));
        assertEquals(factsBefore, factSnapshot(projectId));
        assertEquals(revisionBefore, memoryRevision.current(projectId));
        assertEquals(StoryFact.Status.ACTIVE,
                storyCanon.findById(originalFact.getId()).orElseThrow().getStatus());
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

        ChapterSynopsis written = synopsis(projectId, 1, "第一章", "已写内容");
        written.setWritingNotes("已写节奏要求");
        written.setPlotPurpose("推进既有主线");
        written.setTensionLevel(8);
        written.setVolumeArcPosition("RISING");
        written.setCast("主角,导师");
        written.setBeats("抵达→冲突→抉择");
        written.setClimax("守住承诺");
        Outline original = outline(projectId, "原始总纲",
                volume(projectId, 1, "卷一已写", "已写卷摘要",
                        written,
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

    private List<String> factSnapshot(String projectId) {
        return storyCanon.findAll(projectId).stream()
                .map(fact -> fact.getId() + "|" + fact.getStatus() + "|" + fact.getSupersededBy()
                        + "|" + fact.getAssertion())
                .toList();
    }

    private void installPlotFactFailureTrigger(String projectId) throws Exception {
        String triggerName = "fail_plot_" + projectId.replace("-", "");
        String safeProjectId = projectId.replace("'", "''");
        try (Connection connection = connectionProvider.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TRIGGER " + triggerName
                    + " BEFORE INSERT ON story_facts WHEN NEW.project_id='" + safeProjectId
                    + "' AND NEW.fact_type='PLOT_COMMITMENT' BEGIN SELECT RAISE(ABORT, 'canon failure'); END");
        }
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
