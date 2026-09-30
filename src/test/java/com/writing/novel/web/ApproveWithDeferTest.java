package com.writing.novel.web;

import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.ChapterApproval;
import com.writing.novel.domain.ContinuityIssue;
import com.writing.novel.domain.NovelState;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.ReviewDisposition;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-143（旅程三 F-9）：带簿放行——全部阻塞 HIGH 均有"有效暂挂"裁决
 * （DEFER 且 defer_until_chapter > 本章）时可批准，审计行标注 APPROVE_WITH_DEFER；
 * 任何一条完全无裁决、或暂挂已到期，仍阻塞。
 */
class ApproveWithDeferTest {

    @TempDir Path dir;
    ConnectionProvider cp;
    SqliteNovelRepository repo;
    Project project;

    @BeforeEach
    void setUp() {
        cp = new ConnectionProvider(dir.resolve("awd.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        project = new Project("书", "构思", "修真");
        project.setState(NovelState.WRITING);
        repo.saveProject(project);
        var ch = new Chapter(project.getId(), 1, "第一章");
        ch.setContent("带簿放行测试正文。".repeat(60));
        repo.saveChapter(ch);
    }

    private ContinuityIssue high(String desc) {
        var ctx = repo.readReviewContext(project.getId(), 1);
        var issue = new ContinuityIssue(project.getId(), 1,
                ContinuityIssue.Type.WORLDBUILDING, ContinuityIssue.Severity.HIGH, desc);
        // 必须绑定当前稿版本——旧版本问题按"过期"不阻塞批准（NCS-084）
        issue.setChapterVersion(ctx.identity().version());
        repo.saveContinuityIssue(issue);
        return issue;
    }

    private void defer(ContinuityIssue issue, int until) {
        var ctx = repo.readReviewContext(project.getId(), 1);
        repo.saveDisposition(new ReviewDisposition(UUID.randomUUID().toString(),
                project.getId(), issue.getId(), "fp-" + issue.getId().substring(0, 6),
                ctx.identity(), ReviewDisposition.Action.DEFER, "暂挂至卷末统一修订",
                until, java.time.Instant.now()));
    }

    private ChapterApproval.Result approve() {
        var ctx = repo.readReviewContext(project.getId(), 1);
        return repo.approveChapter(new ChapterApproval.Request(ctx.identity(), ctx.reviewSetHash(),
                UUID.randomUUID().toString(), "带簿放行验证"));
    }

    private String approvalReason() {
        try (Connection conn = cp.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT reason FROM chapter_approvals WHERE project_id=? ORDER BY created_at DESC LIMIT 1")) {
            ps.setString(1, project.getId());
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getString(1) : null;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void 全部阻塞HIGH均有有效暂挂时带簿放行并留审计() {
        defer(high("伏笔回收错位"), 6);
        defer(high("能力越界一段"), 6);
        var result = approve();
        assertFalse(result.replayed());
        assertTrue(approvalReason().contains("APPROVE_WITH_DEFER"),
                "审计行必须标注带簿放行: " + approvalReason());
        assertTrue(approvalReason().contains("2 条 HIGH 暂挂至第 6 章"));
    }

    @Test
    void 有一条完全无裁决仍阻塞() {
        defer(high("伏笔回收错位"), 6);
        high("能力越界一段");
        var e = assertThrows(IllegalStateException.class, this::approve);
        assertTrue(e.getMessage().contains("存在未解决的 HIGH 级问题"));
        assertTrue(e.getMessage().contains("能力越界一段"), "阻塞提示必须列出无裁决项");
        assertFalse(e.getMessage().contains("伏笔回收错位"), "已暂挂项不重复计入阻塞文案");
    }

    @Test
    void 暂挂已到期仍阻塞() {
        defer(high("伏笔回收错位"), 1); // deferUntil <= 本章
        var e = assertThrows(IllegalStateException.class, this::approve);
        assertTrue(e.getMessage().contains("存在未解决的 HIGH 级问题"));
    }
}
