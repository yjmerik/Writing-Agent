package com.writing.novel.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.agent.CharacterDesignerAgent;
import com.writing.novel.agent.ChapterWriterAgent;
import com.writing.novel.agent.ContinuityCheckerAgent;
import com.writing.novel.agent.MemoryManagerAgent;
import com.writing.novel.agent.PlotterAgent;
import com.writing.novel.agent.SceneClassifier;
import com.writing.novel.agent.StyleGuardianAgent;
import com.writing.novel.agent.TestPromptHelper;
import com.writing.novel.agent.WorldBuilderAgent;
import com.writing.novel.domain.Checkpoint;
import com.writing.novel.domain.ChapterSynopsis;
import com.writing.novel.domain.Character;
import com.writing.novel.domain.NovelState;
import com.writing.novel.domain.Outline;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.ProjectMode;
import com.writing.novel.domain.Volume;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.memory.ContextAssembler;
import com.writing.novel.memory.KnowledgeBaseService;
import com.writing.novel.memory.SummaryService;
import com.writing.novel.memory.VectorRetrievalService;
import com.writing.novel.persistence.embedding.EmbeddedVectorStore;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.service.ClimaxDiversityTracker;
import com.writing.novel.service.DiversityTracker;
import com.writing.novel.service.EntityGraphService;
import com.writing.novel.spi.EmbeddingProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InitialSetupCheckpointPayloadTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    private SqliteNovelRepository repository;
    private ProjectService projectService;
    private NovelOrchestrator orchestrator;
    private String plottingResponse;

    @BeforeEach
    void setUp() {
        ConnectionProvider connectionProvider = new ConnectionProvider(tempDir.resolve("initial-setup.db").toString());
        new SqliteSchemaInitializer(connectionProvider).initialize();
        repository = new SqliteNovelRepository(connectionProvider);

        EmbeddingProvider embeddingProvider = new EmbeddingProvider() {
            @Override public float[] embed(String text) { return new float[]{1.0f}; }
            @Override public int dimension() { return 1; }
        };
        var vectorStore = new EmbeddedVectorStore(tempDir.resolve("initial-setup.idx").toString());
        var vectorService = new VectorRetrievalService(vectorStore, embeddingProvider);
        var knowledgeBase = new KnowledgeBaseService(repository, vectorService);
        var summaryService = new SummaryService(repository);
        var entityGraph = new EntityGraphService(repository, null);
        var contextAssembler = new ContextAssembler(repository, knowledgeBase, summaryService, vectorService,
                entityGraph, new ClimaxDiversityTracker(), null, null, null);
        var characterSequence = new AtomicInteger();
        plottingResponse = "【总纲】潮声尽头藏着旧王朝航路。" + "潮😀".repeat(350)
                + "\n【卷1】雾港启航\n追查失落航路。\n第1章 雾港：纪澜找到第一张星图。";
        LlmGateway gateway = new LlmGateway() {
            @Override
            public LlmResponse complete(LlmRequest request) {
                return switch (request.getTaskType()) {
                    case WORLDBUILDING -> new LlmResponse(
                            "【地理】琉璃群岛：\"潮汐门\"守护" + "岛😀".repeat(350)
                                    + "\n【力量体系】潮汐术：借月相辨认航路。", 50, 100);
                    case CHARACTER_DESIGN -> new LlmResponse(
                            "【姓名】纪澜" + characterSequence.incrementAndGet()
                                    + "\n【角色定位】主角\n【性格】" + "执着😀".repeat(140)
                                    + "\n【能力】辨认失落星图", 50, 100);
                    case PLOTTING -> new LlmResponse(plottingResponse, 50, 100);
                    default -> new LlmResponse("ok", 1, 1);
                };
            }

            @Override
            public void streamComplete(LlmRequest request, StreamHandler handler) {
            }
        };

        projectService = new ProjectService(repository);
        var promptService = TestPromptHelper.create();
        orchestrator = new NovelOrchestrator(repository, projectService,
                new WorldBuilderAgent(gateway, knowledgeBase, promptService),
                new CharacterDesignerAgent(gateway, knowledgeBase, promptService),
                new PlotterAgent(gateway, repository, promptService),
                new ChapterWriterAgent(gateway, contextAssembler, repository, vectorService, promptService, null),
                new ContinuityCheckerAgent(gateway, repository, promptService),
                new StyleGuardianAgent(gateway, repository, promptService),
                new MemoryManagerAgent(gateway, summaryService, promptService),
                summaryService, contextAssembler,
                new com.writing.novel.agent.QualityEvaluationAgent(gateway, repository, promptService),
                entityGraph, null, null, null, null, null, null, null, null, null, null, null,
                new SceneClassifier(gateway, promptService), new DiversityTracker(),
                null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void interactiveConceptionCreatesBoundedSummariesFromPersistedSetupWithWorkspaceLinks() throws Exception {
        String concept = "原旨标记·纪澜追查失落航路" + "原😀".repeat(350);
        Project project = projectService.create(
                "潮汐旧闻", concept, "奇幻", ProjectMode.INTERACTIVE, null, 3);

        orchestrator.runConceiving(project.getId());

        Checkpoint checkpoint = repository.findPendingCheckpoints(project.getId()).get(0);
        assertEquals(Checkpoint.Type.AFTER_INITIAL_SETUP, checkpoint.getType());
        assertNotNull(checkpoint.getPayload(), "初始设定 checkpoint 必须携带可审核摘要");
        JsonNode payload = OBJECT_MAPPER.readTree(checkpoint.getPayload());

        assertSummary(payload, "worldSummary", "琉璃群岛");
        assertSummary(payload, "characterSummary", "纪澜");
        assertSummary(payload, "premiseSummary", "原旨标记");
        assertTrue(payload.path("premiseSummary").asText().contains("潮声尽头"),
                "超长项目原旨不得吞掉已持久化的大纲 premise");

        Set<String> targets = new HashSet<>();
        for (JsonNode link : payload.path("links")) {
            assertFalse(link.path("label").asText().isBlank(), "跳转链接必须有可访问名称");
            String target = link.path("target").asText();
            assertTrue(Set.of("world", "outline").contains(target),
                    "跳转目标必须属于 workspace 已约定的内容页签");
            targets.add(target);
        }
        assertTrue(targets.containsAll(Set.of("world", "outline")),
                "初始设定审核必须能跳转世界观/人物和大纲");
    }

    @Test
    void emptyConceptAndPremiseUsePersistedVolumeTitleAndSummary() throws Exception {
        plottingResponse = "【总纲】\n【卷1】回声雾港\n旧案线索在潮汐中重现。\n第1章 雾港：纪澜找到第一张星图。";
        Project project = projectService.create(
                "潮汐旧闻", "", "奇幻", ProjectMode.INTERACTIVE, null, 3);

        orchestrator.runConceiving(project.getId());

        Checkpoint checkpoint = repository.findPendingCheckpoints(project.getId()).get(0);
        JsonNode payload = OBJECT_MAPPER.readTree(checkpoint.getPayload());
        String premiseSummary = payload.path("premiseSummary").asText();
        assertFalse(premiseSummary.isBlank(), "卷标题/summary 应为缺失原旨提供真实持久化回退");
        assertTrue(premiseSummary.contains("回声雾港"));
        assertTrue(premiseSummary.contains("旧案线索在潮汐中重现"));
        assertAllSummariesNonBlank(payload);
    }

    @Test
    void noPersistedPremiseOrVolumeSummaryFailsClosedBeforeCheckpoint() {
        Project project = projectService.create(
                "潮汐旧闻", "", "奇幻", ProjectMode.INTERACTIVE, null, 4);
        repository.saveCharacter(new Character(project.getId(), "纪澜", "主角"));
        Outline outline = new Outline(project.getId(), "");
        Volume emptyVolume = new Volume(project.getId(), 1, "", "");
        for (int chapter = 1; chapter <= 4; chapter++) {
            emptyVolume.addSynopsis(new ChapterSynopsis(
                    project.getId(), chapter, "占位章" + chapter, "仅用于证明大纲已持久化"));
        }
        outline.addVolume(emptyVolume);
        repository.saveOutline(outline);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> orchestrator.runConceiving(project.getId()));

        assertTrue(failure.getMessage().contains("原旨") || failure.getMessage().contains("大纲摘要"));
        assertEquals(NovelState.CONCEIVING,
                repository.findProject(project.getId()).orElseThrow().getState());
        assertTrue(repository.findPendingCheckpoints(project.getId()).isEmpty(),
                "缺少真实摘要时不得创建空内容 checkpoint");
    }

    private static void assertAllSummariesNonBlank(JsonNode payload) {
        assertFalse(payload.path("worldSummary").asText().isBlank());
        assertFalse(payload.path("characterSummary").asText().isBlank());
        assertFalse(payload.path("premiseSummary").asText().isBlank());
    }

    private static void assertSummary(JsonNode payload, String field, String sourceMarker) {
        String summary = payload.path(field).asText();
        assertFalse(summary.isBlank(), field + " 不得为空");
        assertTrue(summary.contains(sourceMarker), field + " 必须来自已持久化内容");
        assertEquals(300, summary.codePointCount(0, summary.length()), field + " 应按 code point 截断");
        assertTrue(hasOnlyPairedSurrogates(summary), field + " 不得截断 surrogate pair");
    }

    private static boolean hasOnlyPairedSurrogates(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (java.lang.Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length() || !java.lang.Character.isLowSurrogate(value.charAt(index + 1))) return false;
                index++;
            } else if (java.lang.Character.isLowSurrogate(current)) {
                return false;
            }
        }
        return true;
    }
}
