package com.writing.novel.service;

import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.ChapterApproval;
import com.writing.novel.domain.ChapterVersion;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.ReviewBarrier;
import com.writing.novel.domain.RevisionProposal;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.spi.NovelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * p37-14：统一修订边界接入候选原子提交。
 * 候选与手编同等 500 字门槛、版本快照、批准失效；派生失败网络重试不增版；
 * 段落候选在任何全文变动后应用都冲突。
 */
class ProposalRevisionBoundaryTest {

    @TempDir
    Path tempDir;

    private ConnectionProvider cp;
    private SqliteNovelRepository repo;
    private ChapterRevisionService service;
    private String projectId;
    private AtomicInteger refreshCalls;

    private static final String C2 = "待复核的初始正文内容，长度满足已完成章节门槛。".repeat(30);
    private static final String LONG_AFTER = "候选生成的新正文内容，长度满足完成门槛要求，并包含修订。".repeat(30);

    @BeforeEach
    void setUp() {
        cp = new ConnectionProvider(tempDir.resolve("boundary.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        refreshCalls = new AtomicInteger();
        // 派生刷新默认成功（可注入失败）
        service = new ChapterRevisionService(repo, null, (pid, ch) -> refreshCalls.incrementAndGet());
        Project project = new Project("边界项目", "概念", "武侠");
        repo.saveProject(project);
        projectId = project.getId();
        Chapter chapter = new Chapter(projectId, 1, "第一章");
        chapter.setContent(C2);
        chapter.setStatus(Chapter.Status.NEEDS_MANUAL_REVIEW);
        repo.saveChapter(chapter);
    }

    // --- helpers ---

    private Chapter chapterFromDb() {
        return repo.findChapterByIndex(projectId, 1).orElseThrow();
    }

    /** 基于“候选所属项目行”构造 READY 章节候选（id/版本/指纹与当前稿对齐）。 */
    private RevisionProposal chapterProposal(String content, String title) {
        Chapter current = chapterFromDb();
        String after = "{\"title\":\"" + title + "\",\"content\":\"" + content.replace("\"", "\\\"") + "\"}";
        return new RevisionProposal(UUID.randomUUID().toString(), projectId,
                RevisionProposal.TargetType.CHAPTER, current.getId(), 1, null,
                current.getVersion(), RevisionProposal.chapterSourceHash(current.getTitle(), current.getContent()),
                "增加对话", "{\"before\":true}", after, "生成摘要",
                RevisionProposal.Status.READY, "key-" + UUID.randomUUID(), null,
                Instant.now(), Instant.now());
    }

    private RevisionProposal passageProposal(String replacement, int passageIndex) {
        Chapter current = chapterFromDb();
        String after = "{\"content\":\"" + replacement.replace("\"", "\\\"") + "\"}";
        return new RevisionProposal(UUID.randomUUID().toString(), projectId,
                RevisionProposal.TargetType.PASSAGE, current.getId(), 1, passageIndex,
                current.getVersion(), RevisionProposal.chapterSourceHash(current.getTitle(), current.getContent()),
                "改写本段", "{\"before\":true}", after, "生成摘要",
                RevisionProposal.Status.READY, "key-" + UUID.randomUUID(), null,
                Instant.now(), Instant.now());
    }

    private ChapterVersion snapshotOfCurrent() {
        Chapter current = chapterFromDb();
        return new ChapterVersion(UUID.randomUUID().toString(), projectId, 1,
                current.getVersion(), current.getTitle(), current.getContent(), "PROPOSAL", Instant.now());
    }

    /** 与候选同语义的原地修订 Chapter（复用行 id，doEdit 语义）。 */
    private Chapter revisedInPlace(String title, String content) {
        Chapter current = chapterFromDb();
        Chapter revised = new Chapter(projectId, 1, title);
        try {
            java.lang.reflect.Field fId = Chapter.class.getDeclaredField("id");
            fId.setAccessible(true);
            fId.set(revised, current.getId());
            java.lang.reflect.Field fCreated = Chapter.class.getDeclaredField("createdAt");
            fCreated.setAccessible(true);
            fCreated.set(revised, current.getCreatedAt());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        revised.setContent(content);
        return revised;
    }

    private int countRows(String sql) throws SQLException {
        try (Connection conn = cp.getConnection(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    // --- 契约断言 ---

    @Test
    void 候选与手编同等500字门槛() throws SQLException {
        RevisionProposal shortProposal = chapterProposal("太短", "第一章");
        repo.saveProposal(shortProposal);
        int versionBefore = chapterFromDb().getVersion();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.applyProposal(projectId, shortProposal.getId()),
                "已完成章节候选正文不足500字必须拒绝");
        assertTrue(e.getMessage().contains("500"));
        assertEquals(versionBefore, chapterFromDb().getVersion(), "拒绝不推进版本");
        assertEquals(0, countRows("SELECT COUNT(*) FROM chapter_versions"), "拒绝不留快照");
        assertEquals(RevisionProposal.Status.READY,
                repo.findProposal(projectId, shortProposal.getId()).orElseThrow().getStatus(),
                "校验失败不消费候选");
    }

    @Test
    void 应用产生版本快照并使既有批准失效() throws SQLException {
        // 作者批准 v2
        ChapterApproval.ReviewContext ctx = repo.readReviewContext(projectId, 1);
        repo.approveChapter(new ChapterApproval.Request(ctx.identity(), ctx.reviewSetHash(),
                "approve-" + UUID.randomUUID(), null));
        assertEquals(Chapter.Status.DONE, chapterFromDb().getStatus());
        assertTrue(repo.findActiveReviewBarriers(projectId).isEmpty());

        int versionBefore = chapterFromDb().getVersion();
        RevisionProposal proposal = chapterProposal(LONG_AFTER, "第一章（修订）");
        repo.saveProposal(proposal);

        ChapterRevisionService.ProposalApplyResult result =
                service.applyProposal(projectId, proposal.getId());

        assertFalse(result.commit().replayed());
        assertEquals(versionBefore + 1, chapterFromDb().getVersion(), "正文推进一版");
        assertEquals(LONG_AFTER, chapterFromDb().getContent());
        assertEquals(1, countRows("SELECT COUNT(*) FROM chapter_versions"), "版本快照留痕");
        // 批准失效：v2 批准可查但不约束 v3，新稿回到待审并绑定新屏障
        assertEquals(1, countRows("SELECT COUNT(*) FROM chapter_approvals WHERE chapter_version=" + versionBefore),
                "历史批准保留");
        assertNull(repo.readReviewContext(projectId, 1).approval(), "v3 不继承批准");
        assertEquals(Chapter.Status.NEEDS_MANUAL_REVIEW, chapterFromDb().getStatus(),
                "已批准稿件修订后须重新人审");
        assertEquals(1, repo.findActiveReviewBarriers(projectId).size());
        assertEquals(versionBefore + 1,
                repo.findActiveReviewBarriers(projectId).get(0).getChapterVersion());
        assertEquals(RevisionProposal.Status.APPLIED,
                repo.findProposal(projectId, proposal.getId()).orElseThrow().getStatus());
        assertEquals(1, refreshCalls.get(), "派生刷新提交后执行一次");
    }

    @Test
    void 派生失败网络重试不增版() throws SQLException {
        AtomicInteger failing = new AtomicInteger(1);
        ChapterRevisionService failingRefresh = new ChapterRevisionService(repo, null, (pid, ch) -> {
            if (failing.getAndDecrement() > 0) throw new IllegalStateException("派生失败");
        });
        RevisionProposal proposal = chapterProposal(LONG_AFTER, "第一章");
        repo.saveProposal(proposal);
        int versionBefore = chapterFromDb().getVersion();

        ChapterRevisionService.ProposalApplyResult first =
                failingRefresh.applyProposal(projectId, proposal.getId());
        assertTrue(first.chapter().isDerivedRefreshPending(), "派生失败标 pending");
        assertEquals(versionBefore + 1, chapterFromDb().getVersion());

        // 网络重试：同一候选再次应用——重放持久 result，派生刷新重试，不另增版本
        ChapterRevisionService.ProposalApplyResult retry =
                service.applyProposal(projectId, proposal.getId());
        assertTrue(retry.commit().replayed(), "重试返回已持久 result");
        assertEquals(versionBefore + 1, chapterFromDb().getVersion(), "重试不得另增版本");
        assertEquals(1, countRows("SELECT COUNT(*) FROM chapter_versions"), "快照只有一份");
        assertFalse(retry.chapter().isDerivedRefreshPending(), "重试派生刷新成功");
    }

    @Test
    void 段落候选在任何全文变动后应用都冲突() throws SQLException {
        RevisionProposal proposal = passageProposal("本段被候选改写。", 0);
        repo.saveProposal(proposal);
        Chapter current = chapterFromDb();

        // 全文变动：外部正式修订（任何全文变动，含段落重排）
        Chapter revised = revisedInPlace(current.getTitle(), C2 + "\n新增一整段，全文已变化。");
        assertTrue(repo.saveChapterRevision(snapshotOfCurrent(), revised, current.getVersion()),
                "来源变化修订成功");
        int latestVersion = chapterFromDb().getVersion();

        assertThrows(NovelRepository.ProposalSourceConflictException.class,
                () -> service.applyProposal(projectId, proposal.getId()),
                "全文变动后段落候选必须冲突");
        assertEquals(latestVersion, chapterFromDb().getVersion(), "冲突不改正文");
        assertEquals(RevisionProposal.Status.STALE,
                repo.findProposal(projectId, proposal.getId()).orElseThrow().getStatus(),
                "冲突候选标 STALE");
    }
}
