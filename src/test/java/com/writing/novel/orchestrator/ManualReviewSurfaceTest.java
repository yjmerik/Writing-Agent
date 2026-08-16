package com.writing.novel.orchestrator;

import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.ContinuityIssue;
import com.writing.novel.domain.Project;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ManualReviewSurfaceTest {

    @TempDir Path tempDir;

    @Test
    void 质量报告暴露needsManualReview与qualityRisk() {
        var cp = new ConnectionProvider(tempDir.resolve("mrs.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        var repo = new SqliteNovelRepository(cp);
        Project p = new Project("t", "c", "tag");
        repo.saveProject(p);
        Chapter ch = new Chapter(p.getId(), 1, "第一章");
        ch.setContent("正文".repeat(80));
        ch.setStatus(Chapter.Status.NEEDS_MANUAL_REVIEW);
        repo.saveChapter(ch);
        repo.saveContinuityIssue(new ContinuityIssue(p.getId(), 1,
                ContinuityIssue.Type.STRUCTURE, ContinuityIssue.Severity.MEDIUM,
                "[quality_risk]ConceiveGate门禁未过：测试"));

        NovelOrchestrator orchestrator = new NovelOrchestrator(repo, new ProjectService(repo),
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                null, null, null, null);

        var report = orchestrator.buildChapterQualityReport(p.getId(), 1);
        assertTrue(report.isNeedsManualReview());
        assertTrue(report.isQualityRisk());
    }
}
