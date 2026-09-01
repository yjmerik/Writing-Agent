package com.writing.novel.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.agent.MemoryManagerAgent;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.Summary;
import com.writing.novel.memory.*;
import com.writing.novel.agent.EntityExtractorAgent;
import com.writing.novel.service.ClimaxDiversityTracker;
import com.writing.novel.service.EntityGraphService;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.spi.EmbeddingProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * p19-03: 全书结构化快照——book_summary ≤3 句无结构参照，第 300 章对全书状态无可靠记忆。
 * 卷末生成结构化 JSON 快照（主线进度/阵营/关键人物/时间线），ContextAssembler 顶部注入。
 */
class BookSnapshotTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;
    private SqliteNovelRepository repo;
    private NovelOrchestrator orchestrator;
    private ContextAssembler assembler;
    private MemoryManagerAgent memoryManager;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("snap.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);

        EmbeddingProvider emb = new EmbeddingProvider() {
            @Override public float[] embed(String text) { return new float[]{1.0f}; }
            @Override public int dimension() { return 1; }
        };
        var store = new com.writing.novel.persistence.embedding.EmbeddedVectorStore(
                tempDir.resolve("snap.idx").toString());
        VectorRetrievalService vectorSvc = new VectorRetrievalService(store, emb);
        KnowledgeBaseService kbSvc = new KnowledgeBaseService(repo, vectorSvc);
        SummaryService summarySvc = new SummaryService(repo);
        EntityGraphService entityGraphSvc = new EntityGraphService(repo, null) {
            @Override public String recallConsistencySection(String projectId, int currentChapter) { return ""; }
            @Override public java.util.List<String> getRecentEntityNames(String projectId, int currentChapter) { return java.util.List.of(); }
            @Override public String resolveCanonical(String projectId, String rawName) { return rawName; }
            @Override public EntityExtractorAgent.ExtractionResult extractFromChapter(String projectId, com.writing.novel.domain.Chapter chapter) {
                return new EntityExtractorAgent.ExtractionResult(0, 0, 0, null);
            }
        };
        assembler = new ContextAssembler(repo, kbSvc, summarySvc, vectorSvc, entityGraphSvc,
                new ClimaxDiversityTracker(), null, null, null);

        memoryManager = mock(MemoryManagerAgent.class);
        // 构造器参数序：repo, projectService, worldBuilder, characterDesigner, plotter,
        // chapterWriter, continuityChecker, styleGuardian, memoryManager(第9位), ...
        orchestrator = new NovelOrchestrator(repo, new ProjectService(repo),
                null, null, null, null, null, null, memoryManager, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private Summary llmSummary(String content) {
        Summary s = new Summary("proj", 1, 8, content);
        return s;
    }

    @Test
    void 卷末生成结构化快照_合法JSON含主线进度() throws Exception {
        Project p = new Project("快照", "构思", "武侠");
        repo.saveProject(p);
        String json = """
                {"mainlineProgress":"主角已查出灭门真凶","activeFactions":"青云门、漕帮","keyCharacters":"林凡(重伤)、叶秋(叛变)","timelineAnchors":"第1卷冬至、第2卷春闱","milestones":"夺回剑谱"}
                """;
        when(memoryManager.summarize(eq(p.getId()), eq(1), anyInt(), anyString()))
                .thenReturn(llmSummary(json));

        orchestrator.updateBookSnapshot(p.getId(), 8, "本卷：主角与漕帮决战，重伤获胜。");

        var saved = repo.findProject(p.getId()).orElseThrow();
        assertNotNull(saved.getBookSnapshot(), "卷末应生成全书快照");
        JsonNode node = MAPPER.readTree(saved.getBookSnapshot());
        assertEquals("主角已查出灭门真凶", node.get("mainlineProgress").asText());
        assertTrue(node.has("activeFactions"));
        assertTrue(node.has("timelineAnchors"));
    }

    @Test
    void 快照更新失败保留旧版不阻塞写作() {
        Project p = new Project("快照容错", "构思", "武侠");
        p.setBookSnapshot("{\"mainlineProgress\":\"旧状态\"}");
        repo.saveProject(p);
        when(memoryManager.summarize(anyString(), anyInt(), anyInt(), anyString()))
                .thenThrow(new RuntimeException("LLM 故障"));

        orchestrator.updateBookSnapshot(p.getId(), 8, "本卷摘要");

        assertEquals("{\"mainlineProgress\":\"旧状态\"}",
                repo.findProject(p.getId()).orElseThrow().getBookSnapshot(),
                "快照更新失败应保留旧版");
    }

    @Test
    void LLM返回非JSON时保留旧版() {
        Project p = new Project("快照坏输出", "构思", "武侠");
        p.setBookSnapshot("{\"mainlineProgress\":\"旧\"}");
        repo.saveProject(p);
        when(memoryManager.summarize(anyString(), anyInt(), anyInt(), anyString()))
                .thenReturn(llmSummary("这不是JSON"));

        orchestrator.updateBookSnapshot(p.getId(), 8, "摘要");

        assertEquals("{\"mainlineProgress\":\"旧\"}",
                repo.findProject(p.getId()).orElseThrow().getBookSnapshot());
    }

    @Test
    void 上下文顶部注入全书快照段() {
        Project p = new Project("注入", "构思", "武侠");
        p.setBookSnapshot("{\"mainlineProgress\":\"主角已查出真凶\"}");
        repo.saveProject(p);

        var ctx = assembler.assemble(p.getId(), 12, "第12章", "梗概", "");
        String recent = ctx.getRecentSummarySection() == null ? "" : ctx.getRecentSummarySection();
        assertTrue(recent.contains("【全书快照】"), "recentSummary 顶部应含全书快照段");
        assertTrue(recent.contains("主角已查出真凶"), "快照内容应被注入");
    }
}
