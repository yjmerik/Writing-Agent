package com.writing.novel.orchestrator;

import com.writing.novel.agent.PlotterAgent;
import com.writing.novel.domain.Checkpoint;
import com.writing.novel.domain.ChapterSynopsis;
import com.writing.novel.domain.NovelState;
import com.writing.novel.domain.Outline;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.ProjectMode;
import com.writing.novel.domain.Volume;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * NCS-078（听雪刀 P0）：空卷大纲候选 = 合法的「全书完」信号。
 * 拒绝卷大纲检查点并指示"不要生成后续卷"时，Plotter 返回空候选此前被当错误
 * （409「Plotter 返回空卷大纲候选」），封死作者的完结出口。
 */
class EmptyVolumeCandidateFinishesBookTest {

    @TempDir
    Path tempDir;
    private SqliteNovelRepository repo;
    private String pid;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("empty-candidate.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        Project p = new Project("空候选书", "构思", "武侠");
        p.setMode(ProjectMode.INTERACTIVE);
        p.setCurrentChapterIndex(2);
        repo.saveProject(p);
        pid = p.getId();
    }

    private NovelOrchestrator orchestratorWith(PlotterAgent plotter) {
        return new NovelOrchestrator(repo, new ProjectService(repo),
                null, null, plotter,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null);
    }

    /** 卷1（已写 1-2 章）+ 卷2（未写 3-5 章）+ 待审卷2大纲检查点。 */
    private Checkpoint seedOutlineWithVolumeTwoPending() {
        Outline outline = new Outline(pid, "总纲");
        Volume v1 = new Volume(pid, 1, "卷1", "");
        v1.addSynopsis(new ChapterSynopsis(pid, 1, "第1章", "已写"));
        v1.addSynopsis(new ChapterSynopsis(pid, 2, "第2章", "已写"));
        outline.addVolume(v1);
        Volume v2 = new Volume(pid, 2, "卷2", "");
        v2.addSynopsis(new ChapterSynopsis(pid, 3, "第3章", "未写"));
        v2.addSynopsis(new ChapterSynopsis(pid, 4, "第4章", "未写"));
        v2.addSynopsis(new ChapterSynopsis(pid, 5, "第5章", "未写"));
        outline.addVolume(v2);
        repo.saveOutline(outline);
        Checkpoint cp = new Checkpoint(pid, Checkpoint.Type.AFTER_VOLUME_OUTLINE,
                "卷2大纲已生成，待审核");
        repo.saveCheckpoint(cp);
        return cp;
    }

    @Test
    void 空候选移除未写章并触达成书门禁() {
        Checkpoint cp = seedOutlineWithVolumeTwoPending();
        PlotterAgent plotter = mock(PlotterAgent.class);
        when(plotter.proposeVolumeRevision(anyString(), anyString(), any(), anyInt(), anyString()))
                .thenReturn(new Volume(pid, 2, "卷2", "")); // 空候选
        NovelOrchestrator orch = orchestratorWith(plotter);

        var result = orch.reviseCheckpoint(pid, cp, "不要生成后续卷，直接完结");

        assertNotNull(result, "空候选应产生修订结果而非抛 409");
        Outline after = repo.findOutlineByProject(pid).orElseThrow();
        assertTrue(after.getVolumes().stream()
                        .flatMap(v -> v.getSynopses().stream())
                        .noneMatch(s -> s.getChapterIndex() > 2),
                "目标卷未写章（3-5）应被移除");
        Project p = repo.findProject(pid).orElseThrow();
        assertTrue(p.getState() == NovelState.FINAL || p.getState() == NovelState.NEEDS_REVISION,
                "大纲耗尽应走成书门禁，实际状态: " + p.getState());
    }

    @Test
    void 空候选在门禁失败时如实进入待修订() {
        Checkpoint cp = seedOutlineWithVolumeTwoPending();
        // 5 条 mustResolve 未回收 → 门禁硬性失败
        for (int i = 0; i < 5; i++) {
            com.writing.novel.domain.Foreshadowing f =
                    new com.writing.novel.domain.Foreshadowing(pid, "关键伏笔" + i, "埋设", 1);
            f.setMustResolve(true);
            repo.saveForeshadowing(f);
        }
        PlotterAgent plotter = mock(PlotterAgent.class);
        when(plotter.proposeVolumeRevision(anyString(), anyString(), any(), anyInt(), anyString()))
                .thenReturn(null); // null 候选同样视为全书完
        NovelOrchestrator orch = orchestratorWith(plotter);

        assertDoesNotThrow(() -> orch.reviseCheckpoint(pid, cp, "不要生成后续卷"));
        Project p = repo.findProject(pid).orElseThrow();
        assertEquals(NovelState.NEEDS_REVISION, p.getState(),
                "mustResolve>3 时门禁应如实 FAIL 进入 NEEDS_REVISION");
    }

    @Test
    void 非空候选仍走完整匹配校验() {
        Checkpoint cp = seedOutlineWithVolumeTwoPending();
        PlotterAgent plotter = mock(PlotterAgent.class);
        Volume wrong = new Volume(pid, 2, "卷2", "");
        wrong.addSynopsis(new ChapterSynopsis(pid, 3, "第3章", "只覆盖部分未写章"));
        when(plotter.proposeVolumeRevision(anyString(), anyString(), any(), anyInt(), anyString()))
                .thenReturn(wrong);
        NovelOrchestrator orch = orchestratorWith(plotter);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> orch.reviseCheckpoint(pid, cp, "只改一章"));
        assertTrue(ex.getMessage().contains("完整匹配"), "非空候选的章号集合校验应保留: " + ex.getMessage());
    }

    @Test
    void 大纲仍有未写章时空候选不触进门禁() {
        Checkpoint cp = seedOutlineWithVolumeTwoPending();
        // 另一个未写卷（卷3，第6章）
        Outline outline = repo.findOutlineByProject(pid).orElseThrow();
        Volume v3 = new Volume(pid, 3, "卷3", "");
        v3.addSynopsis(new ChapterSynopsis(pid, 6, "第6章", "未写"));
        outline.addVolume(v3);
        repo.saveOutline(outline);

        PlotterAgent plotter = mock(PlotterAgent.class);
        when(plotter.proposeVolumeRevision(anyString(), anyString(), any(), anyInt(), anyString()))
                .thenReturn(new Volume(pid, 2, "卷2", ""));
        NovelOrchestrator orch = orchestratorWith(plotter);

        assertDoesNotThrow(() -> orch.reviseCheckpoint(pid, cp, "删掉卷2"));
        Project p = repo.findProject(pid).orElseThrow();
        assertNotEquals(NovelState.FINAL, p.getState(), "仍有未写章（卷3）时不应自动完结");
        assertTrue(repo.findOutlineByProject(pid).orElseThrow().getVolumes().stream()
                .flatMap(v -> v.getSynopses().stream())
                .anyMatch(s -> s.getChapterIndex() == 6), "卷3 未写章应保留");
    }
}
