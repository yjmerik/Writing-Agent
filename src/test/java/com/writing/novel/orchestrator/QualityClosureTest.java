package com.writing.novel.orchestrator;

import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.WritingPreferences;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Real orchestrator + SQLite; only the external model is replaced. */
class QualityClosureTest extends GuardFindingRetentionTest {
    @ParameterizedTest
    @ValueSource(strings={"guard-exception", "guard-invalid", "continuity-invalid",
            "correction-exception", "correction-empty", "recheck-exception", "reader-invalid",
            "reader-recheck-low", "missing-finding", "duplicate-finding", "wrong-version",
            "invented-evidence", "unresolved", "unknown", "rule-high", "rule-exception"})
    void failedOrUnprovenQualityMustPauseWithoutLosingDraft(String mode) {
        failureMode = mode;
        var p = projectService.create("测", "剑修", "玄幻");
        orchestrator.runConceiving(p.getId());
        orchestrator.step(p.getId());
        orchestrator.runWritingOneChapter(p.getId());
        var saved = repository.findChapterByIndex(p.getId(), 1).orElseThrow();
        assertFalse(saved.getContent().isBlank());
        assertFalse(repository.findActiveReviewBarriers(p.getId()).isEmpty(), mode);
        assertEquals(Chapter.Status.NEEDS_MANUAL_REVIEW, saved.getStatus(), mode);
        assertEquals(0, orchestrator.runWritingLoop(p.getId(), 1));
        assertTrue(repository.findChapterByIndex(p.getId(), 2).isEmpty());
        if (mode.startsWith("correction-")) assertTrue(saved.getContent().contains("初稿"));
    }

    @Test
    void everyOriginalHighFindingRequiresVersionBoundEvidenceBeforeResolution() {
        var p = projectService.create("测", "剑修", "玄幻");
        orchestrator.runConceiving(p.getId());
        orchestrator.step(p.getId());
        orchestrator.runWritingOneChapter(p.getId());
        assertTrue(checkResults.latestStatuses(p.getId()).stream().anyMatch(c ->
                c.checker().equals("correction-verification") && c.reason().contains("未出示先使用的官印")
                        && c.reason().contains("先出示官印，再作判断。")), "逐项证据应随检查记录保存");
        assertEquals(1, verificationCalls.get(), "新的空扫描不能替代原问题逐项复验");
        assertTrue(repository.findUnresolvedIssues(p.getId()).stream().noneMatch(i ->
                i.getDescription().contains("未出示先使用的官印")));
        assertTrue(repository.findActiveReviewBarriers(p.getId()).isEmpty());
    }

    @Test
    void noRewriteBudgetDoesNotPermitHighGuardFinding() {
        var p = projectService.create("测", "剑修", "玄幻");
        orchestrator.runConceiving(p.getId());
        orchestrator.step(p.getId());
        var preferences = new WritingPreferences(p.getId());
        preferences.setRewriteBudget(0);
        repository.saveWritingPreferences(preferences);
        orchestrator.runWritingOneChapter(p.getId());
        assertFalse(repository.findActiveReviewBarriers(p.getId()).isEmpty());
        assertTrue(repository.findChapterByIndex(p.getId(), 1).orElseThrow().getContent().contains("初稿"));
    }
}
