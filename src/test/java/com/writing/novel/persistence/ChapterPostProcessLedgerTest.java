package com.writing.novel.persistence;

import com.writing.novel.orchestrator.ChapterPostProcessor;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.PostProcessLedgerRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * p20-03: 后处理阶段账本（ledger）持久化。
 *
 * 验收：
 * - 每项目/章节/阶段的 PENDING/RUNNING/COMPLETED 状态可往返（round-trip）
 * - 重复标记 COMPLETED 幂等且唯一（主键约束，不产生重复行）
 */
class ChapterPostProcessLedgerTest {

    @TempDir Path tempDir;
    private PostProcessLedgerRepository ledger;
    private final String pid = "proj-ledger";

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("ledger.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        ledger = new PostProcessLedgerRepository(cp);
    }

    @Test
    void 阶段状态PENDING到COMPLETED可往返() {
        var stage = ChapterPostProcessor.Stage.FACT_HARVEST;
        ledger.ensureStage(pid, 1, stage);
        assertEquals(PostProcessLedgerRepository.StageStatus.PENDING, ledger.statusOf(pid, 1, stage).orElse(null));

        ledger.markRunning(pid, 1, stage);
        assertEquals(PostProcessLedgerRepository.StageStatus.RUNNING, ledger.statusOf(pid, 1, stage).orElse(null));

        ledger.markCompleted(pid, 1, stage);
        assertEquals(PostProcessLedgerRepository.StageStatus.COMPLETED, ledger.statusOf(pid, 1, stage).orElse(null));
    }

    @Test
    void 重复COMPLETED幂等且行唯一() {
        var stage = ChapterPostProcessor.Stage.ENTITY_GRAPH;
        ledger.ensureStage(pid, 2, stage);
        ledger.markCompleted(pid, 2, stage);
        ledger.markCompleted(pid, 2, stage);
        ledger.markRunning(pid, 2, stage); // COMPLETED 后重复 RUNNING 也不得降级/新增行
        assertEquals(PostProcessLedgerRepository.StageStatus.COMPLETED, ledger.statusOf(pid, 2, stage).orElse(null));
        assertEquals(1, ledger.rowCount(pid, 2, stage), "同 project/chapter/stage 只允许一行");
    }

    @Test
    void 未完成阶段清单按序返回() {
        for (var stage : ChapterPostProcessor.Stage.values()) {
            ledger.ensureStage(pid, 3, stage);
        }
        ledger.markCompleted(pid, 3, ChapterPostProcessor.Stage.CONTINUITY);
        ledger.markCompleted(pid, 3, ChapterPostProcessor.Stage.ENTITY_GRAPH);
        ledger.markCompleted(pid, 3, ChapterPostProcessor.Stage.FACT_HARVEST);

        var unfinished = ledger.unfinishedStages(pid, 3);
        assertEquals(ChapterPostProcessor.Stage.values().length - 3, unfinished.size());
        assertEquals(ChapterPostProcessor.Stage.NARRATIVE_THREADS, unfinished.get(0),
                "未完成阶段必须按声明顺序返回（重启续跑按序执行）");
    }

    @Test
    void 不同章节账本互不干扰() {
        ledger.ensureStage(pid, 1, ChapterPostProcessor.Stage.DIVERSITY);
        ledger.markCompleted(pid, 1, ChapterPostProcessor.Stage.DIVERSITY);
        ledger.ensureStage(pid, 2, ChapterPostProcessor.Stage.DIVERSITY);
        assertEquals(PostProcessLedgerRepository.StageStatus.PENDING,
                ledger.statusOf(pid, 2, ChapterPostProcessor.Stage.DIVERSITY).orElse(null));
    }
}
