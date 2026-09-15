package com.writing.novel.persistence;

import com.writing.novel.domain.PlanChange;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** p38-02：计划变更持久存取——save/find/commit，planHash 验证，决策审计。 */
class PlanChangeRepositoryTest {

    @TempDir
    Path tempDir;
    private ConnectionProvider cp;
    private SqliteNovelRepository repo;

    private PlanChange planChange(String pid, String previewId, String hash) {
        return new PlanChange(previewId, pid, hash, 20, 3, List.of(), List.of(21, 22),
                PlanChange.Status.PENDING, java.time.Instant.now());
    }

    @BeforeEach
    void setUp() {
        cp = new ConnectionProvider(tempDir.resolve("pc.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
    }

    @Test
    void save_find_持久候选() {
        PlanChange pc = planChange("p1", "pv-1", "hash-abc");
        repo.savePlanChange(pc);
        assertTrue(repo.findPlanChange("p1", "pv-1").isPresent());
        assertEquals("hash-abc", repo.findPlanChange("p1", "pv-1").orElseThrow().getSourcePlanHash());
    }

    @Test
    void commit验证planHash和状态() {
        PlanChange pc = planChange("p1", "pv-commit", "hash-ok");
        repo.savePlanChange(pc);
        // 错误 planHash → 拒绝
        assertThrows(IllegalStateException.class, () -> repo.commitPlanChange(
                "p1", "pv-commit", "wrong-hash", List.of(), "key"));
        // 正确 → CONFIRMED
        PlanChange confirmed = repo.commitPlanChange("p1", "pv-commit", "hash-ok",
                List.of(new PlanChange.PromiseDecision("pd1", "pv-commit", "f1",
                        PlanChange.PromiseDecision.ACTION_MIGRATE, 18, null)), "key");
        assertEquals(PlanChange.Status.CONFIRMED, confirmed.getStatus());
        // 已确认再提交 → 拒绝
        assertThrows(IllegalStateException.class, () -> repo.commitPlanChange(
                "p1", "pv-commit", "hash-ok", List.of(), "key2"));
    }
}
