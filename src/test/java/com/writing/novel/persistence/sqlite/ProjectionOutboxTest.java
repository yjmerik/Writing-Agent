package com.writing.novel.persistence.sqlite;

import com.writing.novel.orchestrator.ChapterPostProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-041: 派生任务持久模型——(来源章稿, 投影类型) 唯一；来源稿版本升级重新武装；
 * 重启（重开连接）保持待执行项；旧数据迁移（safeAddColumn）可重复。
 */
class ProjectionOutboxTest {

    @TempDir
    Path tempDir;
    private Path db;

    private PostProcessLedgerRepository open() {
        new SqliteSchemaInitializer(new ConnectionProvider(db.toString())).initialize();
        return new PostProcessLedgerRepository(new ConnectionProvider(db.toString()));
    }

    @Test
    void 来源稿版本升级重新武装待执行项() {
        db = tempDir.resolve("o1.db");
        var ledger = open();
        ledger.ensureStage("p1", 1, ChapterPostProcessor.Stage.CONTINUITY, 1);
        ledger.markRunning("p1", 1, ChapterPostProcessor.Stage.CONTINUITY);
        // 同版本不重新武装（RUNNING 保持）
        ledger.ensureStage("p1", 1, ChapterPostProcessor.Stage.CONTINUITY, 1);
        assertEquals("RUNNING", ledger.pendingTasks("p1", 1).get(0).get("status"));
        // 新稿（v2）重新武装为 PENDING 并绑定 v2
        ledger.ensureStage("p1", 1, ChapterPostProcessor.Stage.CONTINUITY, 2);
        var pending = ledger.pendingTasks("p1", 1);
        assertEquals(1, pending.size(), "(来源章稿,投影类型) 唯一——不产生重复行");
        assertEquals("PENDING", pending.get(0).get("status"));
        assertEquals(2, pending.get(0).get("sourceVersion"));
    }

    @Test
    void 重启保持待执行项_迁移可重复() {
        db = tempDir.resolve("o2.db");
        var first = open();
        first.ensureStage("p1", 2, ChapterPostProcessor.Stage.ENTITY_GRAPH, 3);
        first.failStage("p1", 2, ChapterPostProcessor.Stage.ENTITY_GRAPH);
        // 重启：重开连接（新仓库实例）
        var reopened = open();
        var pending = reopened.pendingTasks("p1", 2);
        assertEquals(1, pending.size(), "重启后待执行项仍在");
        assertEquals(1, pending.get(0).get("attempts"), "重试计数持久");
        // 旧数据迁移可重复：初始化器跑两遍不炸
        new SqliteSchemaInitializer(new ConnectionProvider(db.toString())).initialize();
        new SqliteSchemaInitializer(new ConnectionProvider(db.toString())).initialize();
        assertEquals(1, reopened.pendingTasks("p1", 2).size(), "迁移后待执行项不丢");
    }
}
