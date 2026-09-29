package com.writing.novel.agent;

import com.writing.novel.domain.Chapter;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.telemetry.EventLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * NCS-068: 结构化输出有界重试。
 * 模型偶发无效输出（截断/退化）在 NCS-063/064 之后会显式失败并保留可恢复账本——正确但
 * 每次都中断写作流水线。本特性对「可判定无效」（JSON 解析失败/整批校验失败）做单次带因
 * 重试：事件 STRUCTURED_OUTPUT_RETRY 全量落盘，重试成功才继续，重试失败保持可恢复终态。
 * 与客户端空响应/未终止流重试同语义——瞬态服务端故障有界自愈，绝不伪完成。
 */
class StructuredOutputRetryTest {

    @TempDir Path dir;
    private SqliteNovelRepository repo;
    private LlmGateway gateway;
    private EntityExtractorAgent extractor;
    private Chapter chapter;
    private Path eventsFile;

    @BeforeEach
    void setUp() throws Exception {
        var cp = new ConnectionProvider(dir.resolve("retry.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        gateway = mock(LlmGateway.class);
        extractor = new EntityExtractorAgent(gateway, repo, TestPromptHelper.create());
        chapter = new Chapter("p", 3, "取证");
        chapter.setContent("裴照微取得会票。");
        repo.saveChapter(chapter);
        eventsFile = dir.resolve("events.jsonl");
        System.setProperty(EventLog.PATH_PROPERTY, eventsFile.toString());
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(EventLog.PATH_PROPERTY);
    }

    private static final String TRUNCATED_ENTITIES = "{\"entities\":[{\"name\":\"裴照微\"";
    private static final String VALID_ENTITIES = "{\"entities\":[{\"name\":\"裴照微\",\"type\":\"CHARACTER\"}]}";
    private static final String VALID_EDGES = "{\"edges\":[]}";

    @Test
    void 实体阶段无效输出恰好重试一次并成功() throws Exception {
        when(gateway.complete(any())).thenReturn(
                new LlmResponse(TRUNCATED_ENTITIES, 1, 1),
                new LlmResponse(VALID_ENTITIES, 1, 1),
                new LlmResponse(VALID_EDGES, 1, 1));

        var result = extractor.extract("p", chapter);

        assertNull(result.error(), "重试成功后不得遗留错误");
        assertEquals(1, result.newEntities());
        verify(gateway, times(3)).complete(any()); // 实体重试2次 + 关系1次
        com.writing.novel.telemetry.EventLog.awaitFlush();
        String events = Files.readString(eventsFile);
        assertTrue(events.contains("\"event\":\"STRUCTURED_OUTPUT_RETRY\""), "重试必须可观测: " + events);
        assertTrue(events.contains("\"phase\":\"entity-extraction.entities\""), events);
        assertTrue(events.contains("\"attempt\":1"), events);
    }

    @Test
    void 实体重试仍无效保持可恢复不伪完成() throws Exception {
        when(gateway.complete(any())).thenReturn(
                new LlmResponse(TRUNCATED_ENTITIES, 1, 1),
                new LlmResponse(TRUNCATED_ENTITIES, 1, 1));

        var result = extractor.extract("p", chapter);

        assertNotNull(result.error(), "重试失败必须保持错误终态");
        // 有界：实体阶段最多1次重试
        verify(gateway, times(2)).complete(any());
        assertTrue(repo.findEntitiesByProject("p").isEmpty());
    }

    @Test
    void 合法空实体不触发重试() throws Exception {
        when(gateway.complete(any())).thenReturn(
                new LlmResponse("{\"entities\":[]}", 1, 1));

        var result = extractor.extract("p", chapter);

        assertNull(result.error());
        verify(gateway, times(1)).complete(any());
        com.writing.novel.telemetry.EventLog.awaitFlush();
        String events = Files.exists(eventsFile) ? Files.readString(eventsFile) : "";
        assertFalse(events.contains("STRUCTURED_OUTPUT_RETRY"), "合法空结果不得触发重试: " + events);
    }

    @Test
    void 事实收割无效输出重试一次成功() throws Exception {
        var canon = mock(com.writing.novel.spi.StoryCanon.class);
        when(canon.recall("p")).thenReturn(java.util.List.of());
        var harvester = new FactHarvesterAgent(gateway, canon, TestPromptHelper.create());
        when(gateway.complete(any())).thenReturn(
                new LlmResponse("[", 1, 1),
                new LlmResponse("[]", 1, 1));

        var result = harvester.harvest("p", chapter);

        assertEquals(0, result.total(), "合法空事实为成功");
        verify(gateway, times(2)).complete(any());
        com.writing.novel.telemetry.EventLog.awaitFlush();
        String events = Files.readString(eventsFile);
        assertTrue(events.contains("\"phase\":\"fact-harvest\""), "事实重试必须可观测: " + events);
    }

    @Test
    void 事实收割重试仍无效抛异常保留可恢复() {
        var canon = mock(com.writing.novel.spi.StoryCanon.class);
        when(canon.recall("p")).thenReturn(java.util.List.of());
        var harvester = new FactHarvesterAgent(gateway, canon, TestPromptHelper.create());
        when(gateway.complete(any())).thenReturn(
                new LlmResponse("[", 1, 1),
                new LlmResponse("[", 1, 1));

        assertThrows(IllegalStateException.class, () -> harvester.harvest("p", chapter));
        // 有界：事实阶段最多1次重试
        verify(gateway, times(2)).complete(any());
    }

    @Test
    void 合法空事实不触发重试() throws Exception {
        var canon = mock(com.writing.novel.spi.StoryCanon.class);
        when(canon.recall("p")).thenReturn(java.util.List.of());
        var harvester = new FactHarvesterAgent(gateway, canon, TestPromptHelper.create());
        when(gateway.complete(any())).thenReturn(new LlmResponse("[]", 1, 1));

        assertEquals(0, harvester.harvest("p", chapter).total());
        verify(gateway, times(1)).complete(any());
    }
}
