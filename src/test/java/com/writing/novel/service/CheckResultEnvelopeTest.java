package com.writing.novel.service;

import com.writing.novel.agent.CheckResultEnvelope;
import com.writing.novel.agent.ContinuityCheckerAgent;
import com.writing.novel.domain.*;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.prompt.PromptTemplateService;
import com.writing.novel.orchestrator.NovelOrchestrator;
import com.writing.novel.orchestrator.ProjectService;
import com.writing.novel.persistence.sqlite.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-007（方案 §08/§13/R09/R10/F10）：检查结果信封。
 * 坏 JSON/空响应/截断 → INVALID/UNKNOWN 落库为非通过状态，绝不伪装成"无问题"（VALID_EMPTY）；
 * finalGate 将必查 UNKNOWN/INVALID 视为阻塞；零重写预算不解除门禁。
 */
class CheckResultEnvelopeTest {

    @TempDir
    Path tempDir;

    private SqliteNovelRepository repo;
    private CheckResultRepository checkRepo;
    private String projectId;

    @BeforeEach
    void setUp() {
        var cp = new ConnectionProvider(tempDir.resolve("envelope.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        checkRepo = new CheckResultRepository(cp);
        Project project = new Project("信封项目", "构思", "武侠");
        repo.saveProject(project);
        projectId = project.getId();
    }

    @Test
    void 分类_空响应未知_明确无问题_可解析_不可解析() {
        assertEquals(CheckResultEnvelope.Status.UNKNOWN, CheckResultEnvelope.of(null).status());
        assertEquals(CheckResultEnvelope.Status.UNKNOWN, CheckResultEnvelope.of("   ").status());

        var clean = CheckResultEnvelope.of("检查完成：未发现连续性问题。");
        assertEquals(CheckResultEnvelope.Status.VALID_EMPTY, clean.status());
        assertTrue(clean.issues().isEmpty());

        var hit = CheckResultEnvelope.of("发现以下问题：\n【人物|高】沈昭在第8章已知密信被焚，此处却仍引用原件");
        assertEquals(CheckResultEnvelope.Status.SUCCESS, hit.status());
        assertEquals(1, hit.issues().size());
        assertEquals(ContinuityIssue.Severity.HIGH, hit.issues().get(0).getSeverity());

        // 截断/协议错误/拒绝类输出：非空但完全不可解析 → INVALID，不是空问题列表
        var garbage = CheckResultEnvelope.of("抱歉，我不能继续这个请求。输出被截断###ERR###");
        assertEquals(CheckResultEnvelope.Status.INVALID, garbage.status());
        assertTrue(garbage.issues().isEmpty());
        assertNotNull(garbage.reason());
    }

    @Test
    void 检查器坏输出落库INVALID_不写空问题列表() {
        LlmGateway badGateway = new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest req) {
                return new LlmResponse("###ERR 输出被截断", 10, 10);
            }
            @Override public void streamComplete(LlmRequest req, com.writing.novel.llm.client.StreamHandler h) {}
        };
        var checker = new ContinuityCheckerAgent(badGateway, repo, TestPromptHelper(), null, checkRepo);
        var issues = checker.check(projectId, 1, "正文".repeat(50), "");
        assertTrue(issues.isEmpty(), "坏输出不得产生问题条目");
        var latest = checkRepo.latestStatuses(projectId);
        assertEquals(1, latest.size());
        assertEquals(CheckResultEnvelope.Status.INVALID, latest.get(0).status());
        // 未知的检查计数进入统一读取视图
        var view = new WorkspaceViewService(repo, checkRepo);
        @SuppressWarnings("unchecked")
        var review = (java.util.Map<String, Object>) view.view(projectId).get("review");
        assertEquals(1L, review.get("unknownChecks"), "未知检查必须进入统一读取视图的 unknownChecks");
    }

