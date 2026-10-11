package com.writing.novel.service;

import com.writing.novel.domain.ChapterReview;
import com.writing.novel.domain.ChapterSynopsis;
import com.writing.novel.domain.Outline;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.Volume;
import com.writing.novel.domain.WritingPreferences;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.persistence.sqlite.VolumePacingDirectiveRepository;
import com.writing.novel.telemetry.EventLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-200: 卷级节奏闭环——卷末聚合纹理均值/读者子分/趋势/回读节奏为
 * VolumePacingDirective；注入下一卷 planVolume 梗概与逐章写作约束；
 * 迟滞原则：卷内设定点不变（新事件不改变已持久化指令）。
 */
class VolumePacingControllerTest {

    @TempDir
    Path tempDir;

    private SqliteNovelRepository repo;
    private VolumePacingController controller;
    private VolumePacingDirectiveRepository directiveRepo;
    private String pid;

    private void setUp() {
        var cp = new ConnectionProvider(tempDir.resolve("vp.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        directiveRepo = new VolumePacingDirectiveRepository(cp);
        controller = new VolumePacingController(directiveRepo, repo, null);
        var p = new Project("历史权谋", "庙堂", "历史");
        p.setGenre(com.writing.novel.domain.GenreProfile.HISTORICAL);
        repo.saveProject(p);
        pid = p.getId();
    }

    private void seedReviews(int from, int to, int score) {
        for (int i = from; i <= to; i++) {
            var review = new ChapterReview(pid, i);
            review.setOverallScore(score);
            repo.saveChapterReview(review);
        }
    }

    @Test
    void 卷末聚合纹理与读者分产生指令() {
        setUp();
        for (int i = 0; i < 3; i++) {
            EventLog.emit("TEXTURE_METRICS", Map.of(
                    "projectId", pid,
                    "dialogueParagraphRatio", 0.72,
                    "sceneBreaks", 3,
                    "dialoguePingPong", 2,
                    "avgParagraphLength", 210.0));
        }
        seedReviews(1, 4, 6);
        // 读者均分 6 < 7 → 指令应含张力点设定点

        var directive = controller.closeVolume(pid, 1, 1, 4, "PACING 检出连续 3 章对白超限");
        assertNotNull(directive);
        var saved = directiveRepo.findLatestBefore(pid, 2).orElseThrow();
        assertEquals(1, saved.getVolumeIndex());
        assertEquals(6.0, saved.getReaderAvg(), "读者均分聚合");
        assertTrue(saved.getDirectiveText().contains("对白段占比均值 72%"), "纹理均值进指令");
        assertTrue(saved.getDirectiveText().contains("压低对白密度"), "超限→确定性设定点");
        assertTrue(saved.getDirectiveText().contains("读者均分 6"), "读者分进指令");
        assertTrue(saved.getDirectiveText().contains("PACING"), "趋势告警进指令");
    }

    @Test
    void 迟滞_新事件不改变已持久化设定点() {
        setUp();
        EventLog.emit("TEXTURE_METRICS", Map.of("projectId", pid,
                "dialogueParagraphRatio", 0.72, "sceneBreaks", 3, "dialoguePingPong", 2, "avgParagraphLength", 210.0));
        controller.closeVolume(pid, 1, 1, 4, null);
        String before = controller.directiveFor(pid, 2);

        // 卷内新事件流入（未 closeVolume）→ 设定点不变
        for (int i = 0; i < 5; i++) {
            EventLog.emit("TEXTURE_METRICS", Map.of("projectId", pid,
                    "dialogueParagraphRatio", 0.1, "sceneBreaks", 1, "dialoguePingPong", 0, "avgParagraphLength", 20.0));
        }
        assertEquals(before, controller.directiveFor(pid, 2), "卷内设定点不变");
    }

    @Test
    void 下一卷约束映射_首卷为空_第二卷取前卷指令() {
        setUp();
        // 卷1: ch1-4；卷2: ch5-8
        Outline outline = new Outline(pid, "总纲");
        Volume v1 = new Volume(pid, 1, "卷一", "");
        v1.addSynopsis(new ChapterSynopsis(pid, 1, "第1章", "起"));
        v1.addSynopsis(new ChapterSynopsis(pid, 4, "第4章", "承"));
        Volume v2 = new Volume(pid, 2, "卷二", "");
        v2.addSynopsis(new ChapterSynopsis(pid, 5, "第5章", "转"));
        v2.addSynopsis(new ChapterSynopsis(pid, 6, "第6章", "合"));
        outline.addVolume(v1);
        outline.addVolume(v2);
        repo.saveOutline(outline);

        assertTrue(controller.constraintsFor(pid, 1).isEmpty(), "第一卷无前卷指令");
        assertTrue(controller.directiveFor(pid, 2).isEmpty(), "无指令时空串");

        controller.closeVolume(pid, 1, 1, 4, null);
        String c5 = controller.constraintsFor(pid, 5);
        assertFalse(c5.isEmpty(), "第二卷章节应取前卷指令");
        assertTrue(c5.contains("【卷级节奏指令"));
        assertEquals(c5, controller.constraintsFor(pid, 6), "同卷章节取同一指令（迟滞）");
    }

    /** NCS-200: 下一卷 planVolume 卷梗概携带节奏指令（tryExtendLayeredOutline 注入点） */
    @org.junit.jupiter.api.Test
    void 下一卷planVolume梗概注入指令(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir2) throws Exception {
        setUp();
        // 章节算术：8 章/卷——卷1=ch1-8；当前写到 ch8 → 延迟生成卷2（ch9-10）
        controller.closeVolume(pid, 1, 1, 8, null);

        var prefs = new WritingPreferences(pid);
        prefs.setTargetChapters(10);
        repo.saveWritingPreferences(prefs);
        Outline outline = new Outline(pid, "总纲");
        Volume v1 = new Volume(pid, 1, "卷一", "");
        v1.addSynopsis(new ChapterSynopsis(pid, 8, "第8章", "推进"));
        outline.addVolume(v1);
        repo.saveOutline(outline);
        var p = repo.findProject(pid).orElseThrow();
        p.setCurrentChapterIndex(8);
        repo.saveProject(p);

        com.writing.novel.agent.PlotterAgent plotterMock =
                org.mockito.Mockito.mock(com.writing.novel.agent.PlotterAgent.class);
        org.mockito.Mockito.when(plotterMock.planVolume(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn("第5章 新卷：场景节拍：无关内容");
        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        com.writing.novel.orchestrator.NovelOrchestrator orch =
                new com.writing.novel.orchestrator.NovelOrchestrator(repo,
                        new com.writing.novel.orchestrator.ProjectService(repo),
                        null, null, plotterMock,
                        null, null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, null, null, null, null);
        assertFalse(controller.directiveFor(pid, 2).isBlank(), "closeVolume 后应有可注入指令");
        orch.setVolumePacingController(controller);
        Method m = com.writing.novel.orchestrator.NovelOrchestrator.class
                .getDeclaredMethod("tryExtendLayeredOutline", String.class, Project.class);
        m.setAccessible(true);
        m.invoke(orch, pid, p);

        org.mockito.Mockito.verify(plotterMock).planVolume(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), captor.capture(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertTrue(captor.getValue().contains("【卷级节奏指令"),
                "planVolume 卷梗概应注入节奏指令: " + captor.getValue());
    }
}
