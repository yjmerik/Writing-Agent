package com.writing.novel.service;

import com.writing.novel.domain.*;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.orchestrator.NovelOrchestrator;
import com.writing.novel.persistence.sqlite.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-021（方案 §06/J08）：按正文更新计划——以卷摘要+承诺状态+人物变化（回读结论）
 * 为输入再生成未写部分；产生统一修订请求留痕；已写章不动。
 */
class ReplanFromTextTest {

    @TempDir
    Path tempDir;

    private SqliteNovelRepository repo;
    private RevisionRequestService requests;
    private VolumeReadbackService readbackService;
    private NovelOrchestrator orchestrator;
    private String projectId;

    @BeforeEach
    void setUp() {
        var cp = new ConnectionProvider(tempDir.resolve("replan.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        requests = new RevisionRequestService(new RevisionRequestRepository(cp));

        LlmGateway gateway = new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest req) {
                return new LlmResponse("{\"characterChanges\":\"沈昭从等待者变成追问者\",\"suggestions\":\"下卷开篇给沈昭独处回望\"}",
                        100, 200);
            }
            @Override public void streamComplete(LlmRequest req, com.writing.novel.llm.client.StreamHandler h) {}
        };
        readbackService = new VolumeReadbackService(repo, gateway,
                com.writing.novel.agent.TestPromptHelper.create(),
                new PlanDeviationRepository(cp), new VolumeReadbackRepository(cp));

        orchestrator = Mockito.spy(new NovelOrchestrator(repo, null,
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null));
        orchestrator.setVolumeReadbackService(readbackService);

        Project project = new Project("再规划项目", "构思", "武侠");
        repo.saveProject(project);
        projectId = project.getId();
        Outline outline = new Outline(projectId, "总纲");
        Volume v1 = new Volume(projectId, 1, "第一卷", "卷一摘要");
        v1.getSynopses().add(new ChapterSynopsis(projectId, 1, "第一章", "开局（已写）"));
        Volume v2 = new Volume(projectId, 2, "第二卷", "旧计划（未写）");
        v2.getSynopses().add(new ChapterSynopsis(projectId, 2, "第二章", "旧计划章"));
        outline.getVolumes().add(v1);
        outline.getVolumes().add(v2);
        repo.saveOutline(outline);
        Chapter ch1 = new Chapter(projectId, 1, "第一章");
        ch1.setContent("开局正文。".repeat(80));
        ch1.setStatus(Chapter.Status.DONE);
        repo.saveChapter(ch1);
    }

    @Test
    void 回读结论进入再规划输入上下文() {
        readbackService.generate(projectId, 1);
        readbackService.acknowledge(projectId, 1);
        String context = readbackService.buildReadbackContext(projectId);
        assertTrue(context.contains("沈昭从等待者变成追问者"), "人物变化进入再规划输入");
        assertTrue(context.contains("下卷开篇给沈昭独处回望"), "回读建议进入再规划输入");
        // 无确认回读 → 空上下文（不影响既有输入）
        assertTrue(readbackService.buildReadbackContext("no-such") == null
                || readbackService.buildReadbackContext("no-such").isEmpty());
    }

    @Test
    void 按正文更新计划_调用再规划_登记留痕_已写章不动() {
        readbackService.generate(projectId, 1);
        readbackService.acknowledge(projectId, 1);
        var ch1SynopsisBefore = repo.findOutlineByProject(projectId).orElseThrow()
                .getVolumes().get(0).getSynopses().get(0).getSynopsis();

        var service = new ReplanFromTextService(orchestrator, requests, readbackService);
        var result = service.replanFromText(projectId, 1);

        assertTrue(result.replanned(), "触发了未写部分再规划");
        Mockito.verify(orchestrator).replanRemaining(projectId);
        // 统一修订请求留痕（PLAN_ADJUST 语义：计划已按正文更新）
        var rr = requests.list(projectId);
        assertEquals(1, rr.size());
        assertEquals("PLAN_ADJUST", rr.get(0).getOrigin().name());
        assertTrue(rr.get(0).getIntent().contains("按正文更新计划"));
        // 已写章梗概不被改写
        var ch1SynopsisAfter = repo.findOutlineByProject(projectId).orElseThrow()
                .getVolumes().get(0).getSynopses().get(0).getSynopsis();
        assertEquals(ch1SynopsisBefore, ch1SynopsisAfter, "已写章梗概保持不变");
    }

    @Test
    void 回读未确认时拒绝按正文更新() {
        readbackService.generate(projectId, 1); // PENDING_REVIEW
        var service = new ReplanFromTextService(orchestrator, requests, readbackService);
        assertThrows(IllegalStateException.class, () -> service.replanFromText(projectId, 1),
                "回读未确认——先读回读并确认，再更新计划");
        Mockito.verify(orchestrator, Mockito.never()).replanRemaining(projectId);
    }
}
