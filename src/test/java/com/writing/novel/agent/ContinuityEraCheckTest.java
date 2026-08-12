package com.writing.novel.agent;

import com.writing.novel.domain.KnowledgeEntry;
import com.writing.novel.domain.Project;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ec-03: ContinuityChecker 时代矛盾检测注入测试。
 * 历史题材 → 检查段含时代锚点 + "矛盾判 HIGH" 指令；架空 → 不注入。
 */
class ContinuityEraCheckTest {

    @TempDir
    Path tempDir;

    private SqliteNovelRepository repoWith(Project p, List<KnowledgeEntry> entries) {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve(p.getTitle() + ".db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        SqliteNovelRepository repo = new SqliteNovelRepository(cp);
        repo.saveProject(p);
        for (var e : entries) repo.saveKnowledgeEntry(e);
        return repo;
    }

    private LlmGateway capturingGateway(LlmRequest[] captured) {
        return new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest req) {
                captured[0] = req;
                return new LlmResponse("未发现连续性问题。", 50, 50);
            }
            @Override public void streamComplete(LlmRequest req, StreamHandler h) {}
        };
    }

    @Test
    void 历史题材检查段含时代锚点与判高指令() {
        var p = new Project("明代悬疑", "落魄账房学徒", "悬疑,古代");
        var repo = repoWith(p, List.of(
                new KnowledgeEntry(p.getId(), "ECONOMY", "大明宝钞", "洪武年间发行大明宝钞。"),
                new KnowledgeEntry(p.getId(), "FACTION", "锦衣卫", "皇帝亲军锦衣卫。")));

        LlmRequest[] captured = new LlmRequest[1];
        var agent = new ContinuityCheckerAgent(capturingGateway(captured), repo, TestPromptHelper.create());
        agent.check(p.getId(), 1, "第1章正文", "设定");

        String msg = captured[0].getUserMessage();
        assertTrue(msg.contains("时代一致性专项检查"), "应注入时代检查段: " + msg);
        assertTrue(msg.contains("明代"), "应含时代锚点 明代");
        assertTrue(msg.contains("【世界观|高】"), "应含'矛盾判高'指令");
    }

    @Test
    void 架空世界不注入时代检查段() {
        var p = new Project("玄幻", "少年修仙", "玄幻");
        var repo = repoWith(p, List.of(
                new KnowledgeEntry(p.getId(), "POWER_SYSTEM", "灵气", "修仙界，架空世界。")));

        LlmRequest[] captured = new LlmRequest[1];
        var agent = new ContinuityCheckerAgent(capturingGateway(captured), repo, TestPromptHelper.create());
        agent.check(p.getId(), 1, "第1章正文", "设定");

        assertFalse(captured[0].getUserMessage().contains("时代一致性专项检查"),
                "架空世界不应注入历史时代检查");
    }
}
