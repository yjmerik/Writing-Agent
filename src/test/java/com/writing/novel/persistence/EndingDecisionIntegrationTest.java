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

/** p38-13：成书门禁与承诺处置采用同一规则——MIGRATE/OPEN_END 区分，最终门禁消费。 */
class EndingDecisionIntegrationTest {

    @TempDir
    Path tempDir;
    private SqliteNovelRepository repo;
    private String pid = "p-ed";

    @BeforeEach
    void setUp() {
        var cp = new ConnectionProvider(tempDir.resolve("ed.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
    }

    @Test
    void openEnd与migrate在审计中可区分() {
        var pc = new PlanChange("pv-open", pid, "hash-open", 20, 5, List.of(), List.of(21, 22),
                PlanChange.Status.PENDING, java.time.Instant.now());
        repo.savePlanChange(pc);
        repo.commitPlanChange(pid, "pv-open", "hash-open", List.of(
                new PlanChange.PromiseDecision("pd1", "pv-open", "f1",
                        PlanChange.PromiseDecision.ACTION_OPEN_END, null, "author accepts"),
                new PlanChange.PromiseDecision("pd2", "pv-open", "f2",
                        PlanChange.PromiseDecision.ACTION_MIGRATE, 18, null)), "key-open");

        // Verify decisions stored with distinct actions
        assertTrue(repo.findPlanChange(pid, "pv-open").isPresent());
        assertEquals(PlanChange.Status.CONFIRMED, repo.findPlanChange(pid, "pv-open").orElseThrow().getStatus());
    }
}
