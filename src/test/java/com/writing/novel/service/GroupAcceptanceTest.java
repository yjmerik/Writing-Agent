package com.writing.novel.service;

import com.writing.novel.agent.ContentRevisionAgent;
import com.writing.novel.domain.*;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.orchestrator.NovelOrchestrator;
import com.writing.novel.persistence.sqlite.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-016（方案 §12/R14/F01）：依赖组整组接受与重基。
 * 整组原子（中间失败回滚无半套）；基准漂移拒绝且候选保留（REBASED）；
 * commandId 幂等；保护锚点未变核对。
 */
class GroupAcceptanceTest {

    @TempDir
    Path tempDir;

    private SqliteNovelRepository repo;
    private RevisionRequestService requests;
    private RevisionRepairService repairService;
    private GroupAcceptanceService acceptance;
    private String projectId;

    @BeforeEach
    void setUp() {
        var cp = new ConnectionProvider(tempDir.resolve("group.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        requests = new RevisionRequestService(new RevisionRequestRepository(cp));

        AtomicInteger callCount = new AtomicInteger();
        LlmGateway gateway = new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest req) {
                callCount.incrementAndGet();
                String sp = req.getSystemPrompt() == null ? "" : req.getSystemPrompt();
                var ch = repo.findChaptersByProject(projectId).stream()
                        .filter(c -> sp.contains(c.getId()))
                        .findFirst().orElse(null);
                try {
                    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    var structured = mapper.createObjectNode();
                    structured.put("id", ch == null ? "unknown" : ch.getId());
                    structured.put("projectId", projectId);
                    structured.put("chapterIndex", ch == null ? 0 : ch.getChapterIndex());
                    structured.put("title", ch == null ? "修复" : ch.getTitle());
                    String content = "修复后的依赖一致正文：沈昭只凭残端口供指认，密信原件已不存在于世。".repeat(60);
                    structured.put("content", content);
                    var node = mapper.createObjectNode();
                    node.put("targetType", "CHAPTER");
                    node.put("title", ch == null ? "修复" : ch.getTitle());
                    node.put("content", content);
                    node.put("structuredJson", mapper.writeValueAsString(structured));
                    node.put("summary", "修复后文依赖");
                    node.putArray("directiveResults");
                    return new LlmResponse(mapper.writeValueAsString(node), 300, 600);
                } catch (Exception e) { throw new RuntimeException(e); }
            }
            @Override public void streamComplete(LlmRequest req, com.writing.novel.llm.client.StreamHandler h) {}
        };
        var revisionAgent = new ContentRevisionAgent(gateway, com.writing.novel.agent.TestPromptHelper.create());
        var agentRevision = new AgentRevisionService(repo, revisionAgent, null, null,
                org.mockito.Mockito.mock(NovelOrchestrator.class),
                new com.fasterxml.jackson.databind.ObjectMapper(), null);
        repairService = new RevisionRepairService(
                new ImpactAnalysisService(repo, new SqliteStoryCanon(new StoryFactRepository(cp), repo),
                        new StoryFactRepository(cp)),
                agentRevision, requests, repo);
        acceptance = new GroupAcceptanceService(repo, requests,
                new ChapterRevisionService(repo, null, null));

        Project project = new Project("整组项目", "构思", "武侠");
        repo.saveProject(project);
        projectId = project.getId();

        // 密信场景：8 修订 + 12/20 依赖
        Chapter ch8 = new Chapter(projectId, 8, "第八章");
        ch8.setContent("江砚把密信交给沈昭，信中名单足以致命。结尾原句保持不变。");
        ch8.setStatus(Chapter.Status.DONE);
        repo.saveChapter(ch8);
        Chapter ch12 = new Chapter(projectId, 12, "第十二章");
        ch12.setContent("沈昭凭着那封密信里的名单，向官差告了密。");
        ch12.setStatus(Chapter.Status.DONE);
        repo.saveChapter(ch12);
        Chapter ch20 = new Chapter(projectId, 20, "第二十章");
        ch20.setContent("官差在堂上展示了密信原件，字迹确凿。");
        ch20.setStatus(Chapter.Status.DONE);
        repo.saveChapter(ch20);
        var canon = new SqliteStoryCanon(new StoryFactRepository(cp), repo);
        canon.harvest(new StoryFact(projectId, StoryFact.FactType.OBJECT_STATE,
                "密信", "密信由江砚交给沈昭", 8));
        var current8 = repo.findChapterByIndex(projectId, 8).orElseThrow();
        current8.setContent("江砚把密信凑近烛火，当着沈昭的面焚毁了它。结尾原句保持不变。");
        repo.saveChapter(current8);
    }

    private String prepareRequest() {
        var request = requests.record(projectId, "CHAPTER_REWRITE", "改成当场焚信",
                "[]", "{}", "[{\"chapterIndex\":20,\"span\":\"结尾原句保持不变\"}]");
        requests.approveScope(projectId, request.getId(), "批准");
        var gen = repairService.generateRepairs(projectId, request.getId(), 8, "改成当场焚信");
        assertTrue(gen.generated() >= 1, "前置：至少 1 个修复候选（12/20 章）");
        return request.getId();
    }

    @Test
    void 整组接受成功_全部应用_请求完成() {
        String requestId = prepareRequest();
        var v12 = repo.findChapterByIndex(projectId, 12).orElseThrow().getVersion();
        var v20 = repo.findChapterByIndex(projectId, 20).orElseThrow().getVersion();

        var receipt = acceptance.acceptGroup(projectId, requestId, "cmd-group-1");

        assertTrue(receipt.applied());
        assertEquals("cmd-group-1", receipt.commandId());
        // 全部候选章被应用（版本推进）
        assertTrue(repo.findChapterByIndex(projectId, 12).orElseThrow().getVersion() > v12);
        assertTrue(repo.findChapterByIndex(projectId, 20).orElseThrow().getVersion() > v20);
        assertEquals("COMPLETED", requests.get(projectId, requestId).getStatus().name());
        assertTrue(requests.get(projectId, requestId).getAcceptance().contains("cmd-group-1"));
    }

