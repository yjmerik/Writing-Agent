package com.writing.novel.service;

import com.writing.novel.agent.ContentRevisionAgent;
import com.writing.novel.domain.*;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.orchestrator.NovelOrchestrator;
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
 * NCS-015（方案 §12）：影响修复候选生成。
 * MUST_REPAIR 章生成带影响上下文的隔离修复候选（挂接修订请求）；候选不动主稿；
 * 范围未批准不可生成；生成失败不产生半套状态。
 */
class RevisionRepairTest {

    @TempDir
    Path tempDir;

    private SqliteNovelRepository repo;
    private StoryFactRepository factRepo;
    private SqliteStoryCanon canon;
    private RevisionRequestService requests;
    private AgentRevisionService agentRevision;
    private RevisionRepairService repairService;
    private String projectId;
    private EventLog.Listener listener;
    private final List<String> events = new CopyOnWriteArrayList<>();
    private String lastInstructions = "";

    @BeforeEach
    void setUp() {
        var cp = new ConnectionProvider(tempDir.resolve("repair.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        factRepo = new StoryFactRepository(cp);
        canon = new SqliteStoryCanon(factRepo, repo);
        var requestRepo = new RevisionRequestRepository(cp);
        requests = new RevisionRequestService(requestRepo);

        LlmGateway gateway = new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest req) {
                // 从 systemPrompt 提取章节 id（currentJson 含 id 字段）
                String sp = req.getSystemPrompt() == null ? "" : req.getSystemPrompt();
                var ch = repo.findChaptersByProject(projectId).stream()
                        .filter(c -> sp.contains("\"id\":\"" + c.getId() + "\"")
                                || sp.contains("\"id\": \"" + c.getId() + "\""))
                       .findFirst().orElse(null);
                String id = ch != null ? ch.getId() : "unknown";
                String title = ch != null ? ch.getTitle() : "未知";
                String content = "修复后的正文：沈昭只听说密信曾被焚毁，凭残缺口供指认。"
                        .repeat(60) + "（针对影响的修复稿）";
                lastInstructions = sp;
                try {
                    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    // 候选对象 = structuredJson 的解析结果：须含完整身份（id/projectId/chapterIndex）+ title/content
                    var structured = mapper.createObjectNode();
                    structured.put("id", id);
                    structured.put("projectId", projectId);
                    structured.put("chapterIndex", ch != null ? ch.getChapterIndex() : 0);
                    structured.put("title", title);
                    structured.put("content", content.replace("\n", " "));
                    var node = mapper.createObjectNode();
                    node.put("targetType", "CHAPTER");
                    node.put("title", title);
                    node.put("content", content.replace("\n", " "));
                    node.put("structuredJson", mapper.writeValueAsString(structured));
                    node.put("summary", "按修订意图修复后文依赖");
                    node.putArray("directiveResults");
                    return new LlmResponse(mapper.writeValueAsString(node), 300, 600);
                } catch (Exception e) { throw new RuntimeException(e); }
            }
            @Override public void streamComplete(LlmRequest req, com.writing.novel.llm.client.StreamHandler h) {}
        };
        var revisionAgent = new ContentRevisionAgent(gateway,
                com.writing.novel.agent.TestPromptHelper.create());
        var mockOrchestrator = org.mockito.Mockito.mock(NovelOrchestrator.class);
        agentRevision = new AgentRevisionService(repo, revisionAgent, null, null, mockOrchestrator,
                new com.fasterxml.jackson.databind.ObjectMapper(), null);
        repairService = new RevisionRepairService(
                new ImpactAnalysisService(repo, canon, factRepo), agentRevision, requests, repo);

        Project project = new Project("修复项目", "构思", "武侠");
        repo.saveProject(project);
        projectId = project.getId();
        listener = e -> events.add(String.valueOf(e.get("event")));
        EventLog.subscribe(listener);
    }

    @AfterEach
    void tearDown() {
        EventLog.unsubscribe(listener);
    }

    private void seedImpactScenario() {
        Chapter ch8 = new Chapter(projectId, 8, "第八章");
        ch8.setContent("江砚把密信交给沈昭，信中名单足以致命。");
        ch8.setStatus(Chapter.Status.DONE);
        repo.saveChapter(ch8);
        Chapter ch12 = new Chapter(projectId, 12, "第十二章");
        ch12.setContent("沈昭凭着那封密信里的名单，向官差告了密。");
        ch12.setStatus(Chapter.Status.DONE);
        repo.saveChapter(ch12);
        StoryFact letter = new StoryFact(projectId, StoryFact.FactType.OBJECT_STATE,
                "密信", "密信由江砚交给沈昭", 8);
        canon.harvest(letter);
        // 修订第 8 章：焚信
        var current = repo.findChapterByIndex(projectId, 8).orElseThrow();
        current.setContent("江砚把密信凑近烛火，当着沈昭的面焚毁了它。");
        repo.saveChapter(current);
    }

    @Test
    void 为MUST_REPAIR章生成隔离候选_挂接请求_主稿不变() {
        seedImpactScenario();
        var request = requests.record(projectId, "CHAPTER_REWRITE", "改成当场焚信",
                "[]", "{}", "[{\"span\":\"结尾原句\"}]");
        requests.approveScope(projectId, request.getId(), "批准修复第 12 章");

        var result = repairService.generateRepairs(projectId, request.getId(), 8, "改成当场焚信");

        assertEquals(1, result.generated(), "第 12 章为 MUST_REPAIR → 生成 1 个修复候选");
        assertEquals(List.of(12), result.chapters());
        assertEquals(1, result.previewIds().size());
        // 候选隔离：主稿未变
        var ch12 = repo.findChapterByIndex(projectId, 12).orElseThrow();
        assertTrue(ch12.getContent().contains("向官差告了密"), "修复候选不改动主稿");
        // 影响上下文进入指令
        assertTrue(lastInstructions.contains("密信") || lastInstructions.contains("影响"),
                "修复指令必须携带影响上下文");
        // 候选挂接：requestKey 前缀可查
        var proposals = repo.listProposals(projectId);
        assertEquals(1, proposals.size());
        assertTrue(proposals.get(0).getRequestKey().startsWith("rr:" + request.getId()));
        assertTrue(events.contains("REVISION_REPAIR_GENERATED"));
    }

    @Test
    void 范围未批准不可生成() {
        seedImpactScenario();
        var request = requests.record(projectId, "CHAPTER_REWRITE", "改成当场焚信", "[]", "{}", "[]");
        assertThrows(IllegalStateException.class,
                () -> repairService.generateRepairs(projectId, request.getId(), 8, "改成当场焚信"),
                "OPEN 请求不得消耗修复生成");
        assertTrue(repo.listProposals(projectId).isEmpty());
    }

    @Test
    void 无MUST_REPAIR时零候选_不失败() {
        seedImpactScenario();
        // 第 12 章正文改为不引用密信
        var ch12 = repo.findChapterByIndex(projectId, 12).orElseThrow();
        ch12.setContent("沈昭凭记忆向官差复述了名单。");
        repo.saveChapter(ch12);
        var request = requests.record(projectId, "CHAPTER_REWRITE", "改成当场焚信", "[]", "{}", "[]");
        requests.approveScope(projectId, request.getId(), "批准");
        var result = repairService.generateRepairs(projectId, request.getId(), 8, "改成当场焚信");
        assertEquals(0, result.generated(), "无 MUST_REPAIR 命中 → 零候选，正常返回");
    }
}
