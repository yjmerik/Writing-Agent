package com.writing.novel.persistence;

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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * p37-02：候选持久存取与章节原子应用协议。
 * 重建 repository/连接后候选仍可读；apply 故障回滚后候选保持 READY 且重试成功不重复；
 * 跨项目不可读/不可应用；双 apply 只推进一版正文；来源冲突标 STALE 且绝不改正文。
 */
class ProposalRepositoryTest {

    @TempDir
    Path tempDir;

    private ConnectionProvider cp;
    private SqliteNovelRepository repo;
    private String projectId;

    private static final String C2 = "待复核的初始正文内容，长度满足已完成章节门槛。".repeat(30);
    private static final String C3 = "候选生成的新正文内容，长度同样满足完成门槛要求。".repeat(30);

    @BeforeEach
    void setUp() {
        cp = new ConnectionProvider(tempDir.resolve("proposal.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        Project project = new Project("候选项目", "概念", "武侠");
        project.setState(com.writing.novel.domain.NovelState.PAUSED_AT_CHECKPOINT);
        repo.saveProject(project);
        projectId = project.getId();
        Chapter chapter = new Chapter(projectId, 1, "第一章");
        chapter.setContent(C2);
        chapter.setStatus(Chapter.Status.NEEDS_MANUAL_REVIEW);
        repo.saveChapter(chapter);
        repo.saveReviewBarrier(new ReviewBarrier(projectId, 1, chapter.getVersion(), "根源矛盾待复核"));
    }

    // --- helpers ---

    private Chapter chapterFromDb() {
        return repo.findChapterByIndex(projectId, 1).orElseThrow();
    }

    private RevisionProposal readyProposal(String requestKey) {
        Chapter current = chapterFromDb();
        return new RevisionProposal(UUID.randomUUID().toString(), projectId,
                RevisionProposal.TargetType.CHAPTER, current.getId(), 1, null,
                current.getVersion(), RevisionProposal.chapterSourceHash(current.getTitle(), current.getContent()),
                "增加对话", "{\"before\":true}", "{\"after\":true}", "生成摘要",
                RevisionProposal.Status.READY, requestKey, null,
                Instant.now(), Instant.now());
    }

    private ChapterVersion snapshotFor(Chapter current, String source) {
        return new ChapterVersion(UUID.randomUUID().toString(), projectId, 1,
                current.getVersion(), current.getTitle(), current.getContent(), source, Instant.now());
    }

    private Chapter revisedFrom(RevisionProposal proposal) {
        Chapter revised = new Chapter(projectId, 1, "第一章");
        revised.setContent(C3);
        revised.setStatus(Chapter.Status.NEEDS_MANUAL_REVIEW);
        try {
            java.lang.reflect.Field f = Chapter.class.getDeclaredField("version");
            f.setAccessible(true);
            f.set(revised, proposal.getBaseVersion() + 1);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return revised;
    }

    private ChapterApproval.Identity identityOfCurrent() {
        return ChapterApproval.Identity.of(chapterFromDb());
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
    void 候选持久化并在重建repository后仍可读() {
        RevisionProposal proposal = readyProposal("key-persist");
        repo.saveProposal(proposal);

        // 重建 repository/连接（模拟重启后的新实例视角）
        SqliteNovelRepository reconnected = new SqliteNovelRepository(cp);
        assertTrue(reconnected.findProposal(projectId, proposal.getId()).isPresent(),
                "重启后候选必须可读");
        RevisionProposal restored = reconnected.findProposal(projectId, proposal.getId()).orElseThrow();
        assertEquals(RevisionProposal.Status.READY, restored.getStatus());
        assertEquals(proposal.getInstructions(), restored.getInstructions());
        assertEquals(proposal.getSourceHash(), restored.getSourceHash());
        assertEquals(1, reconnected.listProposals(projectId).size());
        // 跨项目不可读
        assertTrue(reconnected.findProposal("other-project", proposal.getId()).isEmpty(),
                "候选不得跨项目读取");
    }

    @Test
    void 应用成功推进正文并消费候选_屏障重绑与STALE留痕同事务() throws SQLException {
        RevisionProposal proposal = readyProposal("key-apply");
        repo.saveProposal(proposal);
        Chapter current = chapterFromDb();

        NovelRepository.ProposalCommitResult result = repo.commitProposalChapter(
                projectId, proposal.getId(), snapshotFor(current, "PROPOSAL"),
                revisedFrom(proposal), identityOfCurrent());

        assertFalse(result.replayed());
        assertEquals(proposal.getId(), result.proposalId());
        assertEquals(current.getVersion() + 1, result.chapterVersion());
        assertEquals(current.getVersion() + 1, chapterFromDb().getVersion(), "正文推进一版");
        assertEquals(C3, chapterFromDb().getContent());
        assertEquals(RevisionProposal.Status.APPLIED,
                repo.findProposal(projectId, proposal.getId()).orElseThrow().getStatus());
        assertNotNull(repo.findProposal(projectId, proposal.getId()).orElseThrow().getResultJson());
        assertEquals(1, countRows("SELECT COUNT(*) FROM chapter_versions"), "修订快照留痕");
        assertEquals(1, countRows(
                "SELECT COUNT(*) FROM review_barrier_history WHERE project_id='" + projectId + "' AND event='REVISED'"),
                "屏障重绑随同一事务留痕");
        assertEquals(proposal.getBaseVersion() + 1,
                repo.findActiveReviewBarriers(projectId).get(0).getChapterVersion(),
                "屏障重绑到新稿号");
    }

    @Test
    void 双apply重试返回已持久result不另增版本() throws SQLException {
        RevisionProposal proposal = readyProposal("key-double");
        repo.saveProposal(proposal);
        Chapter current = chapterFromDb();
        repo.commitProposalChapter(projectId, proposal.getId(),
                snapshotFor(current, "PROPOSAL"), revisedFrom(proposal), identityOfCurrent());

        NovelRepository.ProposalCommitResult replay = repo.commitProposalChapter(
                projectId, proposal.getId(),
                snapshotFor(chapterFromDb(), "PROPOSAL"), revisedFrom(proposal), identityOfCurrent());

        assertTrue(replay.replayed(), "重复应用返回已持久结果");
        assertEquals(result0Json(), replay.resultJson(), "返回首次应用持久化的 resultJson");
        assertEquals(current.getVersion() + 1, chapterFromDb().getVersion(),
                "重试不得另增版本");
        assertEquals(1, countRows("SELECT COUNT(*) FROM chapter_versions"), "不得新增快照");
    }

    private String result0Json() {
        // 首次应用的 resultJson 形如 {"chapterIndex":1,"version":N,"contentHash":...}
        Chapter current = chapterFromDb();
        return "{\"chapterIndex\":1,\"version\":" + current.getVersion()
                + ",\"contentHash\":\"" + ChapterApproval.contentHash(current.getTitle(), current.getContent()) + "\"}";
    }

    @Test
    void 注入故障回滚后候选保持READY且重试成功() throws SQLException {
        RevisionProposal proposal = readyProposal("key-fail");
        repo.saveProposal(proposal);
        Chapter current = chapterFromDb();
        try (Connection conn = cp.getConnection(); Statement st = conn.createStatement()) {
            st.execute("CREATE TRIGGER inject_proposal_failure BEFORE UPDATE ON chapters " +
                    "BEGIN SELECT RAISE(ABORT, 'injected'); END;");
        }
        assertThrows(RuntimeException.class, () -> repo.commitProposalChapter(
                        projectId, proposal.getId(), snapshotFor(current, "PROPOSAL"),
                        revisedFrom(proposal), identityOfCurrent()),
                "正文写入阶段故障必须整体回滚");
        try (Connection conn = cp.getConnection(); Statement st = conn.createStatement()) {
            st.execute("DROP TRIGGER inject_proposal_failure");
        }

        assertEquals(RevisionProposal.Status.READY,
                repo.findProposal(projectId, proposal.getId()).orElseThrow().getStatus(),
                "回滚后候选保持 READY");
        assertEquals(current.getVersion(), chapterFromDb().getVersion(), "正文未变");
        assertEquals(0, countRows("SELECT COUNT(*) FROM chapter_versions"), "快照回滚");

        // 同一候选重试成功，只推进一版
        NovelRepository.ProposalCommitResult retry = repo.commitProposalChapter(
                projectId, proposal.getId(), snapshotFor(chapterFromDb(), "PROPOSAL"),
                revisedFrom(proposal), identityOfCurrent());
        assertFalse(retry.replayed());
        assertEquals(current.getVersion() + 1, chapterFromDb().getVersion());
    }

    @Test
    void 来源变化时冲突_STALE独立留痕且绝不改正文() throws SQLException {
        RevisionProposal proposal = readyProposal("key-stale");
        repo.saveProposal(proposal);
        // 来源变化：正式修订推进正文（p36-07 边界）
        Chapter current = chapterFromDb();
        Chapter revised = new Chapter(projectId, 1, "第一章");
        revised.setContent(C3);
        revised.setStatus(Chapter.Status.NEEDS_MANUAL_REVIEW);
        repo.saveChapterRevision(snapshotFor(current, "DIRECT_EDIT"), revised, current.getVersion());
        int latestVersion = chapterFromDb().getVersion();

        // 陈旧身份应用：冲突且候选被标 STALE（独立短事务）
        ChapterApproval.Identity staleIdentity = new ChapterApproval.Identity(
                projectId, 1, current.getVersion(),
                RevisionProposal.chapterSourceHash("第一章", C2));
        NovelRepository.ProposalSourceConflictException conflict =
                assertThrows(NovelRepository.ProposalSourceConflictException.class,
                        () -> repo.commitProposalChapter(projectId, proposal.getId(),
                                snapshotFor(chapterFromDb(), "PROPOSAL"), revisedFrom(proposal), staleIdentity));
        assertEquals(latestVersion, conflict.getCurrentIdentity().version());

        assertEquals(latestVersion, chapterFromDb().getVersion(), "冲突绝不改正文");
        assertEquals(latestVersion, chapterFromDb().getContent().length() > 0
                && chapterFromDb().getContent().equals(C3) ? latestVersion : latestVersion);
        assertEquals(RevisionProposal.Status.STALE,
                repo.findProposal(projectId, proposal.getId()).orElseThrow().getStatus(),
                "来源冲突候选标 STALE");
        assertEquals(1, countRows("SELECT COUNT(*) FROM chapter_versions"), "不得新增快照");
    }

    @Test
    void 跨项目不可应用且READY取消用CAS留记录() throws SQLException {
        RevisionProposal proposal = readyProposal("key-cancel");
        repo.saveProposal(proposal);

        ChapterApproval.Identity identity = identityOfCurrent();
        IllegalArgumentException crossProject = assertThrows(IllegalArgumentException.class,
                () -> repo.commitProposalChapter("other-project", proposal.getId(),
                        snapshotFor(chapterFromDb(), "PROPOSAL"), revisedFrom(proposal),
                        new ChapterApproval.Identity("other-project", 1,
                                identity.version(), identity.contentHash())),
                "跨项目应用必须拒绝");
        assertTrue(crossProject.getMessage().contains("候选不存在"));

        assertTrue(repo.cancelProposal(projectId, proposal.getId()), "READY 候选可取消");
        assertEquals(RevisionProposal.Status.CANCELLED,
                repo.findProposal(projectId, proposal.getId()).orElseThrow().getStatus());
        assertFalse(repo.cancelProposal(projectId, proposal.getId()),
                "非 READY 候选 CAS 取消失败");
        assertNotNull(repo.findProposal(projectId, proposal.getId()).orElseThrow().getBeforeJson(),
                "取消留记录");
        assertEquals(1, repo.listProposals(projectId).size(), "取消不删除候选");

        // CANCELLED 候选不可应用
        assertThrows(IllegalStateException.class, () -> repo.commitProposalChapter(
                        projectId, proposal.getId(), snapshotFor(chapterFromDb(), "PROPOSAL"),
                        revisedFrom(proposal), identityOfCurrent()),
                "已取消候选不得应用");
        assertEquals(0, countRows("SELECT COUNT(*) FROM chapter_versions"));
    }
}
