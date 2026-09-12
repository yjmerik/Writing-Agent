package com.writing.novel.agent;

import com.writing.novel.domain.Worldbuilding;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.memory.KnowledgeBaseService;
import com.writing.novel.memory.VectorRetrievalService;
import com.writing.novel.persistence.embedding.EmbeddedVectorStore;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.spi.EmbeddingProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * p34-01（UI 验收 A1，高）：null/空 tags 的项目构思不得 NPE。
 * 复现：新建项目仅填标题+构思，类型标签留空（表单允许）→
 * WorldBuilderAgent.propose 的 Map.of 接收 null tags，模型调用前即 NPE，
 * 界面呈现"创作失败"且无可定位原因。
 */
class WorldBuilderOptionalTagsTest {

    @TempDir
    Path tempDir;

    private WorldBuilderAgent agent() {
        ConnectionProvider cp = new ConnectionProvider(
                tempDir.resolve("wb-tags.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        SqliteNovelRepository repo = new SqliteNovelRepository(cp);
        EmbeddingProvider emb = new EmbeddingProvider() {
            @Override public float[] embed(String text) { return new float[]{1.0f}; }
            @Override public int dimension() { return 1; }
        };
        var store = new EmbeddedVectorStore(tempDir.resolve("wb-tags.idx").toString());
        var vectorSvc = new VectorRetrievalService(store, emb);
        var kbSvc = new KnowledgeBaseService(repo, vectorSvc);
        LlmGateway gw = new LlmGateway() {
            @Override
            public com.writing.novel.llm.LlmResponse complete(com.writing.novel.llm.LlmRequest req) {
                // 回显 userMessage，便于断言 tags 变量已被归一化（非 null 字符串）
                String user = req.getUserMessage() == null ? "" : req.getUserMessage();
                assertTrue(!user.contains("tags=null"), "tags 变量不得为 null 字符串");
                return new com.writing.novel.llm.LlmResponse(
                        "【地理】雾江旧城：沿江小城。\n【社会】殡葬行业：边缘而不可或缺。", 50, 100);
            }

            @Override
            public void streamComplete(com.writing.novel.llm.LlmRequest req,
                                       com.writing.novel.llm.client.StreamHandler h) {
            }
        };
        return new WorldBuilderAgent(gw, kbSvc, TestPromptHelper.create());
    }

    @Test
    void nullTags构思不NPE且世界观可用() {
        Worldbuilding wb = agent().build("p-null", "九十年代殡仪馆化妆师的悬疑故事", null);
        assertNotNull(wb);
        assertEquals(2, wb.getEntries().size(), "占位兜底前应有 2 条解析条目");
    }

    @Test
    void 空白Tags构思不NPE() {
        Worldbuilding wb = agent().build("p-blank", "九十年代殡仪馆化妆师的悬疑故事", "   ");
        assertNotNull(wb);
        assertTrue(wb.getEntries().size() >= 2);
    }

    @Test
    void 正常Tags行为不变() {
        Worldbuilding wb = agent().build("p-normal", "悬疑故事", "悬疑,现实");
        assertNotNull(wb);
        assertEquals(2, wb.getEntries().size());
    }
}
