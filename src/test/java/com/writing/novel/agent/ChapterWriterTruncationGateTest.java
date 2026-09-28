package com.writing.novel.agent;

import com.writing.novel.domain.Chapter;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.memory.ContextAssembler;
import com.writing.novel.memory.WritingContext;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-107: ChapterWriter 截断门禁——finish_reason=length 的半章（即使字数达标）不得直接入库：
 * 先续写补全一次；补全成功才合并落库；补全仍截断则失败不入库。
 */
class ChapterWriterTruncationGateTest {

    @TempDir
    Path tempDir;
    private SqliteNovelRepository repository;
    private String pid;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("truncate.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repository = new SqliteNovelRepository(cp);
        var project = new com.writing.novel.domain.Project("截断测试", "构思", "wuxia");
        repository.saveProject(project);
        pid = project.getId();
    }

    private ChapterWriterAgent writer(LlmGateway gateway) {
        WritingContext ctx = new WritingContext();
        ctx.setTaskSection("第一章 夜探");
        ContextAssembler assembler = new ContextAssembler(null, null, null, null, null, null, null, null, null) {
            @Override public WritingContext assemble(String p, int ci, String ct, String cs, String wn) { return ctx; }
            @Override public WritingContext assemble(String p, int ci, String ct, String cs, String wn, int budget) { return ctx; }
        };
        return new ChapterWriterAgent(gateway, assembler, repository, TestPromptHelper.create());
    }

    @Test
    void 截断响应_触发续写补全_合并落库() {
        String head = "夜色沉沉他提剑入府。".repeat(260);   // ≥2600 字，字数达标但被截断
        String tail = "钟声三响他隐入更深的黑暗。".repeat(120);
        AtomicInteger continuationCalls = new AtomicInteger();
        LlmGateway gateway = new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest req) {
                boolean isContinuation = req.getSystemPrompt() != null && req.getSystemPrompt().contains("续写");
                if (isContinuation) {
                    continuationCalls.incrementAndGet();
                    return new LlmResponse(tail, 50, 500, "stop", null);
                }
                // 首次生成：字数达标但 finish_reason=length（当前缺陷：直接入库）
                return new LlmResponse(head, 100, 20000, "length", null);
            }
            @Override public void streamComplete(LlmRequest req, StreamHandler h) {}
        };

        Chapter ch = writer(gateway).write(pid, 1, "第一章", "夜探", null);
        assertEquals(1, continuationCalls.get(), "截断响应必须触发一次续写补全");
        String saved = repository.findChapterByIndex(pid, 1).orElseThrow().getContent();
        assertTrue(saved.contains(head.substring(0, 20)), "已写头部保留");
        assertTrue(saved.contains("钟声三响"), "续写内容合并入库");
    }

    @Test
    void 续写仍截断_失败不入库() {
        LlmGateway gateway = new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest req) {
                boolean isContinuation = req.getSystemPrompt() != null && req.getSystemPrompt().contains("续写");
                return new LlmResponse("长文".repeat(isContinuation ? 1300 : 260), 100, 20000,
                        "length", null);
            }
            @Override public void streamComplete(LlmRequest req, StreamHandler h) {}
        };

        assertThrows(RuntimeException.class, () -> writer(gateway).write(pid, 1, "第一章", "夜探", null),
                "续写后仍截断必须失败");
        assertTrue(repository.findChapterByIndex(pid, 1).isEmpty(), "腰斩章节不得入库");
    }

    @Test
    void 正常完整响应_不触发续写() {
        LlmGateway gateway = new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest req) {
                return new LlmResponse("完整的章节正文内容。".repeat(260), 100, 800, "stop", null);
            }
            @Override public void streamComplete(LlmRequest req, StreamHandler h) {}
        };
        Chapter ch = writer(gateway).write(pid, 1, "第一章", "夜探", null);
        assertNotNull(ch);
        assertFalse(ch.getContent().contains("续写"), "完整响应不得注入续写内容");
    }
}
