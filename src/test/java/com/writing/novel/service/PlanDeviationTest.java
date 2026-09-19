package com.writing.novel.service;

import com.writing.novel.domain.*;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.persistence.sqlite.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-019（方案 §06）：计划偏离对照与双向校正。
 * 每章记录合同/梗概偏离（已实现/自然替代/未实现/新机会，含证据）；
 * 大纲页三种决定：按正文更新计划（synopsis 更新）/修订正文（创建修订请求）/稍后决定；
 * 已写章不被计划更新改写。
 */
class PlanDeviationTest {

    @TempDir
    Path tempDir;

    private SqliteNovelRepository repo;
    private RevisionRequestService requests;
    private PlanDeviationService service;
    private String projectId;

    @BeforeEach
    void setUp() {
        var cp = new ConnectionProvider(tempDir.resolve("deviation.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        requests = new RevisionRequestService(new RevisionRequestRepository(cp));

        LlmGateway gateway = new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest req) {
                return new LlmResponse("""
                        {"realized":"江砚确实处置了密信——但方式是当众焚毁而非交出",
                         "replaced":"原计划的'交出密信保全自己'被自然替代为'焚毁密信保护众人'",
                         "unrealized":"'沈昭亲眼见证交接'未实现——她迟到一步只见灰烬",
                         "newOpportunity":"沈昭对灰烬的执念成为新的关系裂口线索",
                         "evidence":"他松开手，纸角卷起来，火舌吞掉了那行字"}
                        """, 150, 300);
            }
            @Override public void streamComplete(LlmRequest req, com.writing.novel.llm.client.StreamHandler h) {}
        };
        service = new PlanDeviationService(repo, requests, gateway,
                com.writing.novel.agent.TestPromptHelper.create(),
                new PlanDeviationRepository(cp));

        Project project = new Project("偏离项目", "构思", "武侠");
        repo.saveProject(project);
        projectId = project.getId();
        Outline outline = new Outline(projectId, "总纲");
        Volume v = new Volume(projectId, 1, "第一卷", "卷摘要");
        ChapterSynopsis s = new ChapterSynopsis(projectId, 8, "第八章", "江砚把密信交给沈昭");
        v.getSynopses().add(s);
        outline.getVolumes().add(v);
        repo.saveOutline(outline);
        Chapter ch = new Chapter(projectId, 8, "第八章");
        ch.setContent("江砚把密信凑近烛火。他松开手，纸角卷起来，火舌吞掉了那行字。".repeat(30));
        ch.setStatus(Chapter.Status.DONE);
        repo.saveChapter(ch);
    }

    @Test
    void 分析落库_偏离四类与证据() {
        var result = service.analyze(projectId, 8);
        assertTrue(result.analyzed());
        var deviations = service.list(projectId);
        assertEquals(1, deviations.size());
        var d = deviations.get(0);
        assertEquals(8, d.getChapterIndex());
        assertTrue(d.getDeviationJson().contains("焚毁"));
        assertTrue(d.getDeviationJson().contains("火舌吞掉了那行字"), "证据引文保留");
        assertNull(d.getDecision(), "新偏离尚无决定");
    }

    @Test
    void 按正文更新计划_synopsis更新且已写章不动() {
        service.analyze(projectId, 8);
        service.decide(projectId, 8, "UPDATE_PLAN_FROM_TEXT");

        var d = service.list(projectId).get(0);
        assertEquals("UPDATE_PLAN_FROM_TEXT", d.getDecision());
        assertNotNull(d.getDecidedAt());
        // synopsis 更新为实际发生（实现项作为新梗概）
        var synopsis = repo.findOutlineByProject(projectId).orElseThrow()
                .getVolumes().get(0).getSynopses().get(0);
        assertTrue(synopsis.getSynopsis().contains("焚毁密信"), "计划按正文更新（实际发生成为新计划）");
        // 已写章正文不被改写
        var chapter = repo.findChapterByIndex(projectId, 8).orElseThrow();
        assertTrue(chapter.getContent().contains("火舌吞掉了那行字"), "已写章不被计划更新改写");
    }

    @Test
    void 修订正文决定_创建修订请求() {
        service.analyze(projectId, 8);
        service.decide(projectId, 8, "REVISE_TEXT");

        var d = service.list(projectId).get(0);
        assertEquals("REVISE_TEXT", d.getDecision());
        var rr = requests.list(projectId);
        assertEquals(1, rr.size(), "修订正文决定创建统一修订请求（进入修订工作区）");
        assertEquals("PLAN_DEVIATION", rr.get(0).getOrigin().name());
    }

    @Test
    void 稍后决定_挂起可再决定() {
        service.analyze(projectId, 8);
        service.decide(projectId, 8, "DEFER");
        assertEquals("DEFER", service.list(projectId).get(0).getDecision());
        // 挂起后仍可改为按正文更新
        service.decide(projectId, 8, "UPDATE_PLAN_FROM_TEXT");
        assertEquals("UPDATE_PLAN_FROM_TEXT", service.list(projectId).get(0).getDecision());
    }

    @Test
    void 坏输出不落库() {
        var bad = new PlanDeviationService(repo, requests, new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest req) {
                return new LlmResponse("###ERR", 5, 5);
            }
            @Override public void streamComplete(LlmRequest req, com.writing.novel.llm.client.StreamHandler h) {}
        }, com.writing.novel.agent.TestPromptHelper.create(), new PlanDeviationRepository(
                new ConnectionProvider(tempDir.resolve("deviation.db").toString())));
        var result = bad.analyze(projectId, 8);
        assertFalse(result.analyzed());
        assertTrue(service.list(projectId).isEmpty());
    }

    @Test
    void 无大纲或无正文_明确失败不半套() {
        assertThrows(IllegalArgumentException.class, () -> service.analyze(projectId, 99));
    }
}
