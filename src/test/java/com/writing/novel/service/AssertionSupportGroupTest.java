package com.writing.novel.service;

import com.writing.novel.domain.AssertionSupport;
import com.writing.novel.persistence.sqlite.AssertionSupportRepository;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-052: 断言支持组——AND 前提/OR 来源保存及快照归属；支持历史不可变。
 */
class AssertionSupportGroupTest {

    @TempDir
    Path tempDir;
    private AssertionSupportRepository repo;

    @BeforeEach
    void setUp() {
        var cp = new ConnectionProvider(tempDir.resolve("asg.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new AssertionSupportRepository(cp);
    }

    @Test
    void 保存AND前提与OR来源及快照归属() {
        var and = repo.save(new AssertionSupport("p1", "fact-A",
                AssertionSupport.Link.AND_PREMISE, "fact-P1", "snap:hash-p1"));
        var or = repo.save(new AssertionSupport("p1", "fact-A",
                AssertionSupport.Link.OR_SOURCE, "evidence-9", "snap:hash-e9"));
        assertNotNull(and.getId(), "生成主键");
        assertNotNull(or.getId());
        assertEquals("snap:hash-p1", and.getSupportSnapshot(), "快照归属不可变字段");

        var loaded = repo.findByFact("p1", "fact-A");
        assertEquals(2, loaded.size());
        assertTrue(loaded.stream().anyMatch(s -> s.getLink() == AssertionSupport.Link.AND_PREMISE));
        assertTrue(loaded.stream().anyMatch(s -> s.getLink() == AssertionSupport.Link.OR_SOURCE));
    }

    @Test
    void 支持历史不可变_撤回改状态不删行() {
        var s = repo.save(new AssertionSupport("p1", "fact-B",
                AssertionSupport.Link.OR_SOURCE, "evidence-1", "snap:x"));
        repo.withdraw(s.getId());
        var after = repo.findById(s.getId()).orElseThrow();
        assertEquals(AssertionSupport.Status.WITHDRAWN, after.getStatus(), "撤回后状态标注");
        // 行仍在（历史不可变）
        assertEquals(1, repo.findByFact("p1", "fact-B").size(), "撤回不删行");
    }

    @Test
    void 依赖查询_按支持项反查() {
        repo.save(new AssertionSupport("p1", "fact-C", AssertionSupport.Link.AND_PREMISE, "fact-root", "s"));
        repo.save(new AssertionSupport("p1", "fact-D", AssertionSupport.Link.OR_SOURCE, "fact-root", "s"));
        assertEquals(2, repo.findBySupportFactId("p1", "fact-root").size(), "两个依赖者");
    }
}
