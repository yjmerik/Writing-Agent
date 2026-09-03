package com.writing.novel.orchestrator;

import com.writing.novel.agent.PlotterAgent;
import com.writing.novel.agent.TestPromptHelper;
import com.writing.novel.agent.WorldBuilderAgent;
import com.writing.novel.domain.ChapterSynopsis;
import com.writing.novel.domain.Checkpoint;
import com.writing.novel.domain.KnowledgeEntry;
import com.writing.novel.domain.Outline;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.StoryFact;
import com.writing.novel.domain.Volume;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.LlmTaskType;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.memory.KnowledgeBaseService;
import com.writing.novel.memory.VectorRetrievalService;
import com.writing.novel.persistence.embedding.EmbeddedVectorStore;
import com.writing.novel.persistence.embedding.StubEmbeddingProvider;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.persistence.sqlite.SqliteStoryCanon;
import com.writing.novel.persistence.sqlite.StoryFactRepository;
import com.writing.novel.spi.StoryCanon;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** p26-02: checkpoint revision must update its real target before the checkpoint is handled. */
class CheckpointRevisionRoutingTest {

    @TempDir Path tempDir;
    private ConnectionProvider connectionProvider;
    private SqliteNovelRepository repository;
    private QueueGateway gateway;
    private NovelOrchestrator orchestrator;
    private Project project;
    private KnowledgeBaseService knowledgeBase;
    private StoryCanon storyCanon;

    @BeforeEach
    void setUp() {
        connectionProvider = new ConnectionProvider(tempDir.resolve("revision.db").toString());
        new SqliteSchemaInitializer(connectionProvider).initialize();
        repository = new SqliteNovelRepository(connectionProvider);
        gateway = new QueueGateway();
        var vector = new VectorRetrievalService(
                new EmbeddedVectorStore(tempDir.resolve("vectors.idx").toString()),
                new StubEmbeddingProvider());
        knowledgeBase = new KnowledgeBaseService(repository, vector);
        var worldBuilder = new WorldBuilderAgent(gateway, knowledgeBase, TestPromptHelper.create());
        var plotter = new PlotterAgent(gateway, repository, TestPromptHelper.create());
        storyCanon = new SqliteStoryCanon(new StoryFactRepository(connectionProvider));
        orchestrator = new NovelOrchestrator(repository, new ProjectService(repository),
                worldBuilder, null, plotter, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                null, null, null, null,
                new OrchestratorPolicies(storyCanon, null, null, null, null, null,
                        null, null, null, null, null));
        project = new Project("审核书", "修炼者调查旧案", "玄幻");
        repository.saveProject(project);
    }

    @Test
    void initialSetupRevisionReplacesPersistedWorldAndReturnsChangedSummary_withoutHandlingCheckpoint() {
        KnowledgeEntry old = new KnowledgeEntry(project.getId(), "POWER_SYSTEM", "九境灵气", "修士可飞升");
        knowledgeBase.saveEntry(old);
        storyCanon.saveIfAbsent(new StoryFact(project.getId(), StoryFact.FactType.WORLD_RULE,
                "九境灵气", "九境灵气：修士可飞升", 0));
        storyCanon.saveIfAbsent(new StoryFact(project.getId(), StoryFact.FactType.WORLD_RULE,
                "时代锚点", "时代一致性铁律：架空王朝", 0));
        Checkpoint checkpoint = pending(Checkpoint.Type.AFTER_INITIAL_SETUP, "初始设定生成完成，待审核");
        gateway.reply(LlmTaskType.WORLDBUILDING, """
                【地理】北境三州：故事发生在严寒边镇。
                【历史】永熙年间：王朝末年，民间只有低微方术。
                【规则】术法限制：方术只能辨迹寻物，不能飞升。
                """);

        var result = orchestrator.reviseCheckpoint(
                project.getId(), checkpoint, "改成低魔历史题材，删除所有修炼体系");

        List<KnowledgeEntry> after = repository.findKnowledgeEntriesByProject(project.getId());
        assertEquals("INITIAL_SETUP", result.targetType());
        assertNotEquals(result.beforeSummary(), result.afterSummary());
        assertNotEquals(result.beforeVersion(), result.afterVersion());
        assertEquals(3, after.size());
        assertTrue(after.stream().noneMatch(e -> "九境灵气".equals(e.getName())));
        assertTrue(after.stream().anyMatch(e -> e.getContent().contains("不能飞升")));
        assertNull(knowledgeBase.getIndexedText(old.getId()));
        assertTrue(after.stream().allMatch(entry -> knowledgeBase.getIndexedText(entry.getId()) != null));
        assertTrue(storyCanon.recallBySubject(project.getId(), "九境灵气").isEmpty());
        StoryFact historyFact = storyCanon.recallBySubject(project.getId(), "永熙年间").get(0);
        assertEquals(StoryFact.Status.ACTIVE, historyFact.getStatus());
        assertTrue(historyFact.getAssertion().startsWith(StoryCanon.HUMAN_PATCH_PREFIX));
        assertEquals(1, storyCanon.recallBySubject(project.getId(), "时代锚点").size());
        assertEquals(Checkpoint.Status.PENDING,
                repository.findCheckpoint(checkpoint.getId()).orElseThrow().getStatus());
    }

