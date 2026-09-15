package com.writing.novel.persistence;

import com.writing.novel.domain.ChapterApproval;
import com.writing.novel.domain.ReviewDisposition;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** p38-10：问题裁决存取与版本绑定——save/find/hasValid 按身份匹配。 */
class DispositionApprovalGateTest {

    @TempDir
    Path tempDir;
    private ConnectionProvider cp;
    private SqliteNovelRepository repo;
    private String pid = "p1";
    private ChapterApproval.Identity v2 = new ChapterApproval.Identity(pid, 4, 2, "hash-v2");
    private ChapterApproval.Identity v3 = new ChapterApproval.Identity(pid, 4, 3, "hash-v3");

    @BeforeEach
    void setUp() {
        cp = new ConnectionProvider(tempDir.resolve("disp.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
    }

    private ReviewDisposition disp(String issueId, ChapterApproval.Identity identity, String action) {
        return new ReviewDisposition(java.util.UUID.randomUUID().toString(), pid, issueId,
                "fp-" + issueId, identity, ReviewDisposition.Action.valueOf(action),
                "理由", action.equals("DEFER") ? 8 : null, Instant.now());
    }

    @Test
    void save_find按身份匹配_旧稿裁决不出现() {
        repo.saveDisposition(disp("issue-A", v2, "FALSE_POSITIVE"));
        List<ReviewDisposition> v2Results = repo.findDispositions(pid, 4, 2, "hash-v2");
        assertEquals(1, v2Results.size());
        List<ReviewDisposition> v3Results = repo.findDispositions(pid, 4, 3, "hash-v3");
        assertEquals(0, v3Results.size(), "旧稿裁决不出现在新稿");
    }

    @Test
    void hasValidDisposition按身份和action过滤() {
        repo.saveDisposition(disp("issue-A", v2, "FALSE_POSITIVE"));
        assertTrue(repo.hasValidDisposition(pid, "issue-A", v2), "FALSE_POSITIVE 有效");
        assertFalse(repo.hasValidDisposition(pid, "issue-A", v3), "旧稿无效");
        // DEFER 不算有效豁免（须在期限内由门禁判断）
        repo.saveDisposition(disp("issue-B", v2, "DEFER"));
        assertFalse(repo.hasValidDisposition(pid, "issue-B", v2), "DEFER 不是即时豁免");
        // EXCEPTION 有效
        repo.saveDisposition(disp("issue-C", v2, "EXCEPTION"));
        assertTrue(repo.hasValidDisposition(pid, "issue-C", v2), "EXCEPTION 有效");
    }
}
