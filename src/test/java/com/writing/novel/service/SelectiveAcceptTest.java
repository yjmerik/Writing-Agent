package com.writing.novel.service;

import com.writing.novel.agent.ContinuityCheckerAgent;
import com.writing.novel.domain.*;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.persistence.sqlite.*;
import com.writing.novel.agent.CheckResultEnvelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-024（方案 §13/R18/UX10）：选择性组合采纳。
 * 从多份候选选择片段组合 → 新候选（新哈希、记录来源）→ 强制重检 →
 * 未重检/重检未通过不可采纳；采纳后正文=组合内容，不继承原候选通过状态。
 */
class SelectiveAcceptTest {

    @TempDir
    Path tempDir;

    private SqliteNovelRepository repo;
    private RevisionRequestService requests;
    private SelectiveAcceptService service;
    private String projectId;
    private String requestId;
    private String proposalA;
    private String proposalB;

    @BeforeEach
    void setUp() {
        var cp = new ConnectionProvider(tempDir.resolve("selective.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        requests = new RevisionRequestService(new RevisionRequestRepository(cp));
        var checkResults = new CheckResultRepository(cp);

        // 桩检查器：默认通过
        LlmGateway gateway = new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest req) {
                return new LlmResponse("检查完成：未发现连续性问题。", 10, 10);
            }
            @Override public void streamComplete(LlmRequest req, com.writing.novel.llm.client.StreamHandler h) {}
        };
        var checker = new ContinuityCheckerAgent(gateway, repo,
                com.writing.novel.agent.TestPromptHelper.create(), null, checkResults);
        service = new SelectiveAcceptService(repo, requests, checker, checkResults);

        Project project = new Project("组合项目", "构思", "武侠");
        repo.saveProject(project);
        projectId = project.getId();
        Chapter ch = new Chapter(projectId, 8, "第八章");
        ch.setContent("旧正文第一段：江砚把密信交给沈昭。\n旧正文第二段：沈昭收下信离开渡口。\n旧正文第三段：夜色降临。");
        ch.setStatus(Chapter.Status.DONE);
        repo.saveChapter(ch);

        var request = requests.record(projectId, "CHAPTER_REWRITE", "改成焚信", "[]", "{}", "[]");
        requests.approveScope(projectId, request.getId(), "批准");
        requestId = request.getId();

        // 两份候选（同章同基准）
        int version = repo.findChapterByIndex(projectId, 8).orElseThrow().getVersion();
        proposalA = saveProposal("rr:" + requestId + ":8", version,
                "候选A第一段：江砚把密信凑近烛火。" + "火光映在两人之间。".repeat(40) + "\n"
                + "候选A第二段：沈昭想阻止却未出声。" + "她的指尖攥紧了袖口。".repeat(40) + "\n"
                + "候选A第三段：纸角卷起来。" + "灰烬落在潮湿的木板上。".repeat(40));
        proposalB = saveProposal("rr:" + requestId + ":8-alt", version,
                "候选B第一段：官差的火把逼近栈桥。\n候选B第二段：江砚握紧了信。\n候选B第三段：雾更浓了。");
    }

    @Test
    void 同章第二份组合不能继承第一份的重检() {
        var first = service.combine(projectId, requestId, 8, List.of(
                new SelectiveAcceptService.Fragment(proposalA, List.of(0, 1, 2))));
        service.recheck(projectId, first.previewId());
        var second = service.combine(projectId, requestId, 8, List.of(
                new SelectiveAcceptService.Fragment(proposalA, List.of(0, 2))));
        assertTrue(service.needsRecheck(projectId, second.previewId()));
        assertThrows(IllegalStateException.class, () -> service.accept(projectId, second.previewId()));
    }