    @Test
    void volumeRevisionChangesOnlyUnwrittenSynopsesAndKeepsWrittenBoundary() {
        project.setCurrentChapterIndex(1);
        repository.saveProject(project);
        Outline outline = outlineWithThreeChapters();
        repository.saveOutline(outline);
        ChapterSynopsis writtenBefore = outline.getVolumes().get(0).getSynopses().get(0);
        Checkpoint checkpoint = pending(Checkpoint.Type.AFTER_VOLUME_OUTLINE, "卷1大纲已生成，待审核");
        gateway.reply(LlmTaskType.PLOTTING, """
                第2章 账册失踪：场景节拍：库房盘点→发现缺页→锁定嫌疑。人物：主角。目的：调查账册失踪案。张力：7。
                第3章 夜访证人：场景节拍：跟踪→问证→遭伏击。人物：主角。目的：取得关键口供。张力：8。
                """);

        var result = orchestrator.reviseCheckpoint(
                project.getId(), checkpoint, "把第二章改为调查账册失踪案");

        Outline after = repository.findOutlineByProject(project.getId()).orElseThrow();
        ChapterSynopsis writtenAfter = synopsis(after, 1);
        assertEquals("VOLUME_OUTLINE", result.targetType());
        assertNotEquals(result.beforeSummary(), result.afterSummary());
        assertNotEquals(result.beforeVersion(), result.afterVersion());
        assertEquals(writtenBefore.getId(), writtenAfter.getId());
        assertEquals(writtenBefore.getTitle(), writtenAfter.getTitle());
        assertEquals(writtenBefore.getSynopsis(), writtenAfter.getSynopsis());
        assertTrue(synopsis(after, 2).getSynopsis().contains("调查账册失踪案"));
        assertEquals(1, repository.findProject(project.getId()).orElseThrow().getCurrentChapterIndex());
        assertEquals(Checkpoint.Status.PENDING,
                repository.findCheckpoint(checkpoint.getId()).orElseThrow().getStatus());
    }

    @Test
    void emptyWorldCandidateLeavesOriginalWorldAndCheckpointPending() {
        KnowledgeEntry original = new KnowledgeEntry(project.getId(), "RULE", "旧规则", "原内容");
        repository.saveKnowledgeEntry(original);
        Checkpoint checkpoint = pending(Checkpoint.Type.AFTER_INITIAL_SETUP, "初始设定生成完成，待审核");
        gateway.reply(LlmTaskType.WORLDBUILDING, "");
        gateway.reply(LlmTaskType.WORLDBUILDING, "");

        assertThrows(IllegalStateException.class, () -> orchestrator.reviseCheckpoint(
                project.getId(), checkpoint, "改写世界观"));

        List<KnowledgeEntry> after = repository.findKnowledgeEntriesByProject(project.getId());
        assertEquals(1, after.size());
        assertEquals(original.getId(), after.get(0).getId());
        assertEquals("原内容", after.get(0).getContent());
        assertEquals(Checkpoint.Status.PENDING,
                repository.findCheckpoint(checkpoint.getId()).orElseThrow().getStatus());
    }

    @Test
    void invalidVolumeCandidateLeavesOriginalOutlineAndCheckpointPending() {
        project.setCurrentChapterIndex(1);
        repository.saveProject(project);
        Outline original = outlineWithThreeChapters();
        repository.saveOutline(original);
        Checkpoint checkpoint = pending(Checkpoint.Type.AFTER_VOLUME_OUTLINE, "卷1大纲已生成，待审核");
        gateway.reply(LlmTaskType.PLOTTING,
                "第1章 越界覆盖：场景节拍：错误覆盖。人物：主角。目的：破坏已写边界。张力：9。");

        assertThrows(IllegalStateException.class, () -> orchestrator.reviseCheckpoint(
                project.getId(), checkpoint, "重写未写大纲"));

        Outline after = repository.findOutlineByProject(project.getId()).orElseThrow();
        assertEquals("旧一", synopsis(after, 1).getTitle());
        assertEquals("旧二", synopsis(after, 2).getTitle());
        assertEquals("旧三", synopsis(after, 3).getTitle());
        assertEquals(Checkpoint.Status.PENDING,
                repository.findCheckpoint(checkpoint.getId()).orElseThrow().getStatus());
    }

