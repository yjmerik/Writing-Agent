package com.writing.novel.agent;

import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.Project;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-138: 读者视角评审 Agent——三维度结构化报告、只读性（零 ContinuityIssue/零屏障/零重写触发）、
 * 分层采样与覆盖率声明、fail-loud 解析。
 */
class ReaderPerspectiveAgentTest {

    @TempDir
    Path tempDir;
    private SqliteNovelRepository repository;
    private String pid;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("reader.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repository = new SqliteNovelRepository(cp);
        var project = new Project("读者评审", "构思", "mystery");
        repository.saveProject(project);
        pid = project.getId();
        for (int i = 1; i <= 8; i++) {
            Chapter ch = new Chapter(pid, i, "第" + i + "章");
            StringBuilder content = new StringBuilder("第" + i + "章的开头，沈砚翻开账册。");
            while (content.length() < 2000) content.append("正文推进。");
            content.append("——第").append(i).append("章结尾的悬念。");
            ch.setContent(content.toString());
            repository.saveChapter(ch);
        }
    }

    private static final String VALID_JSON = """
            {"plot":{"score":8,"strengths":["主线清晰"],"weaknesses":["中段略拖"],
             "pacing":"前紧后缓","hookAppeal":"悬念有力"},
            "continuity":{"score":7,"smoothAreas":["时间线顺畅"],"confusingAreas":["第5章场景跳跃"],
             "characterConsistency":"主角一致"},
            "artistry":{"score":9,"highlights":["账册意象"],"weakSpots":["说明文偏多"],
             "dialogueQuality":"鲜活","proseQuality":"凝练"},
            "chapterHighlights":[{"chapterIndex":1,"highlight":"伏笔埋设","concern":"无"}],
            "overall":{"verdict":"愿意追读","topSuggestion":"加强中段推进"}}
            """;

    private ReaderPerspectiveAgent agentReturning(String json, AtomicReference<String> sysPrompt,
                                                   AtomicInteger calls) {
        LlmGateway gateway = new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest req) {
                if (sysPrompt != null) sysPrompt.set(req.getSystemPrompt());
                if (calls != null) calls.incrementAndGet();
                return new LlmResponse(json, 100, 2000);
            }
            @Override public void streamComplete(LlmRequest req, StreamHandler h) {}
        };
        return new ReaderPerspectiveAgent(gateway, repository, TestPromptHelper.create());
    }

    @Test
    void 三维度报告产出且含覆盖率声明() {
        var sysPrompt = new AtomicReference<String>();
        var result = agentReturning(VALID_JSON, sysPrompt, null).review(pid);

        assertEquals(VALID_JSON.trim(), result.reportJson().trim());
        assertEquals(8, result.totalChapters());
        assertTrue(result.chaptersReviewed() >= 3, "至少采样 3 章");
        assertTrue(result.coveragePercent() >= 20, "覆盖率 ≥20%");
        String prompt = sysPrompt.get();
        assertTrue(prompt.contains("评审证据覆盖"), "必须携带覆盖率声明");
        assertTrue(prompt.contains("第1章"), "首章入选");
        assertTrue(prompt.contains("第8章"), "末章入选");
        assertTrue(prompt.contains("你是一位资深读者"), "读者人设");
        assertTrue(prompt.contains("剧情") && prompt.contains("连续性") && prompt.contains("艺术性"));
    }

    @Test
    void 只读性_零ContinuityIssue落库() {
        agentReturning(VALID_JSON, null, null).review(pid);
        assertEquals(0, repository.findUnresolvedIssues(pid).size(),
                "读者评审不得产出 ContinuityIssue");
    }

    @Test
    void 解析失败_fail_loud() {
        assertThrows(IllegalStateException.class,
                () -> agentReturning("not json at all", null, null).review(pid),
                "坏输出必须抛异常（不伪装成功）");
    }

    @Test
    void 无章节时拒绝() {
        var empty = new Project("空", "构思", "mystery");
        repository.saveProject(empty);
        assertThrows(IllegalStateException.class,
                () -> agentReturning(VALID_JSON, null, null).review(empty.getId()));
    }

    @Test
    void 单次LLM调用_无重试浪费() {
        var calls = new AtomicInteger();
        agentReturning(VALID_JSON, null, calls).review(pid);
        assertEquals(1, calls.get(), "正常路径恰好一次调用");
    }
}
