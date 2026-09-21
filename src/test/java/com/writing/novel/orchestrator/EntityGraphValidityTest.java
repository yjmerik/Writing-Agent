package com.writing.novel.orchestrator;

import com.writing.novel.agent.EntityExtractorAgent;
import com.writing.novel.agent.TestPromptHelper;
import com.writing.novel.domain.Chapter;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.persistence.sqlite.*;
import com.writing.novel.service.EntityGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EntityGraphValidityTest {
    @TempDir Path dir;
    private SqliteNovelRepository repo;
    private PostProcessLedgerRepository ledger;
    private LlmGateway gateway;
    private EntityGraphService service;
    private Chapter chapter;
    private final ChapterPostProcessor.Stage stage = ChapterPostProcessor.Stage.ENTITY_GRAPH;

    @BeforeEach void setUp() {
        var cp = new ConnectionProvider(dir.resolve("graph.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        ledger = new PostProcessLedgerRepository(cp);
        gateway = mock(LlmGateway.class);
        service = new EntityGraphService(repo, new EntityExtractorAgent(gateway, repo, TestPromptHelper.create()));
        chapter = new Chapter("p", 3, "取证");
        chapter.setContent("裴照微取得会票。");
        chapter.setStatus(Chapter.Status.POST_PROCESSING);
        repo.saveChapter(chapter);
        ledger.ensureStage("p", 3, stage);
    }

    private ChapterPostProcessor processor(EntityGraphService graph) {
        return new ChapterPostProcessor(mock(NovelOrchestrator.class), repo, null, graph,
                null, null, null, null, null, null, null, null, null, null, null, null, ledger);
    }

    @Test void 截断JSON保留未完成账本且恢复不重复图数据() {
        String valid = """
                {"entities":[{"name":"裴照微","type":"CHARACTER"},{"name":"会票","type":"ITEM"}],
                 "edges":[{"source":"裴照微","target":"会票","relationship":"RELATED_TO"}]}
                """;
        // NCS-068: 首次截断 → 带因重试仍截断 → 才失败保留账本；恢复轮拿到合法响应
        when(gateway.complete(any())).thenReturn(
                new LlmResponse("{\"entities\":[{\"name\":\"会票\",\"type\":\"ITEM\"}],\"edges\":[", 1, 1),
                new LlmResponse("{\"entities\":[{\"name\":\"会票\",\"type\":\"ITEM\"}],\"edges\":[", 1, 1),
                new LlmResponse(valid, 1, 1));
        var processor = processor(service);
        var error = assertThrows(IllegalStateException.class,
                () -> processor.resume("p", chapter, 3, null, null));
        assertTrue(error.getMessage().contains("实体"));
        assertEquals(PostProcessLedgerRepository.StageStatus.RUNNING, ledger.statusOf("p", 3, stage).orElseThrow());
        assertTrue(repo.findEntitiesByProject("p").isEmpty());
        assertEquals(chapter.getContent(), repo.findChapterByIndex("p", 3).orElseThrow().getContent());
        assertEquals(Chapter.Status.POST_PROCESSING, repo.findChapterByIndex("p", 3).orElseThrow().getStatus());
        processor.resume("p", chapter, 3, null, null);
        processor.resume("p", chapter, 3, null, null);
        assertEquals(PostProcessLedgerRepository.StageStatus.COMPLETED, ledger.statusOf("p", 3, stage).orElseThrow());
        assertEquals(2, repo.findEntitiesByProject("p").size());
        assertEquals(1, repo.findEdgesByProject("p").size());
        // NCS-068 重试后：失败轮2次（首次+重试）+ 恢复轮2次（实体+关系）= 4 次调用
        verify(gateway, times(4)).complete(any());
    }

    @Test void 有效空图完成账本() {
        when(gateway.complete(any())).thenReturn(new LlmResponse("{\"entities\":[],\"edges\":[]}", 1, 1));
        processor(service).resume("p", chapter, 3, null, null);
        assertEquals(PostProcessLedgerRepository.StageStatus.COMPLETED, ledger.statusOf("p", 3, stage).orElseThrow());
    }

    @Test void 错误结果即使带部分计数也不得完成() {
        var failing = mock(EntityGraphService.class);
        when(failing.extractFromChapter("p", chapter))
                .thenReturn(new EntityExtractorAgent.ExtractionResult(1, 0, 0, "提取未完成"));
        assertThrows(IllegalStateException.class, () -> processor(failing).resume("p", chapter, 3, null, null));
        assertEquals(PostProcessLedgerRepository.StageStatus.RUNNING, ledger.statusOf("p", 3, stage).orElseThrow());
    }
}