    @Test
    void 正常检查落库SUCCESS_无问题时VALID_EMPTY() {
        LlmGateway goodGateway = new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest req) {
                return new LlmResponse("检查完成：未发现连续性问题。", 10, 10);
            }
            @Override public void streamComplete(LlmRequest req, com.writing.novel.llm.client.StreamHandler h) {}
        };
        var checker = new ContinuityCheckerAgent(goodGateway, repo, TestPromptHelper(), null, checkRepo);
        checker.check(projectId, 1, "正文".repeat(50), "");
        assertEquals(CheckResultEnvelope.Status.VALID_EMPTY, checkRepo.latestStatuses(projectId).get(0).status());
    }

    @Test
    void 最新一次检查覆盖旧状态_后续SUCCESS清除未知() {
        checkRepo.save(projectId, 1, "continuity", CheckResultEnvelope.Status.UNKNOWN, "空响应");
        checkRepo.save(projectId, 1, "continuity", CheckResultEnvelope.Status.SUCCESS, "");
        var unknown = checkRepo.latestStatuses(projectId).stream()
                .filter(r -> r.status() == CheckResultEnvelope.Status.UNKNOWN).count();
        assertEquals(0, unknown, "后续有效检查覆盖旧的 UNKNOWN——最新状态为准");
    }

    @Test
    void 成书门禁阻塞未知必查项() {
        var orchestrator = gateOnlyOrchestrator();
        Project p = gateProject("门禁未知");
        String pid = p.getId();
        // 该项目除第2章检查 UNKNOWN 外无任何其他问题 → 仅凭"尚未查清"就必须阻塞
        checkRepo.save(pid, 2, "continuity", CheckResultEnvelope.Status.UNKNOWN, "输出截断");
        orchestrator.step(pid);
        assertEquals(NovelState.NEEDS_REVISION, repo.findProject(pid).orElseThrow().getState(),
                "尚未查清的必查检查必须阻塞成书");

        // 后续有效检查覆盖 UNKNOWN → 门禁不再因未知阻塞（本项目改为 FINAL）
        checkRepo.save(pid, 2, "continuity", CheckResultEnvelope.Status.SUCCESS, "");
        p.setState(NovelState.VOLUME_DONE);
        repo.saveProject(p);
        orchestrator.step(pid);
        assertEquals(NovelState.FINAL, repo.findProject(pid).orElseThrow().getState(),
                "未知消除且无其他问题 → 允许成书");
    }

    @Test
    void 零重写预算不解除严重问题的门禁() {
        var orchestrator = gateOnlyOrchestrator();
        String pid = gateProject("门禁预算").getId();
        var prefs = new WritingPreferences(pid);
        prefs.setRewriteBudget(0);
        repo.saveWritingPreferences(prefs);
        Chapter ch1 = new Chapter(pid, 1, "第1章");
        ch1.setContent("正文".repeat(100));
        ch1.setStatus(Chapter.Status.DONE);
        repo.saveChapter(ch1);
        for (int i = 0; i < 11; i++) {
            ContinuityIssue severe = new ContinuityIssue(pid, 1,
                    ContinuityIssue.Type.TIMELINE, ContinuityIssue.Severity.HIGH, "严重矛盾" + i);
            severe.setChapterVersion(1);
            repo.saveContinuityIssue(severe);
        }
        orchestrator.step(pid);
        assertEquals(NovelState.NEEDS_REVISION, repo.findProject(pid).orElseThrow().getState(),
                "重写预算为 0 不解除质量门禁——严重未解决问题仍阻塞成书");
    }

    private NovelOrchestrator gateOnlyOrchestrator() {
        NovelOrchestrator orchestrator = new NovelOrchestrator(repo, new ProjectService(repo),
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null);
        orchestrator.setCheckResultRepository(checkRepo);
        return orchestrator;
    }

    private Project gateProject(String title) {
        Project p = new Project(title, "测试", "武侠");
        repo.saveProject(p);
        Outline outline = new Outline(p.getId(), "总纲");
        Volume v = new Volume(p.getId(), 1, "第一卷", "卷摘要");
        v.getSynopses().add(new ChapterSynopsis(p.getId(), 1, "第1章", "开局"));
        outline.getVolumes().add(v);
        repo.saveOutline(outline);
        p.setCurrentChapterIndex(1);
        p.setState(NovelState.VOLUME_DONE);
        repo.saveProject(p);
        return p;
    }

    private PromptTemplateService TestPromptHelper() {
        return com.writing.novel.agent.TestPromptHelper.create();
    }
}
