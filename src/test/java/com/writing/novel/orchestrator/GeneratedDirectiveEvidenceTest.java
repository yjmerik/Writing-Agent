package com.writing.novel.orchestrator;

import com.writing.novel.domain.AuthorDirective;
import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.ChapterApproval;
import com.writing.novel.domain.Project;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.prompt.PromptTemplateService;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.service.DirectiveEvidenceService;
import com.writing.novel.spi.NovelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * p37-16：章节生成完成后落库意见落实证据。
 * 连写两章 HARD 仍注入且各自证据独立；慢核对期间作者修改 → 旧结果只存 STALE 不落新稿；
 * 失败可重试不重复证据；额外 GENERAL 调用被捕获；核对器失败要求保持未验证（钩子吞异常）。
 */
class GeneratedDirectiveEvidenceTest {

    @TempDir
    Path tempDir;

    private ConnectionProvider cp;
    private SqliteNovelRepository repo;
    private RecordingGateway gateway;
    private DirectiveEvidenceService service;
    private NovelOrchestrator orchestrator;
    private String projectId;
    private String hardId;

    static class RecordingGateway implements LlmGateway {
        String response = "[]";
        final AtomicInteger generalCalls = new AtomicInteger();

        @Override
        public LlmResponse complete(LlmRequest request) {
            if (request.getTaskType() == com.writing.novel.llm.LlmTaskType.GENERAL) {
                generalCalls.incrementAndGet();
            }
            return new LlmResponse(response, 10, 10);
        }

