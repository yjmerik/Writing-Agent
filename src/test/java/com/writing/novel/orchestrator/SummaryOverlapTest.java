package com.writing.novel.orchestrator;

import com.writing.novel.domain.*;
import com.writing.novel.llm.*;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.agent.*;
import com.writing.novel.memory.*;
import com.writing.novel.service.EntityGraphService;
import com.writing.novel.spi.EmbeddingProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * so-fix: 摘要范围重叠修复测试。
 * 卷级摘要 [1-8] 生成后，被覆盖的细粒度 [1-3]/[4-6] 应被删除。
 */
class SummaryOverlapTest {

    @TempDir
    Path tempDir;

    @Test
    void 卷级摘要生成后删除被覆盖的细粒度摘要() throws Exception {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("so.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        SqliteNovelRepository repo = new SqliteNovelRepository(cp);

        var p = new Project("重叠测", "故事", "悬疑");
        repo.saveProject(p);

        // 模拟已有细粒度摘要
        repo.saveSummary(new Summary(p.getId(), 1, 3, "摘要1-3"));
        repo.saveSummary(new Summary(p.getId(), 4, 6, "摘要4-6"));
        // 模拟一个不在范围内的
        repo.saveSummary(new Summary(p.getId(), 10, 12, "摘要10-12"));

        // LLM 桩：MEMORY_SUMMARY 返回卷级摘要
        LlmGateway gateway = new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest req) {
                return new LlmResponse("卷级摘要内容", 50, 50);
            }
            @Override public void streamComplete(LlmRequest req, StreamHandler h) {}
        };
        var promptSvc = TestPromptHelper.create();
        SummaryService summarySvc = new SummaryService(repo);

        // removeSupersededSummaries 只依赖 repository，其余 agent 可全 null
        NovelOrchestrator orchestrator = new NovelOrchestrator(repo, new ProjectService(repo),
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null);

        // 生成卷级摘要 [1-8]——应触发清理 [1-3] 和 [4-6]
        // 直接调 generateSummary via reflection or test through removeSupersededSummaries
        // 简单做法：手动调 generateSummary 触发清理
        java.lang.reflect.Method m = NovelOrchestrator.class.getDeclaredMethod(
                "removeSupersededSummaries", String.class, int.class, int.class);
        m.setAccessible(true);
        m.invoke(orchestrator, p.getId(), 1, 8);

        // 验证：[1-3] 和 [4-6] 应被删除，[10-12] 保留
        var remaining = repo.findSummariesByProject(p.getId());
        assertEquals(1, remaining.size(), "应只剩 [10-12]，实际: " +
                remaining.stream().map(s -> "[" + s.getFromChapter() + "-" + s.getToChapter() + "]").toList());
        assertEquals(10, remaining.get(0).getFromChapter());
    }
}
