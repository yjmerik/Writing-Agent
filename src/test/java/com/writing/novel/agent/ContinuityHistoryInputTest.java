package com.writing.novel.agent;

import com.writing.novel.domain.*;
import com.writing.novel.domain.Character;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * mc-p2-02: 连续性检查输入包含历史卷摘要 + 叙事线索台账。
 * 复现 M2：50 章后连续性检查缺少长程记忆，无法发现跨卷矛盾。
 */
class ContinuityHistoryInputTest {

    @TempDir
    Path tempDir;

    @Test
    void 连续性检查包含历史卷摘要与叙事线索台账() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("ch.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        SqliteNovelRepository repo = new SqliteNovelRepository(cp);

        // 50 章合成数据
        var p = new Project("长程", "测试", "权谋");
        repo.saveProject(p);
        for (int i = 1; i <= 50; i++) {
            Chapter c = new Chapter(p.getId(), i, "第" + i + "章");
            c.setContent("第" + i + "章正文：朝堂局势变化，各方势力暗中角力。".repeat(40));
            repo.saveChapter(c);
        }
        // 卷级摘要（span>3）
        repo.saveSummary(new Summary(p.getId(), 1, 20, "【卷一摘要】主角初入朝堂，与丞相结盟。"));
        repo.saveSummary(new Summary(p.getId(), 21, 40, "【卷二摘要】主角斗倒二皇子，赢得兵权。"));
        repo.saveSummary(new Summary(p.getId(), 41, 50, "【卷三摘要】北境战事再起，皇后密谋夺权。"));
        // 叙事线索台账
        NarrativeThread thread = new NarrativeThread(p.getId(), "皇后夺权阴谋", 10, "皇后暗中联络边军");
        thread.markSuspended(35);
        repo.saveNarrativeThread(thread);

        // 捕获请求的 gateway
        final LlmRequest[] captured = new LlmRequest[1];
        LlmGateway gateway = new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest req) {
                captured[0] = req;
                return new LlmResponse("未发现连续性问题。", 50, 50);
            }
            @Override public void streamComplete(LlmRequest req, StreamHandler handler) {}
        };

        ContinuityCheckerAgent agent = new ContinuityCheckerAgent(gateway, repo, TestPromptHelper.create());
        agent.check(p.getId(), 51, "第51章正文……", "设定");

        assertNotNull(captured[0]);
        String msg = captured[0].getUserMessage();
        assertTrue(msg.contains("【历史卷摘要"), "应包含历史卷摘要段: " + msg);
        assertTrue(msg.contains("【卷一摘要】") || msg.contains("【卷三摘要】"),
                "应包含卷级摘要内容");
        assertTrue(msg.contains("【叙事线索台账"), "应包含叙事线索台账段");
        assertTrue(msg.contains("皇后夺权阴谋"), "应包含未回收线索");
    }

    @Test
    void 无摘要无线索时历史段为空() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("ch2.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        SqliteNovelRepository repo = new SqliteNovelRepository(cp);
        var p = new Project("空", "测试", "玄幻");
        repo.saveProject(p);

        final LlmRequest[] captured = new LlmRequest[1];
        LlmGateway gateway = new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest req) {
                captured[0] = req;
                return new LlmResponse("未发现连续性问题。", 50, 50);
            }
            @Override public void streamComplete(LlmRequest req, StreamHandler handler) {}
        };

        ContinuityCheckerAgent agent = new ContinuityCheckerAgent(gateway, repo, TestPromptHelper.create());
        agent.check(p.getId(), 1, "第1章正文", "设定");

        String msg = captured[0].getUserMessage();
        assertFalse(msg.contains("历史卷摘要"), "无卷级摘要时不应出现空段");
        assertFalse(msg.contains("叙事线索台账"), "无线索时不应出现空段");
    }
}