        @Override
        public void streamComplete(LlmRequest request, com.writing.novel.llm.client.StreamHandler handler) {
            // no-op
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        cp = new ConnectionProvider(tempDir.resolve("evidence.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        gateway = new RecordingGateway();
        PromptTemplateService promptService = new PromptTemplateService();
        promptService.loadTemplates();
        com.writing.novel.agent.DirectiveEvidenceAgent agent =
                new com.writing.novel.agent.DirectiveEvidenceAgent(gateway, promptService);
        service = new DirectiveEvidenceService(repo, agent);

        Project project = new Project("证据项目", "概念", "武侠");
        repo.saveProject(project);
        projectId = project.getId();
        seedChapter(4, "第四章正文，包含雨夜对话场景。".repeat(20));
        seedChapter(5, "第五章正文，同样包含雨夜对话场景。".repeat(20));

        AuthorDirective hard = new AuthorDirective(projectId, AuthorDirective.Kind.HARD, null, "每章一场雨夜对话");
        repo.saveAuthorDirective(hard);
        hardId = hard.getId();

        orchestrator = buildOrchestrator(repo);
        orchestrator.setDirectiveEvidenceService(service);
    }

    /** 反射构造：仅 repository 与钩子被测试使用，其余参数（含 policies）填 null。 */
    private static NovelOrchestrator buildOrchestrator(NovelRepository repo) {
        for (var ctor : NovelOrchestrator.class.getDeclaredConstructors()) {
            var types = ctor.getParameterTypes();
            if (types.length >= 2 && types[0] == NovelRepository.class
                    && types[types.length - 1] == OrchestratorPolicies.class) {
                Object[] args = new Object[types.length];
                args[0] = repo;
                for (int i = 1; i < args.length; i++) {
                    args[i] = null;
                }
                try {
                    return (NovelOrchestrator) ctor.newInstance(args);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        throw new IllegalStateException("未找到 NovelOrchestrator 构造器");
    }

    private void seedChapter(int index, String content) {
        Chapter chapter = new Chapter(projectId, index, "第" + index + "章");
        chapter.setContent(content);
        chapter.setStatus(Chapter.Status.DONE);
        repo.saveChapter(chapter);
    }

    private int evidenceCount() throws SQLException {
        try (Connection conn = cp.getConnection(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM directive_evidence")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    void 连写两章HARD注入且证据独立_捕获额外GENERAL调用() throws SQLException {
        service.captureEvidence(projectId, 4);
        service.captureEvidence(projectId, 5);

        assertEquals(2, gateway.generalCalls.get(), "每章一次额外 GENERAL 核对调用（成本口径）");
        assertEquals(2, evidenceCount(), "两章证据各自独立");
        try (Connection conn = cp.getConnection(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT chapter_index, chapter_version, content_hash FROM directive_evidence ORDER BY chapter_index")) {
            rs.next();
            assertEquals(4, rs.getInt("chapter_index"));
            String hash4 = rs.getString("content_hash");
            rs.next();
            assertEquals(5, rs.getInt("chapter_index"));
            assertNotEquals(hash4, rs.getString("content_hash"), "各章绑定各自身份");
        }
    }

    @Test
    void 慢核对期间作者修改_旧结果只存STALE不落新稿() throws SQLException {
        Chapter before = repo.findChapterByIndex(projectId, 4).orElseThrow();
        String beforeHash = ChapterApproval.contentHash(before.getTitle(), before.getContent());

        service.captureEvidence(projectId, 4, () -> {
            Chapter current = repo.findChapterByIndex(projectId, 4).orElseThrow();
            Chapter revised = new Chapter(projectId, 4, "第四章");
            try {
                java.lang.reflect.Field fId = Chapter.class.getDeclaredField("id");
                fId.setAccessible(true);
                fId.set(revised, current.getId());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            revised.setContent("作者在核对期间改写的新正文，与核对时不同。".repeat(20));
            repo.saveChapter(revised);
        });

        assertEquals(1, evidenceCount(), "只留一条历史行");
        try (Connection conn = cp.getConnection(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT status, chapter_version, content_hash FROM directive_evidence")) {
            rs.next();
            assertEquals(AuthorDirective.EvidenceStatus.STALE.name(), rs.getString("status"),
                    "过期结果只存 STALE 历史");
            assertEquals(before.getVersion(), rs.getInt("chapter_version"), "绑定核对时的旧稿");
            assertEquals(beforeHash, rs.getString("content_hash"));
        }
        Chapter after = repo.findChapterByIndex(projectId, 4).orElseThrow();
        assertTrue(after.getContent().startsWith("作者在核对期间改写"), "新稿正文未被旧结果覆盖");
    }

    @Test
    void 失败可重试且不重复证据() throws SQLException {
        gateway.response = "";
        assertThrows(IllegalStateException.class, () -> service.captureEvidence(projectId, 4),
                "核对器失败保持未验证");
        assertEquals(0, evidenceCount(), "失败不留半套证据");
        orchestrator.captureDirectiveEvidenceAfterGeneration(projectId, 4);
        assertEquals(0, evidenceCount(), "钩子吞异常同样不留证据");

        gateway.response = "[{\"directiveId\":\"" + hardId + "\",\"revision\":1," +
                "\"status\":\"APPLIED_SYSTEM\",\"quote\":\"雨夜对话场景\",\"anchor\":\"1\"}]";
        service.captureEvidence(projectId, 4);
        assertEquals(1, evidenceCount());
        service.captureEvidence(projectId, 4);
        assertEquals(1, evidenceCount(), "幂等重试不重复证据");
    }

    @Test
    void 无适用要求不调用模型且钩子吞异常() {
        // 撤销全书 HARD（理由+CAS）后，真实存在的第 9 章无适用要求
        seedChapter(9, "第九章正文。".repeat(30));
        repo.revokeDirective(projectId, hardId, "作者撤销全书要求", 1);
        gateway.generalCalls.set(0);
        service.captureEvidence(projectId, 9);
        assertEquals(0, gateway.generalCalls.get(), "无适用要求不调用额外模型");

        gateway.response = "";
        assertDoesNotThrow(() -> orchestrator.captureDirectiveEvidenceAfterGeneration(projectId, 4),
                "钩子吞掉核对器异常");
        orchestrator.setDirectiveEvidenceService(null);
        assertDoesNotThrow(() -> orchestrator.captureDirectiveEvidenceAfterGeneration(projectId, 4),
                "服务为 null 直接跳过");
    }
}
