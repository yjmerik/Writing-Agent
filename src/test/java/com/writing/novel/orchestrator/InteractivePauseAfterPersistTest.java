package com.writing.novel.orchestrator;

import com.writing.novel.agent.FactHarvesterAgent;
import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.Checkpoint;
import com.writing.novel.domain.NovelState;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.ProjectMode;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * p14-03: INTERACTIVE pause must be visible as soon as the chapter is persisted,
 * even while harvest is still running.
 */
class InteractivePauseAfterPersistTest extends NovelOrchestratorWritingTest {

    @Override
    protected OrchestratorPolicies policiesOverride() {
        // p22-05: 慢收割桩改为构造期策略注入（原后置 setter）
        return new OrchestratorPolicies(null, null, null, null, null, null, null,
                new FactHarvesterAgent(null, null, null) {
                    @Override
                    public HarvestResult harvest(String projectId, Chapter chapter) {
                        try { Thread.sleep(1500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                        return new HarvestResult(0, 0, 0);
                    }
                }, null, null, null);
    }

    @Test
    void interactiveWrite_pausesBeforeSlowHarvestFinishes() throws Exception {
        Project p = projectService.create("t", "c", "tag", ProjectMode.INTERACTIVE);
        orchestrator.runConceiving(p.getId());
        orchestrator.step(p.getId()); // resume AFTER_INITIAL_SETUP -> PLANNING
        orchestrator.step(p.getId()); // PLANNING -> WRITING

        AtomicReference<NovelState> mid = new AtomicReference<>();
        Thread poller = new Thread(() -> {
            try {
                Thread.sleep(200);
                mid.set(repo.findProject(p.getId()).orElseThrow().getState());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        poller.start();
        orchestrator.runWritingOneChapter(p.getId());
        poller.join();

        assertEquals(NovelState.PAUSED_AT_CHECKPOINT, repo.findProject(p.getId()).orElseThrow().getState());
        assertTrue(repo.findPendingCheckpoints(p.getId()).stream()
                .anyMatch(c -> c.getType() == Checkpoint.Type.AFTER_CHAPTER));
        assertEquals(NovelState.PAUSED_AT_CHECKPOINT, mid.get(),
                "status during harvest should already be paused");
    }
}
