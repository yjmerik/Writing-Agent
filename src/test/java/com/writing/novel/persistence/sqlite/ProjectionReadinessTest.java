package com.writing.novel.persistence.sqlite;

import com.writing.novel.orchestrator.ChapterPostProcessor;

import com.writing.novel.orchestrator.ChapterPostProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-043: 失败持久待重试；旧来源不得完成新稿；重启能重建全部必需投影。
 */
class ProjectionReadinessTest {

    @TempDir
    Path tempDir;
    private PostProcessLedgerRepository ledger;

    @BeforeEach
    void setUp() {
        var cp = new ConnectionProvider(tempDir.resolve("pr.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        ledger = new PostProcessLedgerRepository(cp);
    }

    @Test
    void 失败持久待重试() {
        ledger.ensureStage("p1", 1, ChapterPostProcessor.Stage.CONTINUITY, 2);
        ledger.markRunning("p1", 1, ChapterPostProcessor.Stage.CONTINUITY);
        ledger.failStage("p1", 1, ChapterPostProcessor.Stage.CONTINUITY);
        var pending = ledger.pendingTasks("p1", 1);
        assertEquals("PENDING", pending.get(0).get("status"), "失败回 PENDING（持久待重试）");
        assertEquals(1, pending.get(0).get("attempts"));
    }

    @Test
    void 旧来源不得完成新稿() {
        ledger.ensureStage("p1", 1, ChapterPostProcessor.Stage.CONTINUITY, 2);
        assertFalse(ledger.markCompletedForVersion("p1", 1,
                ChapterPostProcessor.Stage.CONTINUITY, 1), "v1 完成标记不得命中 v2 账目");
        assertEquals("PENDING", ledger.pendingTasks("p1", 1).get(0).get("status"));
        assertTrue(ledger.markCompletedForVersion("p1", 1,
                ChapterPostProcessor.Stage.CONTINUITY, 2), "同版本完成标记命中");
        assertTrue(ledger.pendingTasks("p1", 1).isEmpty(), "完成后无待执行项");
    }

    @Test
    void 重启能重建全部必需投影() {
        ledger.ensureStage("p1", 3, ChapterPostProcessor.Stage.CONTINUITY, 1);
        ledger.ensureStage("p1", 3, ChapterPostProcessor.Stage.ENTITY_GRAPH, 1);
        ledger.markCompleted("p1", 3, ChapterPostProcessor.Stage.CONTINUITY);
        // 重启（重开连接）：未完成阶段清单驱动续跑（resumeAllInterruptedPostProcessing 消费同一查询）
        var reopened = new PostProcessLedgerRepository(new ConnectionProvider(
                tempDir.resolve("pr.db").toString()));
        assertEquals(List.of(ChapterPostProcessor.Stage.ENTITY_GRAPH),
                reopened.unfinishedStages("p1", 3), "重启后仅剩未完成投影待重建");
    }
}
