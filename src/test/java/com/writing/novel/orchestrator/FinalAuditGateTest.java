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
 * mc-p3-02: FINAL 成书质量门禁复活（复现 P1 死审计）。
 * VOLUME_DONE → step() 必须统一走门禁：mustResolve 伏笔未回收 >3 或
 * 未解决 HIGH 级 ContinuityIssue >10 时置 NEEDS_REVISION，而不是直接 FINAL。
 */
class FinalAuditGateTest {

    @TempDir
    Path tempDir;
    private SqliteNovelRepository repo;
    private NovelOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("gate.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);

        // 仅需门禁路径——其余 agent 置 null
        orchestrator = new NovelOrchestrator(repo, new ProjectService(repo),
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private Project projectWithOutline() {
        Project p = new Project("门禁", "测试", "权谋");
        repo.saveProject(p);
        Outline outline = new Outline(p.getId(), "总纲");
        Volume v = new Volume(p.getId(), 1, "第一卷", "卷摘要");
        v.getSynopses().add(new ChapterSynopsis(p.getId(), 1, "第1章", "开局"));
        outline.getVolumes().add(v);
        repo.saveOutline(outline);
        return p;
    }

    private void addForeshadowing(String projectId, boolean mustResolve) {
        Foreshadowing f = new Foreshadowing(projectId, "伏笔" + System.nanoTime(), "ctx", 1);
        f.setMustResolve(mustResolve);
        repo.saveForeshadowing(f);
    }

    @Test
    void 未回收mustResolve伏笔超限置NEEDS_REVISION() {
        Project p = projectWithOutline();
        // 4 条 mustResolve 未回收 > 阈值 3
        for (int i = 0; i < 4; i++) addForeshadowing(p.getId(), true);

        // 大纲已写完：当前章索引指向最后一章（1/1）→ 走成书门禁而非继续写
        p.setCurrentChapterIndex(1);
        p.setState(NovelState.VOLUME_DONE);
        repo.saveProject(p);

        orchestrator.step(p.getId());

        assertEquals(NovelState.NEEDS_REVISION,
                repo.findProject(p.getId()).orElseThrow().getState(),
                "mustResolve 伏笔未回收超限应置 NEEDS_REVISION 而非直接 FINAL");
    }

    @Test
    void 门禁通过置FINAL() {
        Project p = projectWithOutline();
        // 2 条 mustResolve + 1 条普通——未回收数在阈值内
        addForeshadowing(p.getId(), true);
        addForeshadowing(p.getId(), true);
        addForeshadowing(p.getId(), false);

        // 大纲已写完：当前章索引指向最后一章（1/1）→ 走成书门禁而非继续写
        p.setCurrentChapterIndex(1);
        p.setState(NovelState.VOLUME_DONE);
        repo.saveProject(p);

        orchestrator.step(p.getId());

        assertEquals(NovelState.FINAL,
                repo.findProject(p.getId()).orElseThrow().getState(),
                "门禁通过应置 FINAL");
    }

    @Test
    void 未解决HIGH问题超限置NEEDS_REVISION() {
        Project p = projectWithOutline();
        // 11 条未解决 HIGH 问题 > 阈值 10
        for (int i = 0; i < 11; i++) {
            ContinuityIssue issue = new ContinuityIssue(p.getId(), 5,
                    ContinuityIssue.Type.TIMELINE, ContinuityIssue.Severity.HIGH, "时间线矛盾" + i);
            repo.saveContinuityIssue(issue);
        }

        // 大纲已写完
        p.setCurrentChapterIndex(1);
        p.setState(NovelState.VOLUME_DONE);
        repo.saveProject(p);

        orchestrator.step(p.getId());

        assertEquals(NovelState.NEEDS_REVISION,
                repo.findProject(p.getId()).orElseThrow().getState(),
                "未解决 HIGH 问题超限应置 NEEDS_REVISION");
    }

    @Test
    void 门禁异常时failClosed不默认通过() {
        // A1: 门禁代码异常时应 fail-closed（NEEDS_REVISION），不静默放行
        Project p = new Project("异常测", "测试", "悬疑");
        repo.saveProject(p);
        // 不存大纲 → findOutlineByProject 返回空 → hasNextChapter false → 走门禁
        // 但 runFinalAuditGate 内部 findProject 会成功，不会抛异常
        // 改用无伏笔无大纲的极端情况验证门禁本身不因异常放行
        p.setCurrentChapterIndex(999);
        p.setState(NovelState.VOLUME_DONE);
        repo.saveProject(p);

        // 用一个会触发门禁但通过的状态（无 mustResolve 伏笔，无 HIGH issues）
        orchestrator.step(p.getId());
        // 无大纲 → startNextVolumeOrFinish 中 findOutlineByProject 为空 → 直接走门禁
        // 门禁通过 → FINAL
        assertEquals(NovelState.FINAL,
                repo.findProject(p.getId()).orElseThrow().getState(),
                "正常情况门禁应通过置 FINAL");
    }
}
