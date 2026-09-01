package com.writing.novel.service;

import com.writing.novel.domain.Entity;
import com.writing.novel.domain.EntityEdge;
import com.writing.novel.domain.RelationshipType;
import com.writing.novel.domain.EntityType;
import com.writing.novel.domain.ContinuityIssue;
import com.writing.novel.domain.Foreshadowing;
import com.writing.novel.domain.KnowledgeEntry;
import com.writing.novel.domain.NarrativeThread;
import com.writing.novel.domain.ThreadStatus;
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
    void 旧的非HIGH问题归档_旧HIGH老化_近期HIGH保留() {
        // 旧 LOW 问题（章 10，当前章 50 → 早于 30，归档）
        ContinuityIssue oldLow = new ContinuityIssue(pid, 10,
                ContinuityIssue.Type.WORLDBUILDING, ContinuityIssue.Severity.LOW, "旧 LOW");
        repo.saveContinuityIssue(oldLow);
        // 旧 HIGH 问题（章 10，当前章 50 → 超 30 章未复发，p16-01 老化归档）
        ContinuityIssue oldHigh = new ContinuityIssue(pid, 10,
                ContinuityIssue.Type.WORLDBUILDING, ContinuityIssue.Severity.HIGH, "旧 HIGH");
        repo.saveContinuityIssue(oldHigh);
        // 近期 HIGH 问题（章 48，当前章 50 → 保留）
        ContinuityIssue recentHigh = new ContinuityIssue(pid, 48,
                ContinuityIssue.Type.WORLDBUILDING, ContinuityIssue.Severity.HIGH, "近期 HIGH");
        repo.saveContinuityIssue(recentHigh);
        // 近期 MEDIUM 问题（章 45，当前章 50 → 晚于 30，保留）
        ContinuityIssue recent = new ContinuityIssue(pid, 45,
                ContinuityIssue.Type.PLOT, ContinuityIssue.Severity.MEDIUM, "近期");
        repo.saveContinuityIssue(recent);

        var result = compactor.compact(pid, 50);
        assertEquals(1, result.issuesArchived(), "应归档 1 条旧 LOW 问题");
        var unresolved = repo.findUnresolvedIssues(pid);
        // 近期 HIGH + 近期 MEDIUM 保留未解决；旧 LOW 与旧 HIGH（老化）已归档
        assertTrue(unresolved.stream().anyMatch(i -> i.getDescription().equals("近期 HIGH")));
        assertTrue(unresolved.stream().anyMatch(i -> i.getDescription().equals("近期")));
        assertFalse(unresolved.stream().anyMatch(i -> i.getDescription().equals("旧 LOW")), "旧 LOW 应已归档");
        assertFalse(unresolved.stream().anyMatch(i -> i.getDescription().equals("旧 HIGH")),
                "30 章前未复发 HIGH 应被 p16-01 老化归档");
    }

    @Test
    void p11_q0_06_已回收叙事线索被删除_活跃线索保留() {
        var resolved = new NarrativeThread(pid, "已结束线索", 1, "desc");
        resolved.setStatus(ThreadStatus.RESOLVED);
        repo.saveNarrativeThread(resolved);
        var active = new NarrativeThread(pid, "活跃线索", 1, "desc");
        repo.saveNarrativeThread(active);

        var result = compactor.compact(pid, 50);
        assertEquals(1, result.threadsDeleted(), "应删除 1 条已回收线索");
        var remaining = repo.findAllNarrativeThreads(pid);
        assertEquals(1, remaining.size());
        assertEquals("活跃线索", remaining.get(0).getName());
    }

    @Test
    void p11_q0_06_重名知识条目去重保留一个() {
        repo.saveKnowledgeEntry(new KnowledgeEntry(pid, "POWER_SYSTEM", "灵气", "内容A"));
        repo.saveKnowledgeEntry(new KnowledgeEntry(pid, "POWER_SYSTEM", "灵气", "内容B"));
        repo.saveKnowledgeEntry(new KnowledgeEntry(pid, "FACTION", "宗门", "内容C"));

        var result = compactor.compact(pid, 50);
        assertEquals(1, result.knowledgeDeduped(), "应去重 1 条重名知识");
        assertEquals(2, repo.findKnowledgeEntriesByProject(pid).size());
    }

    @Test
    void p11_q0_06_长期悬置非关键LOW伏笔放弃_mustResolve保留() {
        var stale = new Foreshadowing(pid, "悬置伏笔", "ctx", 5);
        stale.setPriority(Foreshadowing.Priority.LOW);
        stale.setMustResolve(false);
        repo.saveForeshadowing(stale);
        var critical = new Foreshadowing(pid, "关键伏笔", "ctx", 5);
        critical.setPriority(Foreshadowing.Priority.HIGH);
        critical.setMustResolve(true);
        repo.saveForeshadowing(critical);

        var result = compactor.compact(pid, 50); // 50-30=20 > planted 5 → stale 放弃
        assertEquals(1, result.foreshadowingsRetired());
        var unresolved = repo.findUnresolvedForeshadowings(pid);
        assertEquals(1, unresolved.size(), "仅关键伏笔保留未回收");
    }

    @Test
    void 无过期数据时compact安全返回零() {
        var result = compactor.compact(pid, 100);
        assertEquals(0, result.edgesDeleted());
        assertEquals(0, result.issuesArchived());
    }
}
