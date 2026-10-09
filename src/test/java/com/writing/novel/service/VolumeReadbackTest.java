package com.writing.novel.service;

import com.writing.novel.domain.*;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.orchestrator.NovelOrchestrator;
import com.writing.novel.orchestrator.ProjectService;
import com.writing.novel.persistence.sqlite.*;
import com.writing.novel.telemetry.EventLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-020（方案 §06/J08）：卷末回读报告。
 * 卷末生成回读报告（人物变化/节奏/承诺/偏离/建议）持久化；未确认回读阻断下一卷自动续写；
 * 确认后继续。
 */
class VolumeReadbackTest {

    @TempDir
    Path tempDir;

    private SqliteNovelRepository repo;
    private ConnectionProvider cp;
    private VolumeReadbackService service;
    private NovelOrchestrator orchestrator;
    private String projectId;
    private EventLog.Listener listener;
    private final List<String> events = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        cp = new ConnectionProvider(tempDir.resolve("readback.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);

        LlmGateway gateway = new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest req) {
                return new LlmResponse("""
                        {"characterChanges":"沈昭从等待者变成追问者；江砚的愧疚转为沉默的自我惩罚",
                         "pacing":"前半克制后半急促，焚信段张力到位但余韵偏短",
                         "promiseStatus":"'密信之谜'已兑现一半；'线人身份'仍未推进，建议下卷前半回收",
                         "deviationSummary":"第 8 章计划偏离（焚信替代交接）已按正文更新",
                         "suggestions":"下卷开篇给沈昭一段独处回望；线人线索在三章内推进"}
                        """, 200, 400);
            }
            @Override public void streamComplete(LlmRequest req, com.writing.novel.llm.client.StreamHandler h) {}
        };
        service = new VolumeReadbackService(repo, gateway,
                com.writing.novel.agent.TestPromptHelper.create(),
                new PlanDeviationRepository(cp), new VolumeReadbackRepository(cp));

        orchestrator = new NovelOrchestrator(repo, new ProjectService(repo),
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null);
        orchestrator.setVolumeReadbackService(service);

        Project project = new Project("回读项目", "构思", "武侠");
        repo.saveProject(project);
        projectId = project.getId();
        // 卷 1（章 1-2 已写）+ 卷 2（章 3 未写）
        Outline outline = new Outline(projectId, "总纲");
        Volume v1 = new Volume(projectId, 1, "第一卷", "卷一摘要");
        v1.getSynopses().add(new ChapterSynopsis(projectId, 1, "第一章", "开局"));
        v1.getSynopses().add(new ChapterSynopsis(projectId, 2, "第二章", "焚信"));
        Volume v2 = new Volume(projectId, 2, "第二卷", "卷二摘要");
        v2.getSynopses().add(new ChapterSynopsis(projectId, 3, "第三章", "追问"));
        outline.getVolumes().add(v1);
        outline.getVolumes().add(v2);
        repo.saveOutline(outline);
        Chapter ch1 = new Chapter(projectId, 1, "第一章");
        ch1.setContent("开局正文。".repeat(80));
        ch1.setStatus(Chapter.Status.DONE);
        repo.saveChapter(ch1);
        Chapter ch2 = new Chapter(projectId, 2, "第二章");
        ch2.setContent("焚信正文。".repeat(80));
        ch2.setStatus(Chapter.Status.DONE);
        repo.saveChapter(ch2);
        listener = e -> events.add(String.valueOf(e.get("event")));
        EventLog.subscribe(listener);
    }

    @AfterEach
    void tearDown() {
        EventLog.unsubscribe(listener);
    }

    @Test
    void 生成回读报告_落库含五区块() {
        var result = service.generate(projectId, 1);
        assertTrue(result.generated());
        var report = service.latest(projectId, 1).orElseThrow();
        assertEquals("PENDING_REVIEW", report.getStatus());
        assertTrue(report.getReportJson().contains("沈昭从等待者变成追问者"), "人物变化");
        assertTrue(report.getReportJson().contains("线人身份"), "承诺状态");
        assertTrue(report.getReportJson().contains("建议"), "建议区块（或偏离汇总出现）");
    }

    /** NCS-189: 已确认回读的结论文本应透传 pacing 节奏观察（此前被 buildReadbackContext 丢弃） */
    @Test
    void 已确认回读结论包含节奏观察() {
        service.generate(projectId, 1);
        service.acknowledge(projectId, 1);
        String ctx = service.buildReadbackContext(projectId);
        assertTrue(ctx.contains("节奏观察"), "pacing 字段应出现在回读结论中");
        assertTrue(ctx.contains("前半克制后半急促"), "pacing 内容应透传");
        assertTrue(ctx.contains("人物变化"), "既有字段不回归");
    }

    @Test
    void 未确认回读阻断下一卷自动续写_确认后放行() {
        // VOLUME_DONE 状态、当前章 2（卷 1 完成）
        Project p = repo.findProject(projectId).orElseThrow();
        p.setCurrentChapterIndex(2);
        p.setState(NovelState.VOLUME_DONE);
        repo.saveProject(p);

        // 无回读：卷过渡正常
        orchestrator.step(projectId);
        assertEquals(NovelState.WRITING, repo.findProject(projectId).orElseThrow().getState(),
                "无回读记录时卷过渡正常（旧行为兼容）");

        // 回到 VOLUME_DONE + 未确认回读 → 阻断（保持 VOLUME_DONE）
        p = repo.findProject(projectId).orElseThrow();
        p.setState(NovelState.VOLUME_DONE);
        repo.saveProject(p);
        service.generate(projectId, 1);
        orchestrator.step(projectId);
        assertEquals(NovelState.VOLUME_DONE, repo.findProject(projectId).orElseThrow().getState(),
                "未确认的卷末回读阻断下一卷自动续写");
        assertTrue(events.contains("VOLUME_READBACK_PENDING"), "阻断留事件");

        // 确认回读 → 放行
        service.acknowledge(projectId, 1);
        orchestrator.step(projectId);
        assertEquals(NovelState.WRITING, repo.findProject(projectId).orElseThrow().getState(),
                "回读确认后下一卷可继续");
    }

    @Test
    void 坏输出不落库_生成失败可重试() {
        var bad = new VolumeReadbackService(repo, new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest req) {
                return new LlmResponse("###ERR", 5, 5);
            }
            @Override public void streamComplete(LlmRequest req, com.writing.novel.llm.client.StreamHandler h) {}
        }, com.writing.novel.agent.TestPromptHelper.create(), new PlanDeviationRepository(cp),
                new VolumeReadbackRepository(cp));
        var result = bad.generate(projectId, 1);
        assertFalse(result.generated());
        assertTrue(service.latest(projectId, 1).isEmpty(), "坏输出不落库");
    }
}
