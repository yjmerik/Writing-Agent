package com.writing.novel.orchestrator;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Runtime regression replaces a source-text assertion that allowed log-only failures. */
class FailLoudTest extends GuardFindingRetentionTest {
    @Test
    void correctionFailureMustRaiseDurableBarrierAndRetainOriginalDraft() {
        failureMode = "correction-exception";
        var p = projectService.create("测", "剑修", "玄幻");
        orchestrator.runConceiving(p.getId());
        orchestrator.step(p.getId());
        orchestrator.runWritingOneChapter(p.getId());
        assertFalse(repository.findActiveReviewBarriers(p.getId()).isEmpty());
        assertTrue(repository.findChapterByIndex(p.getId(), 1).orElseThrow().getContent().contains("初稿"));
    }
}
