package com.writing.novel.orchestrator;

import com.writing.novel.domain.*;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * p16-02: FINAL 伏笔回收章死路修复。
 * 原路径：大纲耗尽时对 mustResolve 伏笔写『第N章必须回收』ContinuityIssue 后直接走门禁并 break，
 * 且 runWritingLoop 的完结分支因 tryExtendLayeredOutline 失败提前 break 而整体不可达——回收指令无人消费。
 * 修复：大纲耗尽且 mustResolve>0 时追加『尾声·伏笔回收卷』（有界一次），回收指令写进新章梗概 writingNotes。
 */
class FinalForeshadowingRecoveryTest {

    @TempDir
    Path tempDir;
    private SqliteNovelRepository repo;
    private NovelOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("recovery.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        orchestrator = new NovelOrchestrator(repo, new ProjectService(repo),
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private Project projectWithExhaustedOutline(int currentChapter) {
        Project p = new Project("回收", "测试", "武侠");
        repo.saveProject(p);
        Outline outline = new Outline(p.getId(), "总纲");
        Volume v = new Volume(p.getId(), 1, "第一卷", "卷摘要");
        v.getSynopses().add(new ChapterSynopsis(p.getId(), 1, "第1章", "开局"));
        outline.getVolumes().add(v);
        repo.saveOutline(outline);
        p.setCurrentChapterIndex(currentChapter);
        p.setState(NovelState.WRITING);
        repo.saveProject(p);
        return p;
    }

    @Test
    void mustResolve伏笔存在时追加回收卷_指令写入writingNotes() {
        Project p = projectWithExhaustedOutline(8);
        Foreshadowing f = new Foreshadowing(p.getId(), "玉佩来历", "第2章埋设", 2);
        f.setMustResolve(true);
        repo.saveForeshadowing(f);

        boolean appended = orchestrator.appendForeshadowingRecoveryVolumeIfNeeded(p.getId(), p);

        assertTrue(appended, "mustResolve 未回收时应追加回收卷");
        Outline outline = repo.findOutlineByProject(p.getId()).orElseThrow();
        Volume recovery = outline.getVolumes().stream()
                .filter(v -> v.getTitle().contains("伏笔回收")).findFirst().orElseThrow();
        assertTrue(recovery.getSynopses().size() >= 2, "回收卷至少 2 章");
        // 回收指令必须出现在新章梗概的 writingNotes（ChapterWriter 的确定性输入通道）
        boolean instructionFound = recovery.getSynopses().stream()
                .anyMatch(s -> s.getWritingNotes() != null
                        && s.getWritingNotes().contains("玉佩来历")
                        && s.getWritingNotes().contains("回收"));
        assertTrue(instructionFound, "回收指令应写入新章 writingNotes 并点名伏笔");
        // 伏笔的期望回收章已设到回收卷范围内
        var saved = repo.findUnresolvedForeshadowings(p.getId()).stream()
                .filter(x -> x.getDescription().equals("玉佩来历")).findFirst().orElseThrow();
        assertNotNull(saved.getExpectedResolveChapter(), "expectedResolveChapter 应已设置");
        assertTrue(saved.getExpectedResolveChapter() > 8);
    }

    @Test
    void 回收卷只追加一次_第二次返回false() {
        Project p = projectWithExhaustedOutline(8);
        Foreshadowing f = new Foreshadowing(p.getId(), "身世之谜", "第1章埋设", 2);
        f.setMustResolve(true);
        repo.saveForeshadowing(f);

        assertTrue(orchestrator.appendForeshadowingRecoveryVolumeIfNeeded(p.getId(), p));
        assertFalse(orchestrator.appendForeshadowingRecoveryVolumeIfNeeded(p.getId(), p),
                "回收卷已存在时不得重复追加（有界，防无限延长）");
    }

    @Test
    void 无mustResolve伏笔时不追加回收卷() {
        Project p = projectWithExhaustedOutline(8);
        Foreshadowing f = new Foreshadowing(p.getId(), "闲笔", "第1章埋设", 1);
        f.setMustResolve(false);
        repo.saveForeshadowing(f);

        assertFalse(orchestrator.appendForeshadowingRecoveryVolumeIfNeeded(p.getId(), p));
        var volumes = repo.findOutlineByProject(p.getId()).orElseThrow().getVolumes();
        assertTrue(volumes.stream().noneMatch(v -> v.getTitle().contains("伏笔回收")));
    }
}
