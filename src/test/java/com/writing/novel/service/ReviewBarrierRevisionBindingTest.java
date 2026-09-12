package com.writing.novel.service;

import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.ReviewBarrier;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * p34-04（UI 验收 A4，高）：正式修订必须同步人审屏障稿号。
 * 复现：v2 屏障待审 → 作者编辑保存 v3 → 顶部仍"v2 待审"且两套批准动作并存。
 * 修复：版本推进的正式修订把该章 ACTIVE 屏障重绑到新稿号（原因注明"内容已修订"），
 * 屏障继续阻止续写（新稿未经检查），但界面显示一致的新稿待审。
 */
class ReviewBarrierRevisionBindingTest {

    @TempDir
    Path tempDir;
    private SqliteNovelRepository repo;
    private ChapterRevisionService service;
    private String pid;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("rbb.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        service = new ChapterRevisionService(repo);
        Project p = new Project("屏障重绑", "概念", "悬疑");
        repo.saveProject(p);
        pid = p.getId();
        Chapter ch = new Chapter(pid, 1, "第一章");
        ch.setContent("初稿正文。".repeat(150));
        repo.saveChapter(ch);
        repo.saveReviewBarrier(new ReviewBarrier(pid, 1, ch.getVersion(), "根源性矛盾待人工"));
    }

    @Test
    void 正式修订把屏障重绑到新稿号() {
        int beforeVersion = repo.findChapterByIndex(pid, 1).orElseThrow().getVersion();

        service.edit(pid, 1, beforeVersion, "第一章",
                "作者修订后的正文。".repeat(150), "DIRECT_EDIT");

        int afterVersion = repo.findChapterByIndex(pid, 1).orElseThrow().getVersion();
        assertEquals(beforeVersion + 1, afterVersion, "修订正常推进版本");

        List<ReviewBarrier> barriers = repo.findActiveReviewBarriers(pid);
        assertEquals(1, barriers.size(), "屏障仍 ACTIVE（新稿未经检查）");
        ReviewBarrier barrier = barriers.get(0);
        assertEquals(afterVersion, barrier.getChapterVersion(),
                "屏障稿号必须同步到新稿（消除 v2 待审/v3 当前并存）");
        assertTrue(barrier.getReason().contains("内容已修订"),
                "原因应注明内容已修订需重新审查，实际: " + barrier.getReason());
    }

    @Test
    void 无屏障章节修订不受影响() {
        Chapter ch2 = new Chapter(pid, 2, "第二章");
        ch2.setContent("无屏障章节。".repeat(150));
        repo.saveChapter(ch2);
        int v = repo.findChapterByIndex(pid, 2).orElseThrow().getVersion();

        service.edit(pid, 2, v, "第二章", "无屏障章节修订。".repeat(150), "DIRECT_EDIT");

        assertEquals(0, repo.findActiveReviewBarriers(pid).stream()
                .filter(b -> b.getChapterIndex() == 2).count());
    }
}
