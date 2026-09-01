package com.writing.novel.orchestrator;

import com.writing.novel.agent.ConsistencyGuard;
import org.junit.jupiter.api.Test;
import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.ContinuityIssue;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * p20-01: 人工复核结果优先级。
 *
 * 修复前缺陷：checkContinuityAfterWriting（现 ChapterProduction.qualityGate，p22-04）
 * 内 applyRepairClassification 把章节标记为 NEEDS_MANUAL_REVIEW，但 doWriteOneChapter
 * 末尾无条件 setStatus(DONE) 覆盖了人工复核标记；且下一章写作不检查前章状态。
 */
class ManualReviewLifecycleTest extends ManualReviewLifecycleTestHarnessSupport {

    /** 恒定返回 HIGH CHARACTER 矛盾的一致性守卫——稳定触发重写后仍存 → MANUAL_REVIEW 仲裁 */
    static class AlwaysHighGuard extends ConsistencyGuard {
        AlwaysHighGuard() {
            super(null, null, null);
        }
        @Override
        public List<ContinuityIssue> check(String projectId, int chapterIndex, String chapterContent, List<String> cast) {
            return List.of(new ContinuityIssue(projectId, chapterIndex,
                    ContinuityIssue.Type.CHARACTER, ContinuityIssue.Severity.HIGH,
                    "测试注入：根源性人物矛盾"));
        }
    }

    @Override
    protected ConsistencyGuard guardOverride() {
        return new AlwaysHighGuard();
    }

    @Test
    void 人工复核标记不被后处理完成覆盖() {
        String pid = seedProject().getId();
        Chapter ch = orchestrator.runWritingOneChapter(pid);
        assertNotNull(ch, "章节应写成");
        assertEquals(Chapter.Status.NEEDS_MANUAL_REVIEW, ch.getStatus(),
                "MANUAL_REVIEW 仲裁后章节必须保持 NEEDS_MANUAL_REVIEW，不得被末尾 DONE 覆盖");
    }

    @Test
    void 待人工章节未处理前拒绝写下一章() {
        String pid = seedProject().getId();
        orchestrator.runWritingOneChapter(pid);
        assertEquals(Chapter.Status.NEEDS_MANUAL_REVIEW,
                repo.findChapterByIndex(pid, 1).orElseThrow().getStatus());

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> orchestrator.runWritingOneChapter(pid),
                "前章待人工复核时写下一章必须被拒绝");
        assertTrue(ex.getMessage().contains("人工复核") || ex.getMessage().contains("人工"));
    }

    @Test
    void 连续写作循环在待人工章节处停下() {
        String pid = seedProject().getId();
        int written = orchestrator.runWritingLoop(pid, 3);
        assertEquals(1, written, "第1章待人工复核后循环必须停止，不得继续写第2章");
        assertTrue(repo.findChapterByIndex(pid, 2).isEmpty(), "不得越过待人工章节写后续章节");
        assertEquals(1, repo.findProject(pid).orElseThrow().getCurrentChapterIndex());
    }

    @Test
    void 人工复核通过后写作继续() {
        String pid = seedProject().getId();
        orchestrator.runWritingOneChapter(pid);
        assertEquals(Chapter.Status.NEEDS_MANUAL_REVIEW,
                repo.findChapterByIndex(pid, 1).orElseThrow().getStatus());

        // 人工复核通过（等价 resolve-review 端点行为：NEEDS_MANUAL_REVIEW → DONE）
        Chapter resolved = repo.findChapterByIndex(pid, 1).orElseThrow();
        resolved.setStatus(Chapter.Status.DONE);
        repo.saveChapter(resolved);

        Chapter next = orchestrator.runWritingOneChapter(pid);
        assertNotNull(next, "复核通过后应可继续写下一章");
        assertEquals(2, next.getChapterIndex());
    }
}
