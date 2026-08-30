package com.writing.novel.service;

import com.writing.novel.domain.*;
import com.writing.novel.orchestrator.NovelOrchestrator;
import com.writing.novel.orchestrator.ProjectService;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * p16-01: HIGH issue 老化 + FINAL 门禁口径。
 * 400 章尺度下历史 HIGH 无界累积（归档只处理非 HIGH）导致 FINAL 门禁必挂；
 * 修复：卷末老化 30 章前未复发 HIGH；FINAL 门禁只统计近 60 章未解决 HIGH。
 */
class HighIssueAgingTest {

    @TempDir
    Path tempDir;
    private SqliteNovelRepository repo;
    private ArchiveCompactor compactor;
    private NovelOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("aging.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        compactor = new ArchiveCompactor(repo);
        orchestrator = new NovelOrchestrator(repo, new ProjectService(repo),
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private Project projectAtChapter(String title, int currentChapter) {
        Project p = new Project(title, "测试", "武侠");
        repo.saveProject(p);
        Outline outline = new Outline(p.getId(), "总纲");
        Volume v = new Volume(p.getId(), 1, "第一卷", "卷摘要");
        v.getSynopses().add(new ChapterSynopsis(p.getId(), 1, "第1章", "开局"));
        outline.getVolumes().add(v);
        repo.saveOutline(outline);
        p.setCurrentChapterIndex(currentChapter);
        p.setState(NovelState.VOLUME_DONE);
        repo.saveProject(p);
        return p;
    }

    @Test
    void 卷末老化30章前未复发HIGH() {
        Project p = projectAtChapter("老化", 50);
        ContinuityIssue stale = new ContinuityIssue(p.getId(), 10,
                ContinuityIssue.Type.WORLDBUILDING, ContinuityIssue.Severity.HIGH, "陈年穿帮");
        ContinuityIssue recent = new ContinuityIssue(p.getId(), 48,
                ContinuityIssue.Type.WORLDBUILDING, ContinuityIssue.Severity.HIGH, "新近穿帮");
        repo.saveContinuityIssue(stale);
        repo.saveContinuityIssue(recent);

        compactor.compact(p.getId(), 50);

        var unresolved = repo.findUnresolvedIssues(p.getId());
        assertTrue(unresolved.stream().noneMatch(i -> i.getDescription().equals("陈年穿帮")),
                "30 章前未复发 HIGH 应被老化归档");
        assertTrue(unresolved.stream().anyMatch(i -> i.getDescription().equals("新近穿帮")),
                "近期 HIGH 不应被老化");
    }

    @Test
    void FINAL门禁只统计近60章HIGH_历史HIGH不阻塞() {
        Project p = projectAtChapter("历史门禁", 200);
        // 41 条 100+ 章前的历史 HIGH（全量口径下 >10 必挂）
        for (int i = 0; i < 41; i++) {
            repo.saveContinuityIssue(new ContinuityIssue(p.getId(), 10,
                    ContinuityIssue.Type.TIMELINE, ContinuityIssue.Severity.HIGH, "历史矛盾" + i));
        }

        orchestrator.step(p.getId());

        assertEquals(NovelState.FINAL,
                repo.findProject(p.getId()).orElseThrow().getState(),
                "历史 HIGH（近 60 章外）不应阻塞 FINAL 门禁");
    }

    @Test
    void FINAL门禁近期HIGH超限仍阻塞() {
        Project p = projectAtChapter("近期门禁", 200);
        // 11 条 60 章窗口内的未解决 HIGH
        for (int i = 0; i < 11; i++) {
            repo.saveContinuityIssue(new ContinuityIssue(p.getId(), 195 + i,
                    ContinuityIssue.Type.TIMELINE, ContinuityIssue.Severity.HIGH, "近期矛盾" + i));
        }

        orchestrator.step(p.getId());

        assertEquals(NovelState.NEEDS_REVISION,
                repo.findProject(p.getId()).orElseThrow().getState(),
                "近 60 章内未解决 HIGH 超限仍应 NEEDS_REVISION");
    }
}
