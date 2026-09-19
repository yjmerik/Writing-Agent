package com.writing.novel.service;

import com.writing.novel.domain.*;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.orchestrator.NovelOrchestrator;
import com.writing.novel.agent.ContentRevisionAgent;
import com.writing.novel.persistence.sqlite.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-025（方案 §05/DELEGATION）：委托策略分级。
 * 按变更类别（措辞/单章事实/动机命运/跨章）配置 AUTO/CONFIRM；
 * AUTO 范围内自动接受留痕；越界类别要求作者确认；规则可自定义。
 */
class DelegationPolicyTest {

    @TempDir
    Path tempDir;

    private SqliteNovelRepository repo;
    private DelegationPolicyService service;
    private RevisionRequestService requests;
    private RevisionRepairService repairService;
    private GroupAcceptanceService acceptance;
    private String projectId;

    @BeforeEach
    void setUp() {
        var cp = new ConnectionProvider(tempDir.resolve("delegation.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        requests = new RevisionRequestService(new RevisionRequestRepository(cp));
        service = new DelegationPolicyService(new DelegationPolicyRepository(cp), repo);
        acceptance = new GroupAcceptanceService(repo, requests,
                new ChapterRevisionService(repo, null, null));

        LlmGateway gateway = new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest req) {
                var ch = repo.findChaptersByProject(projectId).stream()
                        .filter(c -> req.getSystemPrompt() != null && req.getSystemPrompt().contains(c.getId()))
                        .findFirst().orElse(null);
                try {
                    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    var structured = mapper.createObjectNode();
                    structured.put("id", ch == null ? "unknown" : ch.getId());
                    structured.put("projectId", projectId);
                    structured.put("chapterIndex", ch == null ? 0 : ch.getChapterIndex());
                    structured.put("title", ch == null ? "修复" : ch.getTitle());
                    String content = "修复后的正文，密信确实已焚毁不再出现。".repeat(80);
                    structured.put("content", content);
                    var node = mapper.createObjectNode();
                    node.put("targetType", "CHAPTER");
                    node.put("title", ch == null ? "修复" : ch.getTitle());
                    node.put("content", content);
                    node.put("structuredJson", mapper.writeValueAsString(structured));
                    node.put("summary", "修复");
                    node.putArray("directiveResults");
                    return new LlmResponse(mapper.writeValueAsString(node), 300, 600);
                } catch (Exception e) { throw new RuntimeException(e); }
            }
            @Override public void streamComplete(LlmRequest req, com.writing.novel.llm.client.StreamHandler h) {}
        };
        var agentRevision = new AgentRevisionService(repo,
                new ContentRevisionAgent(gateway, com.writing.novel.agent.TestPromptHelper.create()),
                null, null, Mockito.mock(NovelOrchestrator.class),
                new com.fasterxml.jackson.databind.ObjectMapper(), null);
        repairService = new RevisionRepairService(
                new ImpactAnalysisService(repo, new SqliteStoryCanon(new StoryFactRepository(cp), repo),
                        new StoryFactRepository(cp)),
                agentRevision, requests, repo);

        Project project = new Project("委托项目", "构思", "武侠");
        repo.saveProject(project);
        projectId = project.getId();
        Chapter ch = new Chapter(projectId, 8, "第八章");
        ch.setContent("江砚把密信交给沈昭，信中名单足以致命。");
        ch.setStatus(Chapter.Status.DONE);
        repo.saveChapter(ch);
        Chapter ch12 = new Chapter(projectId, 12, "第十二章");
        ch12.setContent("沈昭凭着那封密信里的名单，向官差告了密。");
        ch12.setStatus(Chapter.Status.DONE);
        repo.saveChapter(ch12);
    }

    private String seedSingleChapterRequest() {
        // 事实锚定第 8 章 + 12 章引用 → 单章修复（第 12 章 MUST_REPAIR）
        var canon = new SqliteStoryCanon(new StoryFactRepository(
                new ConnectionProvider(tempDir.resolve("delegation.db").toString())), repo);
        // 通过 repairService 的 canon 无法直接访问——直接用事实仓库走 harvest
        // 简化：靠实体词法——12 章不含人物名则 RECHECK 而非 MUST_REPAIR。
        // 为确保 MUST_REPAIR：造锚定事实。
        return null;
    }

    @Test
    void 默认规则_措辞与单章事实AUTO_动机与跨章CONFIRM() {
        var rules = service.rules(projectId);
        assertEquals(DelegationPolicyService.Mode.AUTO, rules.get(DelegationPolicyService.ChangeClass.WORDING));
        assertEquals(DelegationPolicyService.Mode.AUTO, rules.get(DelegationPolicyService.ChangeClass.FACT_LOCAL));
        assertEquals(DelegationPolicyService.Mode.CONFIRM, rules.get(DelegationPolicyService.ChangeClass.MOTIVE));
        assertEquals(DelegationPolicyService.Mode.CONFIRM, rules.get(DelegationPolicyService.ChangeClass.CROSS_CHAPTER));
    }

