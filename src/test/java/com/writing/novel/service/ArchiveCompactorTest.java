package com.writing.novel.service;

import com.writing.novel.domain.Entity;
import com.writing.novel.domain.EntityEdge;
import com.writing.novel.domain.RelationshipType;
import com.writing.novel.domain.EntityType;
import com.writing.novel.domain.ContinuityIssue;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * p10-w3-03: ArchiveCompactor 归档衰减——过期边删除 + 旧问题归档（根因 B）。
 */
class ArchiveCompactorTest {

    @TempDir
    Path tempDir;
    private SqliteNovelRepository repo;
    private ArchiveCompactor compactor;
    private String pid = "proj-1";

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("archive.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        compactor = new ArchiveCompactor(repo);
    }

    @Test
    void 过期实体边被物理删除_近期边保留() {
        Entity a = new Entity(pid, "甲", EntityType.CHARACTER);
        Entity b = new Entity(pid, "乙", EntityType.CHARACTER);
        repo.saveEntity(a); repo.saveEntity(b);
        // 过期边（validTo=3，当前章 50 → 早于 45 删除）
        EntityEdge expired = new EntityEdge(pid, a.getId(), b.getId(), RelationshipType.ENEMY);
        expired.expire(3);
        repo.saveEntityEdge(expired);
        // 近期边（validTo=0 活跃，保留）
        EntityEdge recent = new EntityEdge(pid, a.getId(), b.getId(), RelationshipType.ALLY);
        repo.saveEntityEdge(recent);

        var result = compactor.compact(pid, 50);
        assertEquals(1, result.edgesDeleted(), "应删除 1 条过期边");
        var remaining = repo.findEdgesByProject(pid);
        assertEquals(1, remaining.size());
        assertEquals(RelationshipType.ALLY, remaining.get(0).getRelationship());
    }

    @Test
    void 旧的非HIGH问题归档_HIGH问题保留() {
        // 旧 LOW 问题（章 10，当前章 50 → 早于 30，归档）
        ContinuityIssue oldLow = new ContinuityIssue(pid, 10,
                ContinuityIssue.Type.WORLDBUILDING, ContinuityIssue.Severity.LOW, "旧 LOW");
        repo.saveContinuityIssue(oldLow);
        // 旧 HIGH 问题（章 10，HIGH 不归档）
        ContinuityIssue oldHigh = new ContinuityIssue(pid, 10,
                ContinuityIssue.Type.WORLDBUILDING, ContinuityIssue.Severity.HIGH, "旧 HIGH");
        repo.saveContinuityIssue(oldHigh);
        // 近期 MEDIUM 问题（章 45，当前章 50 → 晚于 30，保留）
        ContinuityIssue recent = new ContinuityIssue(pid, 45,
                ContinuityIssue.Type.PLOT, ContinuityIssue.Severity.MEDIUM, "近期");
        repo.saveContinuityIssue(recent);

        var result = compactor.compact(pid, 50);
        assertEquals(1, result.issuesArchived(), "应归档 1 条旧 LOW 问题");
        var unresolved = repo.findUnresolvedIssues(pid);
        // HIGH + 近期 MEDIUM 保留未解决；旧 LOW 已归档（resolved）
        assertTrue(unresolved.stream().anyMatch(i -> i.getSeverity() == ContinuityIssue.Severity.HIGH));
        assertTrue(unresolved.stream().anyMatch(i -> i.getDescription().equals("近期")));
        assertFalse(unresolved.stream().anyMatch(i -> i.getDescription().equals("旧 LOW")), "旧 LOW 应已归档");
    }

    @Test
    void 无过期数据时compact安全返回零() {
        var result = compactor.compact(pid, 100);
        assertEquals(0, result.edgesDeleted());
        assertEquals(0, result.issuesArchived());
    }
}
