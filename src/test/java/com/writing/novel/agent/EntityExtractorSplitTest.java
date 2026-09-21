package com.writing.novel.agent;

import com.writing.novel.domain.Chapter;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.LlmTaskType;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * NCS-067: 实体提取拆分与 schema 瘦身。
 * 根因：单次调用要求实体+关系+description 全量输出，需求约 9.4k 字符——无论预算多大都
 * 鼓励模型写长输出（continued-entity-diagnosis-20260921.json 三章均在 4096 token 处截断）。
 * 方案：拆为「紧凑实体」「关系（仅引用规范名）」两次调用，各自独立预算；任一步失败保持
 * 可恢复、不部分标记、恢复重跑不重复。
 */
class EntityExtractorSplitTest {

    @TempDir Path dir;
    private SqliteNovelRepository repo;
    private LlmGateway gateway;
    private EntityExtractorAgent agent;
    private Chapter chapter;

    @BeforeEach
    void setUp() {
        var cp = new ConnectionProvider(dir.resolve("split.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        gateway = mock(LlmGateway.class);
        agent = new EntityExtractorAgent(gateway, repo, TestPromptHelper.create());
        chapter = new Chapter("p", 3, "取证");
        chapter.setContent("裴照微从盐帮手中取得会票，查验了盐霜印记。");
        repo.saveChapter(chapter);
    }

    private static final String COMPACT_ENTITIES = """
            {"entities":[{"name":"裴照微","type":"CHARACTER"},{"name":"会票","type":"ITEM"},{"name":"盐帮","type":"FACTION"}]}
            """;
    private static final String EDGES_ONLY = """
            {"edges":[{"source":"裴照微","target":"会票","relationship":"OWNS"},
                      {"source":"会票","target":"盐帮","relationship":"RELATED_TO"}]}
            """;

    @Test
    void 拆分为实体与关系两次调用_各自独立解析落库() {
        when(gateway.complete(any())).thenReturn(
                new LlmResponse(COMPACT_ENTITIES, 1, 1),
                new LlmResponse(EDGES_ONLY, 1, 1));

        var result = agent.extract("p", chapter);

        assertNull(result.error());
        assertEquals(3, result.newEntities());
        assertEquals(0, result.updatedEntities());
        assertEquals(2, result.newEdges());
        assertEquals(3, repo.findEntitiesByProject("p").size());
        assertEquals(2, repo.findEdgesByProject("p").size());

        ArgumentCaptor<LlmRequest> captor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(gateway, times(2)).complete(captor.capture());
        var requests = captor.getAllValues();
        assertEquals(LlmTaskType.EXTRACTION, requests.get(0).getTaskType());
        assertEquals(LlmTaskType.EXTRACTION, requests.get(1).getTaskType());
        // 第二次调用必须携带第一步提取出的规范名清单（关系只引用清单内实体）
        assertTrue(requests.get(1).getSystemPrompt().contains("裴照微"),
                "关系调用 prompt 应包含第一步提取的实体名");
        assertTrue(requests.get(1).getSystemPrompt().contains("盐帮"));
    }

    @Test
    void 实体响应截断_不发起关系调用且不落库() {
        when(gateway.complete(any())).thenReturn(
                new LlmResponse("{\"entities\":[{\"name\":\"裴照微\"", 1, 1));

        var result = agent.extract("p", chapter);

        assertNotNull(result.error(), "截断的实体响应经一次带因重试后仍必须显式失败");
        // NCS-068: 首次 + 有界重试1次 = 2次调用；实体失败后不得发起关系调用
        verify(gateway, times(2)).complete(any());
        assertTrue(repo.findEntitiesByProject("p").isEmpty(), "截断响应不得部分落库");
    }

    @Test
    void 关系响应截断_实体保留_恢复重跑不重复() {
        when(gateway.complete(any())).thenReturn(
                new LlmResponse(COMPACT_ENTITIES, 1, 1),
                new LlmResponse("{\"edges\":[", 1, 1));

        var first = agent.extract("p", chapter);
        assertNotNull(first.error(), "关系响应截断必须报错，不得伪完成");
        assertEquals(3, repo.findEntitiesByProject("p").size(), "实体已先落库，供恢复阶段去重");
        assertEquals(0, repo.findEdgesByProject("p").size());

        // 恢复重跑：实体全部命中去重，关系成功建立且不重复
        when(gateway.complete(any())).thenReturn(
                new LlmResponse(COMPACT_ENTITIES, 1, 1),
                new LlmResponse(EDGES_ONLY, 1, 1));
        var second = agent.extract("p", chapter);

        assertNull(second.error());
        assertEquals(0, second.newEntities(), "恢复阶段实体应全部命中已有清单");
        assertEquals(3, second.updatedEntities());
        assertEquals(3, repo.findEntitiesByProject("p").size(), "恢复不重复建实体");
        assertEquals(2, repo.findEdgesByProject("p").size());
    }

    @Test
    void 有效空实体与空关系_不报错() {
        when(gateway.complete(any())).thenReturn(
                new LlmResponse("{\"entities\":[]}", 1, 1),
                new LlmResponse("{\"edges\":[]}", 1, 1));
        var result = agent.extract("p", chapter);
        assertNull(result.error());
        assertEquals(0, result.newEntities());
        assertEquals(0, result.newEdges());
    }
}
