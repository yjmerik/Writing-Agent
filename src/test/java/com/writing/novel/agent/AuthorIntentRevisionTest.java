package com.writing.novel.agent;

import com.writing.novel.domain.AuthorIntent;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.service.AuthorIntentContextService;
import com.writing.novel.telemetry.EventLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-035: 生成修订与核查遵从创作约定——核查/段落修订请求携带精确契约版本及内容，
 * 所用修订号经 AUTHOR_INTENT_USED 审计；委托边界条款在场（自由文本不得提升委托权限）。
 */
class AuthorIntentRevisionTest {

    @TempDir
    Path tempDir;
    private final List<Map<String, Object>> events = new ArrayList<>();
    private final EventLog.Listener capture = record -> {
        if ("AUTHOR_INTENT_USED".equals(record.get("event"))) events.add(record);
    };

    private AuthorIntentContextService serviceWithContract(
            com.writing.novel.persistence.sqlite.SqliteNovelRepository repo) {
        // 约定仓储与主库同连接（与生产 SqliteNovelRepository.findAuthorIntent 同构）
        var intentRepo = new com.writing.novel.persistence.sqlite.AuthorIntentRepository(
                new com.writing.novel.persistence.sqlite.ConnectionProvider(
                        tempDir.resolve("rev.db").toString()));
        var intent = new AuthorIntent();
        intent.setProjectId("p1");
        intent.setPremise("庙堂之高与江湖之远互为镜像");
        intent.setAutonomy("大纲与人物可自主细化；主线转折必须留给作者");
        intentRepo.append("p1", intent);
        return new AuthorIntentContextService(repo, TestPromptHelper.create());
    }

    @Test
    void 核查请求携带契约并审计所用修订() {
        var cp = new com.writing.novel.persistence.sqlite.ConnectionProvider(
                tempDir.resolve("rev.db").toString());
        new com.writing.novel.persistence.sqlite.SqliteSchemaInitializer(cp).initialize();
        var repo = new com.writing.novel.persistence.sqlite.SqliteNovelRepository(cp);
        repo.saveProject(new com.writing.novel.domain.Project("权谋", "构思", "历史"));
        var service = serviceWithContract(repo);

        final List<String> prompts = new ArrayList<>();
        LlmGateway captureGateway = new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest req) {
                prompts.add(req.getSystemPrompt());
                return new LlmResponse("未发现连续性问题。", 10, 10);
            }
            @Override public void streamComplete(LlmRequest req, StreamHandler h) {}
        };
        ContinuityCheckerAgent agent = new ContinuityCheckerAgent(captureGateway, repo,
                TestPromptHelper.create(), null, null);
        agent.setAuthorIntentContextService(service);
        agent.check("p1", 1, "正文内容。", "设定：无");

        assertEquals(1, prompts.size());
        assertTrue(prompts.get(0).contains("【创作约定·第1版】"), "核查请求应带契约版本");
        assertTrue(prompts.get(0).contains("庙堂之高与江湖之远互为镜像"), "应带契约内容");
        assertTrue(prompts.get(0).contains("自主程度仅描述创作偏好"), "委托边界条款应在场");

        EventLog.subscribe(capture);
        try {
            agent.check("p1", 1, "正文内容。", "设定：无");
        } finally {
            EventLog.unsubscribe(capture);
        }
        assertEquals(1, events.size(), "核查应审计所用修订");
        assertEquals("REVIEW_CHECK", events.get(0).get("context"));
        assertEquals(1, events.get(0).get("revision"));
    }

    @Test
    void 空契约时核查与段落修订零注入() {
        var cp = new com.writing.novel.persistence.sqlite.ConnectionProvider(
                tempDir.resolve("rev2.db").toString());
        new com.writing.novel.persistence.sqlite.SqliteSchemaInitializer(cp).initialize();
        var repo = new com.writing.novel.persistence.sqlite.SqliteNovelRepository(cp);
        repo.saveProject(new com.writing.novel.domain.Project("t", "c", "tag"));
        var service = new AuthorIntentContextService(repo, TestPromptHelper.create());

        final List<String> prompts = new ArrayList<>();
        LlmGateway captureGateway = new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest req) {
                prompts.add(req.getSystemPrompt());
                return new LlmResponse("- 段落扩写后的内容，比原来更长一些，加了感官细节与动作描写。", 10, 10);
            }
            @Override public void streamComplete(LlmRequest req, StreamHandler h) {}
        };
        PassageEditor editor = new PassageEditor(captureGateway, TestPromptHelper.create());
        editor.setAuthorIntentContextService(service);
        editor.editPassage("一段短文本。", "", "", "扩写", "EXPAND");

        assertFalse(prompts.get(0).contains("创作约定"), "空契约零注入");
    }
}
