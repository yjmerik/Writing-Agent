package com.writing.novel.agent;

import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.StoryFact;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.LlmTaskType;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.llm.prompt.PromptTemplateService;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteStoryCanon;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.persistence.sqlite.StoryFactRepository;
import com.writing.novel.telemetry.EventLog;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * p16-03: FactHarvester 收窄 + fail-loud。
 * 原实现把全部 ACTIVE 事实拼进 prompt（无 cap），千条事实 ≈33k token 爆窗；
 * 且 LLM 失败被 catch → return (0,0,0) 静默，真典停止生长无告警。
 */
class FactHarvesterScopedTest {

    @TempDir
    Path tempDir;
    private StoryFactRepository factRepo;
    private CapturingGateway gateway;
    private FactHarvesterAgent agent;

    @BeforeAll
    static void redirectEventLog() throws Exception {
        // fail-loud 断言需要读事件文件；每个测试类用独立文件避免串扰
        Path events = Files.createTempDirectory("events").resolve("events.jsonl");
        System.setProperty(EventLog.PATH_PROPERTY, events.toString());
    }

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("facts.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        factRepo = new StoryFactRepository(cp);
        gateway = new CapturingGateway("[]");        agent = new FactHarvesterAgent(gateway, new SqliteStoryCanon(factRepo), com.writing.novel.agent.TestPromptHelper.create());
    }

    private void seedFacts(String projectId, int count) {
        for (int i = 0; i < count; i++) {
            StoryFact f = new StoryFact(projectId, StoryFact.FactType.CHARACTER_STATE,
                    "角色" + i, "状态描述" + i, 1);
            f.setImportance(1 + (i % 5));
            factRepo.save(f);
        }
    }

    @Test
    void 五百条事实时prompt清单有上界且含cast相关() {
        String projectId = "proj-scope";
        seedFacts(projectId, 500);
        // 高相关的角色0/1/2 必须出现在正文里
        StoryFact castFact = new StoryFact(projectId, StoryFact.FactType.CHARACTER_STATE,
                "主角甲", "佩剑行走", 2);
        factRepo.save(castFact);

        Chapter ch = new Chapter(projectId, 10, "第10章");
        ch.setContent("主角甲 与 角色499 在客栈相遇，主角甲 拔剑。");
        agent.harvest(projectId, ch);

        String prompt = gateway.lastSystemPrompt;
        int factLines = countFactLines(prompt);
        assertTrue(factLines <= 80, "事实清单应 ≤80 条，实际 " + factLines);
        assertTrue(prompt.contains("主角甲"), "正文 cast 相关事实必须保留在清单中");
        assertTrue(prompt.contains("检索子集") || prompt.contains("子集"),
                "清单必须声明其为检索子集（未列出不代表不存在）");
    }

    @Test
    void LLM失败时记LLM_ERROR事件不再静默() throws Exception {
        String projectId = "proj-loud";
        seedFacts(projectId, 3);
        Path eventsFile = Path.of(System.getProperty(EventLog.PATH_PROPERTY));
        long before = Files.exists(eventsFile) ? Files.lines(eventsFile).count() : 0;
        gateway = new CapturingGateway(null); // null → complete 抛异常
        agent = new FactHarvesterAgent(gateway, new SqliteStoryCanon(factRepo), com.writing.novel.agent.TestPromptHelper.create());

        Chapter ch = new Chapter(projectId, 5, "第5章");
        ch.setContent("内容");
        assertThrows(IllegalStateException.class, () -> agent.harvest(projectId, ch));
        long after = Files.lines(eventsFile).count();
        assertTrue(after > before, "LLM 失败应产生新事件行");
        String last = Files.lines(eventsFile).reduce((a, b) -> b).orElse("");
        assertTrue(last.contains("LLM_ERROR"), "事件应为 LLM_ERROR: " + last);
    }

    private int countFactLines(String prompt) {
        int count = 0;
        for (String line : prompt.split("\n")) {
            if (line.startsWith("- [")) count++;
        }
        return count;
    }

    private static class CapturingGateway implements LlmGateway {
        final String content;
        String lastSystemPrompt;

        CapturingGateway(String content) { this.content = content; }

        @Override
        public LlmResponse complete(LlmRequest request) {
            if (content == null) throw new RuntimeException("模拟 LLM 故障");
            lastSystemPrompt = request.getSystemPrompt();
            return new LlmResponse(content, 10, 5);
        }

        @Override
        public void streamComplete(LlmRequest request, StreamHandler handler) {
            throw new UnsupportedOperationException();
        }
    }
}
