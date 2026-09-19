package com.writing.novel.service;

import com.writing.novel.agent.CheckResultEnvelope;
import com.writing.novel.domain.*;
import com.writing.novel.persistence.sqlite.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-023（方案 §13/UX09）：三类信息分层——必须处理 / 尚未查清 / 文学建议。
 */
class ReviewClassificationTest {

    @TempDir
    Path tempDir;

    private SqliteNovelRepository repo;
    private CheckResultRepository checkRepo;
    private ReviewClassificationService service;
    private String projectId;

    @BeforeEach
    void setUp() {
        var cp = new ConnectionProvider(tempDir.resolve("classes.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        checkRepo = new CheckResultRepository(cp);
        service = new ReviewClassificationService(repo, checkRepo);
        Project project = new Project("分层项目", "构思", "武侠");
        repo.saveProject(project);
        projectId = project.getId();
    }

    @Test
    void 三类分列_严重与屏障入必须处理_未知检查入尚未查清_轻微入建议() {
        Chapter ch = new Chapter(projectId, 8, "第八章");
        ch.setContent("正文。".repeat(60));
        ch.setStatus(Chapter.Status.DONE);
        repo.saveChapter(ch);

        ContinuityIssue high = new ContinuityIssue(projectId, 8,
                ContinuityIssue.Type.TIMELINE, ContinuityIssue.Severity.HIGH, "密信已毁但后文展示原件");
        high.setChapterVersion(1);
        repo.saveContinuityIssue(high);
        ContinuityIssue low = new ContinuityIssue(projectId, 8,
                ContinuityIssue.Type.PACING, ContinuityIssue.Severity.LOW, "该章后半节奏稍缓");
        repo.saveContinuityIssue(low);
        checkRepo.save(projectId, 9, "continuity", CheckResultEnvelope.Status.UNKNOWN, "空响应");
        repo.saveReviewBarrier(new ReviewBarrier(projectId, 8, 2, "质量门禁"));
        // 第 8 章推进到 v2 → HIGH 绑定 v1 为过期
        ch.setContent("修订后的正文。".repeat(60));
        repo.saveChapter(ch);

        var result = service.classify(projectId);

        var must = (java.util.List<?>) result.get("mustFix");
        var unknown = (java.util.List<?>) result.get("unknown");
        var suggestions = (java.util.List<?>) result.get("suggestions");
        assertEquals(2, must.size(), "HIGH 问题 + 人审屏障 → 必须处理");
        assertTrue(must.stream().anyMatch(c -> String.valueOf(((java.util.Map<?, ?>) c).get("description")).contains("密信已毁")));
        var highCard = must.stream().map(c -> (java.util.Map<?, ?>) c)
                .filter(c -> "ISSUE".equals(c.get("kind"))).findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, highCard.get("stale"), "问题卡标注过期（绑定版本 < 当前）");
        assertEquals(1, unknown.size(), "UNKNOWN 检查 → 尚未查清（不当作无问题）");
        assertTrue(String.valueOf(((java.util.Map<?, ?>) unknown.get(0)).get("description")).contains("不能当作无问题"));
        assertEquals(1, suggestions.size(), "LOW → 文学建议");
    }

    @Test
    void 无问题时三列为空_不显示为零问题误导() {
        var result = service.classify(projectId);
        assertTrue(((java.util.List<?>) result.get("mustFix")).isEmpty());
        assertTrue(((java.util.List<?>) result.get("unknown")).isEmpty());
        assertTrue(((java.util.List<?>) result.get("suggestions")).isEmpty());
    }
}