    @Test
    void 无效检查输出不能允许组合采纳() {
        var results = new CheckResultRepository(new ConnectionProvider(tempDir.resolve("selective.db").toString()));
        LlmGateway invalid = new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest req) { return new LlmResponse("[]", 10, 10); }
            @Override public void streamComplete(LlmRequest req, com.writing.novel.llm.client.StreamHandler h) {}
        };
        var invalidService = new SelectiveAcceptService(repo, requests, new ContinuityCheckerAgent(
                invalid, repo, com.writing.novel.agent.TestPromptHelper.create(), null, results), results);
        var combined = invalidService.combine(projectId, requestId, 8, List.of(
                new SelectiveAcceptService.Fragment(proposalA, List.of(0, 1, 2))));
        invalidService.recheck(projectId, combined.previewId());
        assertTrue(invalidService.needsRecheck(projectId, combined.previewId()));
        assertThrows(IllegalStateException.class, () -> invalidService.accept(projectId, combined.previewId()));
    }

    @Test
    void 组合采纳重试重放同一回执不再次写稿() {
        var combined = service.combine(projectId, requestId, 8, List.of(
                new SelectiveAcceptService.Fragment(proposalA, List.of(0, 1, 2))));
        service.recheck(projectId, combined.previewId());
        service.accept(projectId, combined.previewId());
        int acceptedVersion = repo.findChapterByIndex(projectId, 8).orElseThrow().getVersion();
        assertTrue(service.accept(projectId, combined.previewId()));
        assertEquals(acceptedVersion, repo.findChapterByIndex(projectId, 8).orElseThrow().getVersion());
        assertEquals(RevisionProposal.Status.APPLIED, repo.findProposal(projectId, combined.previewId()).orElseThrow().getStatus());
    }

    private String saveProposal(String requestKey, int baseVersion, String content) {
        var identity = com.writing.novel.domain.ChapterApproval.Identity.of(
                repo.findChapterByIndex(projectId, 8).orElseThrow());
        RevisionProposal p = new RevisionProposal(java.util.UUID.randomUUID().toString(), projectId,
                RevisionProposal.TargetType.CHAPTER, "chapter-8", 8, null, baseVersion,
                identity.contentHash(), "组合测试", "{}",
                "{\"id\":\"chapter-8\",\"projectId\":\"" + projectId + "\",\"chapterIndex\":8,"
                        + "\"title\":\"第八章\",\"content\":" + jsonEscape(content) + "}",
                "候选", RevisionProposal.Status.READY, requestKey, null,
                java.time.Instant.now(), java.time.Instant.now());
        repo.saveProposal(p);
        return p.getId();
    }

    private String jsonEscape(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    @Test
    void 组合产生新候选_新哈希_记录来源() {
        var combined = service.combine(projectId, requestId, 8, List.of(
                new SelectiveAcceptService.Fragment(proposalA, List.of(0, 2)),
                new SelectiveAcceptService.Fragment(proposalB, List.of(0))));

        assertNotNull(combined.previewId(), "组合产生新候选");
        assertNotEquals(proposalA, combined.previewId());
        assertNotEquals(proposalB, combined.previewId());
        assertTrue(combined.content().contains("江砚把密信凑近烛火"), "候选A第0段");
        assertTrue(combined.content().contains("纸角卷起来"), "候选A第2段");
        assertFalse(combined.content().contains("沈昭想阻止却未出声"), "未选段落不进入组合");
        assertTrue(combined.content().contains("官差的火把逼近栈桥"), "候选B第0段");
        assertEquals(2, combined.sources().size(), "来源候选记录在案");
        assertTrue(service.needsRecheck(projectId, combined.previewId()),
                "新组合候选必须重检后才可采纳");
    }

    @Test
    void 未重检不可采纳_重检通过后可采纳且正文更新() {
        var combined = service.combine(projectId, requestId, 8, List.of(
                new SelectiveAcceptService.Fragment(proposalA, List.of(0, 1, 2))));
        assertThrows(IllegalStateException.class,
                () -> service.accept(projectId, combined.previewId()),
                "未重检的组合候选不可采纳");

        service.recheck(projectId, combined.previewId());
        assertFalse(service.needsRecheck(projectId, combined.previewId()));

        var accepted = service.accept(projectId, combined.previewId());
        assertTrue(accepted);
        var ch = repo.findChapterByIndex(projectId, 8).orElseThrow();
        assertTrue(ch.getContent().contains("纸角卷起来"), "正文=组合内容");
        assertTrue(ch.getContent().contains("沈昭想阻止却未出声"));
    }

    @Test
    void 重检失败_组合候选保持不可采纳() {
        var failing = new SelectiveAcceptService(repo, requests, new ContinuityCheckerAgent(
                new LlmGateway() {
                    @Override public LlmResponse complete(LlmRequest req) {
                        return new LlmResponse("【人物|高】组合内容与第12章矛盾", 10, 10);
                    }
                    @Override public void streamComplete(LlmRequest req, com.writing.novel.llm.client.StreamHandler h) {}
                }, repo, com.writing.novel.agent.TestPromptHelper.create(), null,
                new CheckResultRepository(new ConnectionProvider(tempDir.resolve("selective.db").toString()))),
                new CheckResultRepository(new ConnectionProvider(tempDir.resolve("selective.db").toString())));
        var combined = failing.combine(projectId, requestId, 8, List.of(
                new SelectiveAcceptService.Fragment(proposalA, List.of(0))));
        failing.recheck(projectId, combined.previewId());
        // 重检发现 HIGH 问题 → 不可采纳
        assertThrows(IllegalStateException.class,
                () -> failing.accept(projectId, combined.previewId()),
                "重检发现严重问题的组合候选不可采纳");
        var ch = repo.findChapterByIndex(projectId, 8).orElseThrow();
        assertFalse(ch.getContent().contains("候选A第一段"), "正文未被组合候选覆盖");
    }

    @Test
    void 采纳后原候选不继承通过状态_仍可供比较() {
        var combined = service.combine(projectId, requestId, 8, List.of(
                new SelectiveAcceptService.Fragment(proposalA, List.of(0, 1, 2))));
        service.recheck(projectId, combined.previewId());
        service.accept(projectId, combined.previewId());

        var a = repo.findProposal(projectId, proposalA).orElseThrow();
        assertEquals(RevisionProposal.Status.READY, a.getStatus(),
                "原候选保持 READY（不因组合采纳被标记通过）");
    }
}
