package com.writing.novel.orchestrator;

import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.ChapterQualityReport;
import com.writing.novel.domain.ChapterReview;
import com.writing.novel.domain.ChapterVersion;
import com.writing.novel.domain.ContinuityIssue;
import com.writing.novel.domain.Project;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * p32-05（体验报告 #3/P1）：审核产物绑定章节版本。
 * 问题/评语在落库时持久化产生时点的章节版本与正文指纹；
 * 章节升版（作者改稿/重写）后，旧条目在质量报告中标记过期，新条目为当前。
 */
class ReviewVersionBindingTest {

    @TempDir
    Path tempDir;
    private SqliteNovelRepository repo;
    private NovelOrchestrator orchestrator;
    private String projectId;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("rvb.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        orchestrator = new NovelOrchestrator(repo, new ProjectService(repo),
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null);
        Project p = new Project("版本绑定", "概念", "现实");
        repo.saveProject(p);
        projectId = p.getId();
        Chapter chapter = new Chapter(projectId, 1, "第一章");
        chapter.setContent("初稿正文。".repeat(100));
        repo.saveChapter(chapter);
    }

    private int currentVersion() {
        return repo.findChapterByIndex(projectId, 1).orElseThrow().getVersion();
    }

    /** 模拟作者保存链路产生的版本推进（CAS + 快照） */
    private void bumpChapterVersion(String newContent) {
        Chapter current = repo.findChapterByIndex(projectId, 1).orElseThrow();
        ChapterVersion snapshot = new ChapterVersion(UUID.randomUUID().toString(), projectId, 1,
                current.getVersion(), current.getTitle(), current.getContent(),
                "DIRECT_EDIT", Instant.now());
        current.setContent(newContent);
        assertTrue(repo.saveChapterRevision(snapshot, current, snapshot.getVersion()));
    }

    @Test
    void 问题与评语落库时绑定当前章节版本与正文指纹() {
        repo.saveContinuityIssue(new ContinuityIssue(projectId, 1,
                ContinuityIssue.Type.TIMELINE, ContinuityIssue.Severity.HIGH, "时间线矛盾"));
        ChapterReview review = new ChapterReview(projectId, 1);
        review.setSummary("评语");
        repo.saveChapterReview(review);

        ContinuityIssue stored = repo.findContinuityIssuesByProject(projectId).get(0);
        assertEquals(currentVersion(), stored.getChapterVersion(), "问题应绑定当前章节版本");
        assertNotNull(stored.getContentHash(), "问题应绑定正文指纹");

        ChapterReview storedReview = repo.findChapterReview(projectId, 1).orElseThrow();
        assertEquals(currentVersion(), storedReview.getChapterVersion());
        assertEquals(stored.getContentHash(), storedReview.getContentHash(), "同一正文指纹一致");
    }

    @Test
    void 章节升版后旧条目在质量报告标记过期新条目为当前() {
        repo.saveContinuityIssue(new ContinuityIssue(projectId, 1,
                ContinuityIssue.Type.TIMELINE, ContinuityIssue.Severity.HIGH, "旧问题：父亲刻痕场景"));
        ChapterReview oldReview = new ChapterReview(projectId, 1);
        oldReview.setSummary("旧评语：赞扬父亲刻痕");
        oldReview.setOverallScore(9);
        repo.saveChapterReview(oldReview);

        // 作者删除被引用场景 → 版本推进
        bumpChapterVersion("作者删场景后的新稿。".repeat(100));

        repo.saveContinuityIssue(new ContinuityIssue(projectId, 1,
                ContinuityIssue.Type.PLOT, ContinuityIssue.Severity.MEDIUM, "新问题：当章发现"));

        ChapterQualityReport report = orchestrator.buildChapterQualityReport(projectId, 1);
        assertEquals(currentVersion(), report.getChapterVersion());

        var oldIssue = report.getIssues().stream()
                .filter(i -> i.getDescription().contains("旧问题")).findFirst().orElseThrow();
        var newIssue = report.getIssues().stream()
                .filter(i -> i.getDescription().contains("新问题")).findFirst().orElseThrow();
        assertTrue(oldIssue.isStale(), "升版前的旧问题必须标记过期");
        assertFalse(newIssue.isStale(), "升版后的新问题应为当前");
        assertEquals(1, report.getStaleIssueCount());
        assertTrue(report.isReviewStale(), "指向旧正文的评语必须标记过期");
        assertTrue(report.getReview().isStale());
    }

    @Test
    void 同版本条目不误报过期且未知版本条目保持当前() {
        repo.saveContinuityIssue(new ContinuityIssue(projectId, 1,
                ContinuityIssue.Type.PLOT, ContinuityIssue.Severity.LOW, "当前问题"));

        ChapterQualityReport report = orchestrator.buildChapterQualityReport(projectId, 1);
        assertFalse(report.getIssues().get(0).isStale());
        assertEquals(0, report.getStaleIssueCount());

        // 无对应章节的版本未知条目（如规划期 quality_risk，detectedAtChapter=0 不进本章报告）
        repo.saveContinuityIssue(new ContinuityIssue(projectId, 0,
                ContinuityIssue.Type.STRUCTURE, ContinuityIssue.Severity.LOW, "[quality_risk] 规划"));
        report = orchestrator.buildChapterQualityReport(projectId, 1);
        assertTrue(report.getIssues().stream().noneMatch(i -> i.getDescription().contains("规划")));
    }
}
