package com.writing.novel.persistence;

import com.writing.novel.domain.AuthorDirective;
import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.ChapterApproval;
import com.writing.novel.domain.Project;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * p37-07：意见与证据版本化仓储。
 * 相同证据幂等；确认在事务内重读章/意见版本（旧稿、UNMET、意见修订过期均拒绝）；
 * 跨项目不可操作；HARD 某章确认后下一章仍适用，ONCE 确认后不再适用；
 * updateDirective CAS 升 revision 使旧证据失效；撤销留审计。
 */
class DirectiveEvidenceRepositoryTest {

    @TempDir
    Path tempDir;

    private ConnectionProvider cp;
    private SqliteNovelRepository repo;
    private String projectId;
    private AuthorDirective hard;

    private static final String C2 = "待复核正文，长度满足已完成章节口径要求。".repeat(30);
    private static final String C3 = "修订后的正文，同样满足完成章节口径长度。".repeat(30);

    @BeforeEach
    void setUp() {
        cp = new ConnectionProvider(tempDir.resolve("directive.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        Project project = new Project("意见仓储项目", "概念", "武侠");
        repo.saveProject(project);
        projectId = project.getId();
        Chapter chapter = new Chapter(projectId, 4, "第四章");
        chapter.setContent(C2);
        chapter.setStatus(Chapter.Status.DONE);
        repo.saveChapter(chapter);

        hard = new AuthorDirective(projectId, AuthorDirective.Kind.HARD, null, "每章至少一场雨夜对话");
        repo.saveAuthorDirective(hard);
    }

    private AuthorDirective.Evidence systemEvidence(int chapterVersion, String contentHash) {
        return new AuthorDirective.Evidence(UUID_NAME(), hard.getId(), 1, 4, chapterVersion, contentHash,
                null, "雨夜对话原文", "p:4", AuthorDirective.EvidenceStatus.APPLIED_SYSTEM, null);
    }

    private String UUID_NAME() {
        return java.util.UUID.randomUUID().toString();
    }

    private ChapterApproval.Identity identityV2() {
        return new ChapterApproval.Identity(projectId, 4, 2,
                ChapterApproval.contentHash("第四章", C2));
    }

    // --- 契约断言 ---

    @Test
    void 相同证据幂等且多章证据并存() {
        AuthorDirective.Evidence first = repo.saveDirectiveEvidence(projectId,
                systemEvidence(2, identityV2().contentHash()));
        AuthorDirective.Evidence second = repo.saveDirectiveEvidence(projectId,
                new AuthorDirective.Evidence(UUID_NAME(), hard.getId(), 1, 4, 2,
                        identityV2().contentHash(), null, "雨夜对话原文", "p:4",
                        AuthorDirective.EvidenceStatus.APPLIED_SYSTEM, null));
        assertEquals(first.id(), second.id(), "相同证据幂等返回原行");
        // 不同章证据并存
        repo.saveDirectiveEvidence(projectId, new AuthorDirective.Evidence(UUID_NAME(), hard.getId(), 1,
                5, 1, "other-hash", null, null, null, AuthorDirective.EvidenceStatus.UNMET, "第5章未出现"));
        assertEquals(2, evidenceCount(), "两章证据并存");
    }

    private int evidenceCount() {
        try (var conn = cp.getConnection(); var st = conn.createStatement();
             var rs = st.executeQuery("SELECT COUNT(*) FROM directive_evidence WHERE project_id='" + projectId + "'")) {
            rs.next();
            return rs.getInt(1);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void 确认成功要求当前稿与意见版本一致() {
        AuthorDirective.Evidence evidence = repo.saveDirectiveEvidence(projectId,
                systemEvidence(2, identityV2().contentHash()));
        AuthorDirective.Evidence confirmed = repo.confirmDirectiveEvidence(
                projectId, evidence.id(), identityV2(), 1);
        assertEquals(AuthorDirective.EvidenceStatus.CONFIRMED, confirmed.status());
    }

    @Test
    void 旧稿证据不能确认() throws Exception {
        AuthorDirective.Evidence evidence = repo.saveDirectiveEvidence(projectId,
                systemEvidence(2, identityV2().contentHash()));
        // 当前稿已推进 v3（旧稿证据对 v3 无效）
        Chapter current = repo.findChapterByIndex(projectId, 4).orElseThrow();
        Chapter revised = new Chapter(projectId, 4, "第四章");
        try {
            java.lang.reflect.Field fId = Chapter.class.getDeclaredField("id");
            fId.setAccessible(true);
            fId.set(revised, current.getId());
        } catch (Exception e) { throw new IllegalStateException(e); }
        revised.setContent(C3);
        repo.saveChapter(revised);

        // 作者读的是 v3：证据绑定的 v2 不再可确认
        ChapterApproval.Identity v3Identity = ChapterApproval.Identity.of(
                repo.findChapterByIndex(projectId, 4).orElseThrow());
        assertThrows(IllegalStateException.class, () -> repo.confirmDirectiveEvidence(
                        projectId, evidence.id(), v3Identity, 1),
                "旧稿证据不能确认");
        // 作者声称读 v2 但当前已是 v3：同样拒绝（事务内重读当前章）
        assertThrows(IllegalStateException.class, () -> repo.confirmDirectiveEvidence(
                        projectId, evidence.id(), identityV2(), 1),
                "过期身份不能确认");
    }

    @Test
    void 意见revision过期后旧证据无效() {
        AuthorDirective.Evidence evidence = repo.saveDirectiveEvidence(projectId,
                systemEvidence(2, identityV2().contentHash()));
        // 意见内容修订：revision 1 → 2
        AuthorDirective updated = new AuthorDirective(hard.getId(), projectId, hard.getKind(),
                hard.getChapterIndex(), "每章至少一场雨夜对话，且对白不少于三句", hard.getStatus(),
                hard.getSystemEvidence(), hard.getSystemEvidenceVersion(),
                hard.getCreatedAt(), hard.getConfirmedAt(), hard.getUpdatedAt());
        repo.updateDirective(updated, 1);
        assertThrows(IllegalStateException.class, () -> repo.confirmDirectiveEvidence(
                        projectId, evidence.id(), identityV2(), 1),
                "意见修订过期后旧证据不能确认");
        // 新 revision 下重新核对后的证据可确认
        AuthorDirective.Evidence fresh = repo.saveDirectiveEvidence(projectId,
                new AuthorDirective.Evidence(UUID_NAME(), hard.getId(), 2, 4, 2,
                        identityV2().contentHash(), null, "新证据", "p:4",
                        AuthorDirective.EvidenceStatus.APPLIED_SYSTEM, null));
        assertEquals(AuthorDirective.EvidenceStatus.CONFIRMED,
                repo.confirmDirectiveEvidence(projectId, fresh.id(), identityV2(), 2).status());
    }

    @Test
    void 跨项目与跨章伪证据拒绝() {
        AuthorDirective.Evidence evidence = repo.saveDirectiveEvidence(projectId,
                systemEvidence(2, identityV2().contentHash()));
        // 跨项目
        assertThrows(Exception.class, () -> repo.confirmDirectiveEvidence(
                "other-project", evidence.id(), identityV2(), 1), "跨项目证据不可确认");
        // 跨章：作者读的身份是第 9 章
        ChapterApproval.Identity otherChapter = new ChapterApproval.Identity(
                projectId, 9, 2, identityV2().contentHash());
        assertThrows(IllegalStateException.class, () -> repo.confirmDirectiveEvidence(
                        projectId, evidence.id(), otherChapter, 1),
                "章号不一致的伪证据拒绝");
        // UNMET 不能确认
        AuthorDirective.Evidence unmet = repo.saveDirectiveEvidence(projectId,
                new AuthorDirective.Evidence(UUID_NAME(), hard.getId(), 1, 6, 1, "h",
                        null, null, null, AuthorDirective.EvidenceStatus.UNMET, "未落实"));
        assertThrows(IllegalStateException.class, () -> repo.confirmDirectiveEvidence(
                        projectId, unmet.id(), identityV2(), 1), "UNMET 不能确认");
    }

    @Test
    void HARD某章确认后下一章仍适用_ONCE确认后不再适用() {
        AuthorDirective.Evidence saved = repo.saveDirectiveEvidence(projectId,
                systemEvidence(2, identityV2().contentHash()));
        // HARD：确认第4章后第5章仍适用
        AuthorDirective.Evidence confirmed = repo.confirmDirectiveEvidence(
                projectId, saved.id(), identityV2(), 1);
        assertEquals(AuthorDirective.EvidenceStatus.CONFIRMED, confirmed.status());
        List<AuthorDirective> applicableAt5 = repo.findApplicableDirectives(projectId, 5);
        assertTrue(applicableAt5.stream().anyMatch(d -> d.getId().equals(hard.getId())),
                "HARD 确认某章后下一章仍适用");
        // ONCE：确认后不再适用
        AuthorDirective once = new AuthorDirective(projectId, AuthorDirective.Kind.ONCE, 4, "本章增加伏笔");
        repo.saveAuthorDirective(once);
        AuthorDirective.Evidence onceEvidence = repo.saveDirectiveEvidence(projectId,
                new AuthorDirective.Evidence(UUID_NAME(), once.getId(), 1, 4, 2,
                        identityV2().contentHash(), null, "q", "p:4",
                        AuthorDirective.EvidenceStatus.APPLIED_SYSTEM, null));
        repo.confirmDirectiveEvidence(projectId, onceEvidence.id(), identityV2(), 1);
        assertTrue(repo.findApplicableDirectives(projectId, 5).stream()
                        .noneMatch(d -> d.getId().equals(once.getId())),
                "ONCE 确认后不再适用");
        assertTrue(repo.findApplicableDirectives(projectId, 4).stream()
                        .noneMatch(d -> d.getId().equals(once.getId())),
                "ONCE 确认后本章也不再适用");
    }

    @Test
    void updateDirectiveCAS与撤销留审计() {
        // CAS：错误 expectedRevision 失败
        AuthorDirective updated = new AuthorDirective(hard.getId(), projectId, hard.getKind(),
                hard.getChapterIndex(), "修订后的要求", hard.getStatus(), null, null,
                hard.getCreatedAt(), hard.getConfirmedAt(), hard.getUpdatedAt());
        assertThrows(IllegalStateException.class, () -> repo.updateDirective(updated, 99));
        repo.updateDirective(updated, 1);
        assertEquals(2, repo.findAuthorDirective(projectId, hard.getId()).orElseThrow().getRevision());
        // 撤销：CAS + 理由必填 + 审计
        assertThrows(IllegalArgumentException.class,
                () -> repo.revokeDirective(projectId, hard.getId(), "  ", 2), "撤销必须理由");
        repo.revokeDirective(projectId, hard.getId(), "作者改变主意", 2);
        AuthorDirective revoked = repo.findAuthorDirective(projectId, hard.getId()).orElseThrow();
        assertEquals(AuthorDirective.Status.DISMISSED, revoked.getStatus());
        assertFalse(revoked.isEnabled());
        assertEquals(3, revoked.getRevision());
        assertTrue(repo.findApplicableDirectives(projectId, 5).isEmpty(), "撤销后不再适用");
    }

}
