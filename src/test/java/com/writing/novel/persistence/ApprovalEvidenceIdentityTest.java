package com.writing.novel.persistence;

import com.writing.novel.domain.*;
import com.writing.novel.persistence.sqlite.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ApprovalEvidenceIdentityTest {
    @TempDir Path dir;
    SqliteNovelRepository repo;
    String pid;
    ChapterApproval.Identity identity;
    @BeforeEach void setup() {
        var cp = new ConnectionProvider(dir.resolve("evidence.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        var project = new Project("批准身份", "c", "t");
        project.setState(NovelState.PAUSED_AT_CHECKPOINT);
        pid = project.getId(); repo.saveProject(project);
        var ch = new Chapter(pid, 1, "第一章"); ch.setContent("真实正文");
        ch.setStatus(Chapter.Status.NEEDS_MANUAL_REVIEW); repo.saveChapter(ch);
        repo.saveReviewBarrier(new ReviewBarrier(pid, 1, ch.getVersion(), "复核"));
        identity = repo.readReviewContext(pid, 1).identity();
    }
    ChapterApproval.Request request() {
        var ctx = repo.readReviewContext(pid, 1);
        return new ChapterApproval.Request(ctx.identity(), ctx.reviewSetHash(), UUID.randomUUID().toString(), "确认");
    }
    void blocked() {
        assertThrows(IllegalStateException.class, () -> repo.approveChapter(request()));
        var ctx = repo.readReviewContext(pid, 1);
        assertNull(ctx.approval()); assertEquals(1, ctx.barrierIds().size());
        assertEquals(Chapter.Status.NEEDS_MANUAL_REVIEW, repo.findChapterByIndex(pid, 1).orElseThrow().getStatus());
        assertEquals(NovelState.PAUSED_AT_CHECKPOINT, repo.findProject(pid).orElseThrow().getState());
    }
    @ParameterizedTest @ValueSource(strings={"project","chapter","version","hash","defer"})
    void mismatchedDispositionCannotReleaseHighIssue(String wrong) {
        var issue = new ContinuityIssue(pid, 1, ContinuityIssue.Type.TIMELINE, ContinuityIssue.Severity.HIGH, "先后矛盾");
        repo.saveContinuityIssue(issue);
        String dispositionProject = wrong.equals("project") ? "another-project" : pid;
        var bad = new ChapterApproval.Identity(dispositionProject, wrong.equals("chapter") ? 2 : 1,
                wrong.equals("version") ? 999 : identity.version(), wrong.equals("hash") ? "fake" : identity.contentHash());
        repo.saveDisposition(new ReviewDisposition(UUID.randomUUID().toString(), dispositionProject, issue.getId(), "fp",
                bad, wrong.equals("defer") ? ReviewDisposition.Action.DEFER : ReviewDisposition.Action.EXCEPTION,
                "旧错误记录", 5, Instant.now()));
        blocked();
        repo.saveDisposition(new ReviewDisposition(UUID.randomUUID().toString(), pid, issue.getId(), "fp", identity,
                ReviewDisposition.Action.EXCEPTION, "当前稿复核", null, Instant.now()));
        assertNotNull(repo.approveChapter(request()).approvalId());
    }
    @ParameterizedTest @ValueSource(strings={"project","revision","chapter","version","hash","nullHash","status"})
    void mismatchedDirectiveEvidenceCannotSatisfyCurrentRequirement(String wrong) {
        var directive = new AuthorDirective(pid, AuthorDirective.Kind.HARD, 1, "保持事实");
        repo.saveAuthorDirective(directive);
        directive.setPrivateContent("补充要求"); repo.updateDirective(directive, 1);
        repo.saveDirectiveEvidence(wrong.equals("project") ? "another-project" : pid,
                new AuthorDirective.Evidence(UUID.randomUUID().toString(), directive.getId(), wrong.equals("revision") ? 1 : 2,
                        wrong.equals("chapter") ? 2 : 1, wrong.equals("version") ? 999 : identity.version(),
                        wrong.equals("hash") ? "fake" : wrong.equals("nullHash") ? null : identity.contentHash(),
                        null, "错误证据", null, wrong.equals("status") ? AuthorDirective.EvidenceStatus.STALE : AuthorDirective.EvidenceStatus.CONFIRMED, null));
        blocked();
        repo.saveDirectiveEvidence(pid, new AuthorDirective.Evidence(UUID.randomUUID().toString(), directive.getId(), 2,
                1, identity.version(), identity.contentHash(), null, "新要求的当前稿证据", null, AuthorDirective.EvidenceStatus.CONFIRMED, null));
        assertNotNull(repo.approveChapter(request()).approvalId());
    }
    @Test void appliedSystemEvidenceForCurrentPreferenceRemainsAccepted() {
        var d = new AuthorDirective(pid, AuthorDirective.Kind.PREFERENCE, 1, "偏好"); repo.saveAuthorDirective(d);
        repo.saveDirectiveEvidence(pid, new AuthorDirective.Evidence(UUID.randomUUID().toString(), d.getId(), 1,
                1, identity.version(), identity.contentHash(), null, "真实正文", null, AuthorDirective.EvidenceStatus.APPLIED_SYSTEM, null));
        assertNotNull(repo.approveChapter(request()).approvalId());
    }
}