    @Test
    void 自定义规则_翻转后生效_无效值拒绝() {
        service.saveRules(projectId, java.util.Map.of("MOTIVE", "AUTO"));
        assertEquals(DelegationPolicyService.Mode.AUTO, service.rules(projectId).get(DelegationPolicyService.ChangeClass.MOTIVE));
        assertThrows(IllegalArgumentException.class,
                () -> service.saveRules(projectId, java.util.Map.of("NOT_A_CLASS", "AUTO")));
    }

    @Test
    void 请求分类_跨章候选为CROSS_CHAPTER_单章按来源() {
        var request = requests.record(projectId, "CHAPTER_REWRITE", "改成焚信", "[]", "{}", "[]");
        // 无候选时按 origin：CHAPTER_REWRITE → FACT_LOCAL
        assertEquals(DelegationPolicyService.ChangeClass.FACT_LOCAL,
                service.classOfRequest(projectId, request));
        var plan = requests.record(projectId, "PLAN_ADJUST", "调整", "[]", "{}", "[]");
        assertEquals(DelegationPolicyService.ChangeClass.CROSS_CHAPTER,
                service.classOfRequest(projectId, plan));
    }

    @Test
    void 自动接受_单章修复在AUTO规则内_留痕采纳() {
        // 建立单章候选（12 章 MUST_REPAIR 路径需要锚定事实——直接手工保存 rr 候选）
        var request = requests.record(projectId, "CHAPTER_REWRITE", "改成焚信", "[]", "{}", "[]");
        requests.approveScope(projectId, request.getId(), "批准");
        saveReadyProposal("rr:" + request.getId() + ":12", 12);

        var decision = service.autoAcceptIfDelegated(projectId, request.getId(), acceptance);
        assertTrue(decision.autoAccepted(), "单章修复（FACT_LOCAL 默认 AUTO）自动接受");
        assertEquals(DelegationPolicyService.ChangeClass.FACT_LOCAL, decision.changeClass());
        assertEquals("COMPLETED", requests.get(projectId, request.getId()).getStatus().name());
        assertTrue(repo.findChapterByIndex(projectId, 12).orElseThrow().getContent().contains("密信确实已焚毁"));
    }

    @Test
    void 跨章候选默认CONFIRM_不自动接受() {
        var request = requests.record(projectId, "CHAPTER_REWRITE", "改成焚信", "[]", "{}", "[]");
        requests.approveScope(projectId, request.getId(), "批准");
        saveReadyProposal("rr:" + request.getId() + ":8", 8);
        saveReadyProposal("rr:" + request.getId() + ":12", 12);

        var decision = service.autoAcceptIfDelegated(projectId, request.getId(), acceptance);
        assertFalse(decision.autoAccepted(), "跨章候选（CROSS_CHAPTER 默认 CONFIRM）不自动接受");
        assertTrue(decision.message().contains("确认"));
        assertEquals("SCOPE_APPROVED", requests.get(projectId, request.getId()).getStatus().name(),
                "请求保持待确认状态");
    }

    @Test
    void 自定义授权跨章AUTO后_自动接受生效() {
        service.saveRules(projectId, java.util.Map.of("CROSS_CHAPTER", "AUTO"));
        var request = requests.record(projectId, "CHAPTER_REWRITE", "改成焚信", "[]", "{}", "[]");
        requests.approveScope(projectId, request.getId(), "批准");
        saveReadyProposal("rr:" + request.getId() + ":8", 8);
        saveReadyProposal("rr:" + request.getId() + ":12", 12);
        var decision = service.autoAcceptIfDelegated(projectId, request.getId(), acceptance);
        assertTrue(decision.autoAccepted(), "作者显式授权跨章 AUTO 后生效");
    }

    private void saveReadyProposal(String requestKey, int chapterIndex) {
        var ch = repo.findChapterByIndex(projectId, chapterIndex).orElseThrow();
        var identity = ChapterApproval.Identity.of(ch);
        RevisionProposal p = new RevisionProposal(java.util.UUID.randomUUID().toString(), projectId,
                RevisionProposal.TargetType.CHAPTER, ch.getId(), chapterIndex, null,
                identity.version(), identity.contentHash(), "委托测试", "{}",
                "{\"id\":\"" + ch.getId() + "\",\"projectId\":\"" + projectId + "\",\"chapterIndex\":" + chapterIndex
                        + ",\"title\":" + json(ch.getTitle()) + ",\"content\":" + json("修复后的正文，密信确实已焚毁不再出现。".repeat(80)) + "}",
                "候选", RevisionProposal.Status.READY, requestKey, null,
                java.time.Instant.now(), java.time.Instant.now());
        repo.saveProposal(p);
    }

    private String json(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