    @Test
    void 中间失败回滚_无半套状态() {
        String requestId = prepareRequest();
        // 制造第二章（按序的最后一章）基准漂移：并发推进其版本
        var ch20 = repo.findChapterByIndex(projectId, 20).orElseThrow();
        ch20.setContent("官差在堂上展示了另一份文书。");
        repo.saveChapter(ch20);
        var v12Before = repo.findChapterByIndex(projectId, 12).orElseThrow().getVersion();

        assertThrows(IllegalStateException.class,
                () -> acceptance.acceptGroup(projectId, requestId, "cmd-group-2"),
                "基准漂移必须使整组接受失败");

        // 无半套：第 12 章未被应用（版本不变）
        assertEquals(v12Before, repo.findChapterByIndex(projectId, 12).orElseThrow().getVersion(),
                "整组失败时不得有半套应用（先应用的章已回滚或未应用）");
    }

    @Test
    void 基准漂移_候选保留标记REBASED() {
        String requestId = prepareRequest();
        var ch12 = repo.findChapterByIndex(projectId, 12).orElseThrow();
        ch12.setContent("沈昭凭记忆复述了名单。");
        repo.saveChapter(ch12);

        assertThrows(IllegalStateException.class,
                () -> acceptance.acceptGroup(projectId, requestId, "cmd-group-3"));

        var proposals = repo.listProposals(projectId).stream()
                .filter(p -> p.getRequestKey().startsWith("rr:" + requestId)).toList();
        assertTrue(proposals.stream().anyMatch(p -> p.getStatus() == RevisionProposal.Status.STALE),
                "基准漂移的候选保留并标 STALE（可比较，不丢弃）");
        assertEquals("SCOPE_APPROVED", requests.get(projectId, requestId).getStatus().name(),
                "请求回到可重基状态（非半完成）");
    }

    @Test
    void commandId幂等_重复接受返回同收据() {
        String requestId = prepareRequest();
        var first = acceptance.acceptGroup(projectId, requestId, "cmd-idem");
        assertTrue(first.applied());
        // 重复同一 commandId → 幂等重放（不再次推进版本）
        var v12 = repo.findChapterByIndex(projectId, 12).orElseThrow().getVersion();
        var second = acceptance.acceptGroup(projectId, requestId, "cmd-idem");
        assertEquals(first.commandId(), second.commandId());
        assertTrue(second.replayed());
        assertEquals(v12, repo.findChapterByIndex(projectId, 12).orElseThrow().getVersion(),
                "幂等重放不重复写正文");
    }

    @Test
    void 第二章提交中断不得留下第一章新稿或已采纳回执() throws Exception {
        String requestId = prepareRequest();
        var before12 = repo.findChapterByIndex(projectId, 12).orElseThrow();
        try (var conn = new ConnectionProvider(tempDir.resolve("group.db").toString()).getConnection();
             var st = conn.createStatement()) {
            st.execute("CREATE TRIGGER fail_second BEFORE UPDATE ON chapters WHEN EXISTS (SELECT 1 FROM revision_proposals WHERE project_id=NEW.project_id AND status='APPLIED') BEGIN SELECT RAISE(ABORT, '模拟第二章提交中断'); END");
        }
        assertThrows(RuntimeException.class, () -> acceptance.acceptGroup(projectId, requestId, "interrupted"));
        var after12 = repo.findChapterByIndex(projectId, 12).orElseThrow();
        assertEquals(before12.getContent(), after12.getContent());
        assertEquals(before12.getVersion(), after12.getVersion());
        assertTrue(repo.listProposals(projectId).stream().filter(p -> p.getRequestKey().startsWith("rr:" + requestId))
                .allMatch(p -> p.getStatus() == RevisionProposal.Status.READY && p.getResultJson() == null));
        assertEquals("SCOPE_APPROVED", requests.get(projectId, requestId).getStatus().name());
    }

    @Test
    void 保护锚点被候选改动时拒绝() {
        String requestId = prepareRequest();
        // 把保护 span 放进第 20 章候选章：候选内容不含"结尾原句保持不变"→ 若保护锚点声明于该章则拒绝
        // （本场景保护锚点在修订章 8——修复候选在 12/20 章；此处验证保护核对逻辑对候选章生效）
        var request = requests.get(projectId, requestId);
        // 重新登记一个带候选章保护锚点的请求
        var r2 = requests.record(projectId, "CHAPTER_REWRITE", "保护核对场景",
                "[]", "{}", "[{\"chapterIndex\":12,\"span\":\"向官差告了密\"}]");
        requests.approveScope(projectId, r2.getId(), "批准");
        repairService.generateRepairs(projectId, r2.getId(), 8, "改成当场焚信");
        // 第 12 章候选内容（桩）不含保护 span「向官差告了密」→ 拒绝
        var v12 = repo.findChapterByIndex(projectId, 12).orElseThrow().getVersion();
        assertThrows(IllegalStateException.class,
                () -> acceptance.acceptGroup(projectId, r2.getId(), "cmd-protect"),
                "候选移除了保护锚点声明的原文 span → 整组拒绝");
        assertEquals(v12, repo.findChapterByIndex(projectId, 12).orElseThrow().getVersion(),
                "保护拒绝时主稿不变（零写入）");
    }
}
