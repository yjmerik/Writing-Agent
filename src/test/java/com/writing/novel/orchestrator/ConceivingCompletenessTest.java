package com.writing.novel.orchestrator;

import com.writing.novel.agent.*;
import com.writing.novel.domain.NovelState;
import com.writing.novel.domain.Project;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.memory.*;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.service.EntityGraphService;
import com.writing.novel.spi.EmbeddingProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** p14-02: empty conceiving output must stay CONCEIVING and fail loud. */
class ConceivingCompletenessTest {

    @TempDir Path tempDir;
    private NovelOrchestrator orchestrator;
    private SqliteNovelRepository repo;
    private ProjectService projectService;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("c.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);

        EmbeddingProvider emb = new EmbeddingProvider() {
            @Override public float[] embed(String text) { return new float[]{1.0f}; }
            @Override public int dimension() { return 1; }
        };
        var store = new com.writing.novel.persistence.embedding.EmbeddedVectorStore(
                tempDir.resolve("c.idx").toString());
        VectorRetrievalService vectorSvc = new VectorRetrievalService(store, emb);
        KnowledgeBaseService kbSvc = new KnowledgeBaseService(repo, vectorSvc);
        SummaryService summarySvc = new SummaryService(repo);
        EntityGraphService entityGraphSvc = new EntityGraphService(repo, null) {
            @Override public String recallConsistencySection(String projectId, int currentChapter) { return ""; }
            @Override public java.util.List<String> getRecentEntityNames(String projectId, int currentChapter) { return java.util.List.of(); }
            @Override public String resolveCanonical(String projectId, String rawName) { return rawName; }
            @Override public com.writing.novel.agent.EntityExtractorAgent.ExtractionResult extractFromChapter(String projectId, com.writing.novel.domain.Chapter chapter) {
                return new com.writing.novel.agent.EntityExtractorAgent.ExtractionResult(0, 0, 0, null);
            }
        };
        ContextAssembler assembler = new ContextAssembler(repo, kbSvc, summarySvc, vectorSvc, entityGraphSvc,
                new com.writing.novel.service.ClimaxDiversityTracker(), null, null, null);

        LlmGateway gateway = new LlmGateway() {
            @Override
            public LlmResponse complete(LlmRequest req) {
                return new LlmResponse("", 0, 0);
            }
            @Override
            public void streamComplete(LlmRequest req, StreamHandler h) {}
        };

        projectService = new ProjectService(repo);
        var promptSvc = TestPromptHelper.create();
        orchestrator = new NovelOrchestrator(repo, projectService,
                new WorldBuilderAgent(gateway, kbSvc, promptSvc),
                new CharacterDesignerAgent(gateway, kbSvc, promptSvc),
                new PlotterAgent(gateway, repo, promptSvc),
                new ChapterWriterAgent(gateway, assembler, repo, vectorSvc, promptSvc, null),
                new ContinuityCheckerAgent(gateway, repo, promptSvc),
                new StyleGuardianAgent(gateway, repo, promptSvc),
                new MemoryManagerAgent(gateway, summarySvc, promptSvc),
                summarySvc, assembler,
                new QualityEvaluationAgent(gateway, repo, promptSvc),
                null, null, null, null, null, null, null, null, null, null, null, null,
                new SceneClassifier(gateway, promptSvc),
                new com.writing.novel.service.DiversityTracker(), null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void emptyLlmOutput_staysConceiving_andThrowsWithMissingParts() {
        Project p = projectService.create("空构思", "短构思", "志怪");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> orchestrator.runConceiving(p.getId()));
        assertTrue(ex.getMessage().contains("世界观") || ex.getMessage().contains("人物") || ex.getMessage().contains("大纲"),
                ex.getMessage());

        Project found = repo.findProject(p.getId()).orElseThrow();
        assertEquals(NovelState.CONCEIVING, found.getState());
    }
}