    @Test
    void knowledgeReplacementRollsBackWhenAnyInsertFails() throws Exception {
        KnowledgeEntry original = new KnowledgeEntry(project.getId(), "RULE", "旧规则", "原内容");
        repository.saveKnowledgeEntry(original);
        installAbortTrigger("knowledge_entries", "NEW.name = '触发失败'");
        List<KnowledgeEntry> replacement = List.of(
                new KnowledgeEntry(project.getId(), "HISTORY", "新历史", "新内容"),
                new KnowledgeEntry(project.getId(), "RULE", "触发失败", "不得落库"));

        assertThrows(RuntimeException.class,
                () -> repository.replaceKnowledgeEntries(project.getId(), replacement));

        List<KnowledgeEntry> after = repository.findKnowledgeEntriesByProject(project.getId());
        assertEquals(1, after.size());
        assertEquals(original.getId(), after.get(0).getId());
        assertEquals("原内容", after.get(0).getContent());
    }

    @Test
    void outlineReplacementRollsBackWhenAnySynopsisInsertFails() throws Exception {
        Outline original = outlineWithThreeChapters();
        repository.saveOutline(original);
        installAbortTrigger("chapter_synopses", "NEW.title = '触发失败'");
        Outline replacement = new Outline(project.getId(), "新总纲");
        Volume replacementVolume = new Volume(project.getId(), 1, "新卷", "新卷梗概");
        replacementVolume.addSynopsis(new ChapterSynopsis(project.getId(), 1, "可写入", "候选一"));
        replacementVolume.addSynopsis(new ChapterSynopsis(project.getId(), 2, "触发失败", "候选二"));
        replacement.addVolume(replacementVolume);

        assertThrows(RuntimeException.class, () -> repository.saveOutline(replacement));

        Outline after = repository.findOutlineByProject(project.getId()).orElseThrow();
        assertEquals("旧总纲", after.getPremise());
        assertEquals("旧卷", after.getVolumes().get(0).getTitle());
        assertEquals("旧一", synopsis(after, 1).getTitle());
        assertEquals("旧二", synopsis(after, 2).getTitle());
        assertEquals("旧三", synopsis(after, 3).getTitle());
    }

    private Checkpoint pending(Checkpoint.Type type, String description) {
        Checkpoint checkpoint = new Checkpoint(project.getId(), type, description);
        repository.saveCheckpoint(checkpoint);
        return checkpoint;
    }

    private Outline outlineWithThreeChapters() {
        Outline outline = new Outline(project.getId(), "旧总纲");
        Volume volume = new Volume(project.getId(), 1, "旧卷", "旧卷梗概");
        volume.addSynopsis(new ChapterSynopsis(project.getId(), 1, "旧一", "已写内容"));
        volume.addSynopsis(new ChapterSynopsis(project.getId(), 2, "旧二", "未写内容二"));
        volume.addSynopsis(new ChapterSynopsis(project.getId(), 3, "旧三", "未写内容三"));
        outline.addVolume(volume);
        return outline;
    }

    private static ChapterSynopsis synopsis(Outline outline, int chapterIndex) {
        return outline.getVolumes().stream()
                .flatMap(volume -> volume.getSynopses().stream())
                .filter(synopsis -> synopsis.getChapterIndex() == chapterIndex)
                .findFirst().orElseThrow();
    }

    private void installAbortTrigger(String table, String condition) throws Exception {
        try (Connection connection = connectionProvider.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TRIGGER abort_replacement BEFORE INSERT ON " + table
                    + " WHEN " + condition
                    + " BEGIN SELECT RAISE(ABORT, 'forced replacement failure'); END");
        }
    }

    private static final class QueueGateway implements LlmGateway {
        private final java.util.Map<LlmTaskType, ArrayDeque<String>> replies =
                new java.util.EnumMap<>(LlmTaskType.class);

        void reply(LlmTaskType type, String content) {
            replies.computeIfAbsent(type, ignored -> new ArrayDeque<>()).add(content);
        }

        @Override
        public LlmResponse complete(LlmRequest request) {
            ArrayDeque<String> queue = replies.get(request.getTaskType());
            String content = queue == null || queue.isEmpty() ? "" : queue.removeFirst();
            return new LlmResponse(content, 10, 10);
        }

        @Override
        public void streamComplete(LlmRequest request, StreamHandler handler) {
        }
    }
}
