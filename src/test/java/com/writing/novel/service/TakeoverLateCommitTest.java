package com.writing.novel.service;

import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.ChapterVersion;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * p40-01（审计 P2-7 充实版）：不可取消慢模型与接手晚写入。
 * 慢"模型"线程持有过期基准，期间作者接手编辑推进版本——晚到的写入必须被
 * 版本 CAS 拒绝（零写入），不得覆盖作者内容；工权占用期间编辑入口拒绝。
 */
class TakeoverLateCommitTest {

    @TempDir
    Path tempDir;

    private SqliteNovelRepository repo;
    private String projectId;

    private static final String V1 = "初始正文内容，长度满足门槛要求。".repeat(30);
    private static final String AUTHOR_EDIT = "作者接手后的编辑内容，必须保留。".repeat(40);
    private static final String LATE_MODEL = "慢模型晚到的输出，不得覆盖作者内容。".repeat(30);

    @BeforeEach
    void setUp() {
        var cp = new ConnectionProvider(tempDir.resolve("takeover.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        Project project = new Project("晚写入项目", "c", "t");
        repo.saveProject(project);
        projectId = project.getId();
        Chapter chapter = new Chapter(projectId, 1, "第一章");
        chapter.setContent(V1);
        chapter.setStatus(Chapter.Status.DONE);
        repo.saveChapter(chapter);
    }

    private Chapter current() {
        return repo.findChapterByIndex(projectId, 1).orElseThrow();
    }

    private ChapterVersion snapshotOf(Chapter ch) {
        return new ChapterVersion(UUID.randomUUID().toString(), projectId, 1,
                ch.getVersion(), ch.getTitle(), ch.getContent(), "REWRITE", Instant.now());
    }

    private Chapter revisedFrom(Chapter base, String content, int newVersion) {
        Chapter revised = new Chapter(projectId, 1, base.getTitle());
        try {
            var fId = Chapter.class.getDeclaredField("id");
            fId.setAccessible(true);
            fId.set(revised, base.getId());
            var fCreated = Chapter.class.getDeclaredField("createdAt");
            fCreated.setAccessible(true);
            fCreated.set(revised, base.getCreatedAt());
            revised.setContent(content);
            // setContent 会自增版本——最后再精确覆盖目标版本号
            var fVersion = Chapter.class.getDeclaredField("version");
            fVersion.setAccessible(true);
            fVersion.set(revised, newVersion);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        revised.setStatus(Chapter.Status.DONE);
        return revised;
    }

    @Test
    void 慢模型晚写入被版本CAS拒绝且不覆盖作者内容() throws Exception {
        // 慢"模型"读取基准（v1）后阻塞——模拟不可取消的长任务
        Chapter modelBase = current();
        int baseVersion = modelBase.getVersion();
        CountDownLatch authorDone = new CountDownLatch(1);
        CountDownLatch modelStarted = new CountDownLatch(1);
        AtomicReference<Boolean> lateWriteResult = new AtomicReference<>();

        Thread slowModel = new Thread(() -> {
            modelStarted.countDown();
            try {
                assertTrue(authorDone.await(10, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            // 作者已接手并推进版本；晚到写入以过期基准提交 → CAS 必须拒绝
            lateWriteResult.set(repo.saveChapterRevision(snapshotOf(modelBase),
                    revisedFrom(modelBase, LATE_MODEL, baseVersion + 1), baseVersion));
        }, "slow-model");
        slowModel.start();
        assertTrue(modelStarted.await(5, TimeUnit.SECONDS));

        // 作者接手编辑（真实边界，expectedVersion=当前版本）
        Chapter authorBase = current();
        boolean authorOk = repo.saveChapterRevision(snapshotOf(authorBase),
                revisedFrom(authorBase, AUTHOR_EDIT, authorBase.getVersion() + 1),
                authorBase.getVersion());
        assertTrue(authorOk, "作者编辑必须成功");
        assertEquals(baseVersion + 1, current().getVersion());
        assertEquals(AUTHOR_EDIT, current().getContent());

        // 放行慢模型 → 晚写入以过期基准提交
        authorDone.countDown();
        slowModel.join(10_000);

        assertEquals(Boolean.FALSE, lateWriteResult.get(),
                "过期基准的晚写入必须被 CAS 拒绝（零写入）");
        assertEquals(AUTHOR_EDIT, current().getContent(), "作者内容不被晚到输出覆盖");
        assertEquals(baseVersion + 1, current().getVersion(), "版本不被晚写入推进");
        assertEquals(1, repo.findChapterVersions(projectId, 1).size(),
                "只有作者编辑留下快照");
    }

    @Test
    void 工权占用期间编辑入口拒绝_释放后恢复() {
        WritingTaskExecutor executor = new WritingTaskExecutor(null, repo);
        ChapterRevisionService service = new ChapterRevisionService(repo, executor, null);
        try {
            executor.beginWork(projectId, "slow-agent-task");
            assertThrows(WorkConflictException.class,
                    () -> service.edit(projectId, 1, current().getVersion(),
                            "第一章", AUTHOR_EDIT, "DIRECT_EDIT"),
                    "工权占用期间编辑必须拒绝");
            assertEquals(V1, current().getContent(), "拒绝时零写入");
        } finally {
            executor.endWork(projectId, "slow-agent-task");
            executor.shutdown();
        }
        Chapter after = service.edit(projectId, 1, current().getVersion(),
                "第一章", AUTHOR_EDIT, "DIRECT_EDIT");
        assertEquals(AUTHOR_EDIT, after.getContent(), "释放后恢复可编辑");
    }
}
