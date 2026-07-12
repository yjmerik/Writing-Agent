package com.writing.novel.orchestrator;

import com.writing.novel.agent.*;
import com.writing.novel.domain.*;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.memory.*;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.spi.EmbeddingProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class NovelOrchestratorConceiveTest {

    @TempDir Path tempDir;
    private NovelOrchestrator orchestrator;
    private SqliteNovelRepository repo;
    private ProjectService projectService;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("o.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);

        EmbeddingProvider emb = new EmbeddingProvider() {
            @Override public float[] embed(String text) { return new float[]{1.0f}; }
            @Override public int dimension() { return 1; }
        };
        var store = new com.writing.novel.persistence.embedding.EmbeddedVectorStore(
                tempDir.resolve("o.idx").toString());
        VectorRetrievalService vectorSvc = new VectorRetrievalService(store, emb);
        KnowledgeBaseService kbSvc = new KnowledgeBaseService(repo, vectorSvc);
        SummaryService summarySvc = new SummaryService(repo);
        ContextAssembler assembler = new ContextAssembler(repo, kbSvc, summarySvc, vectorSvc);

        LlmGateway gateway = new LlmGateway() {
            @Override
            public LlmResponse complete(LlmRequest req) {
                return switch (req.getTaskType()) {
                    case WORLDBUILDING -> new LlmResponse(
                            "【地理】玄黄大陆：东方主大陆。\n【力量体系】灵气：修炼九重。", 50, 100);
                    case CHARACTER_DESIGN -> new LlmResponse(
                            "【姓名】张三\n【角色定位】主角\n【性格】火爆\n【能力】剑修", 50, 100);
                    case PLOTTING -> new LlmResponse(
                            "【总纲】张三的修炼之路。\n\n【卷1】初入仙门\n拜入宗门。\n第1章 测试：灵根测试。", 50, 100);
                    default -> new LlmResponse("ok", 1, 1);
                };
            }
            @Override
            public void streamComplete(LlmRequest req, StreamHandler h) {}
        };

        projectService = new ProjectService(repo);
        var promptSvc = com.writing.novel.agent.TestPromptHelper.create();
        orchestrator = new NovelOrchestrator(repo, projectService,
                new WorldBuilderAgent(gateway, kbSvc, promptSvc),
                new CharacterDesignerAgent(gateway, kbSvc, promptSvc),
                new PlotterAgent(gateway, repo, promptSvc),
                new ChapterWriterAgent(gateway, assembler, repo, vectorSvc, promptSvc),
                new ContinuityCheckerAgent(gateway, repo, promptSvc),
                new StyleGuardianAgent(gateway, repo, promptSvc),
                new MemoryManagerAgent(gateway, summarySvc, promptSvc),
                summarySvc, assembler);
    }

    @Test
    void Solo模式运行构思阶段生成设定并转入规划() {
        Project p = projectService.create("测试", "剑修故事", "玄幻");

        orchestrator.runConceiving(p.getId());

        Project found = repo.findProject(p.getId()).orElseThrow();
        assertEquals(NovelState.PLANNING, found.getState());
        assertFalse(repo.findKnowledgeEntriesByProject(p.getId()).isEmpty(), "应有世界观条目");
        assertFalse(repo.findCharactersByProject(p.getId()).isEmpty(), "应有人物");
        assertNotNull(repo.findOutlineByProject(p.getId()).orElse(null), "应有大纲");
    }
}
