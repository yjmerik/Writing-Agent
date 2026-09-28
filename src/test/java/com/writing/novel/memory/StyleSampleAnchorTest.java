package com.writing.novel.memory;

import com.writing.novel.agent.ChapterWriterAgent;
import com.writing.novel.agent.TestPromptHelper;
import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.StyleProfile;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * NCS-102: 风格例文锚点——
 * 1) StyleProfile.styleSamples 持久化往返（JSON 列，含换行文本）；
 * 2) 非空时 chapter-writing prompt 注入范本 few-shot 段；
 * 3) 为空时 prompt 与旧行为一致（不注入范本段）。
 */
class StyleSampleAnchorTest {

    @TempDir
    Path tempDir;
    private SqliteNovelRepository repository;
    private final AtomicReference<String> lastSystemPrompt = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("samples.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repository = new SqliteNovelRepository(cp);
    }

    private ChapterWriterAgent writer() {
        WritingContext ctx = new WritingContext();
        ctx.setTaskSection("第一章 开篇");
        ContextAssembler assembler = new ContextAssembler(null, null, null, null, null, null, null, null, null) {
            @Override public WritingContext assemble(String pid, int ci, String ct, String cs, String wn) { return ctx; }
            @Override public WritingContext assemble(String pid, int ci, String ct, String cs, String wn, int budget) { return ctx; }
        };
        LlmGateway gateway = new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest req) {
                lastSystemPrompt.set(req.getSystemPrompt());
                return new LlmResponse("夜风掠过檐角，铁马轻响。".repeat(200), 100, 400);
            }
            @Override public void streamComplete(LlmRequest req, StreamHandler h) {}
        };
        return new ChapterWriterAgent(gateway, assembler, repository, TestPromptHelper.create());
    }

    @Test
    void 例文范本持久化往返() {
        var project = new Project("范本测试", "构思", "wuxia");
        repository.saveProject(project);
        var style = new StyleProfile(project.getId());
        style.setTone("沉雄遒劲");
        style.addStyleSample("暮色四合，钟声自远山而来。\n他在檐下立了片刻，指节因握剑而过白。");
        style.addStyleSample("雪落无声。旧年的誓言，此刻只余炉中一点将熄的火。");
        repository.saveStyleProfile(style);

        var loaded = repository.findStyleProfileByProject(project.getId()).orElseThrow();
        assertEquals(2, loaded.getStyleSamples().size());
        assertTrue(loaded.getStyleSamples().get(0).contains("\n"), "例文含换行应完整保留");
        assertEquals("沉雄遒劲", loaded.getTone());
    }

    @Test
    void 范本非空时注入写作prompt() {
        var project = new Project("注入测试", "构思", "wuxia");
        repository.saveProject(project);
        var style = new StyleProfile(project.getId());
        style.setTone("白描传神");
        style.addStyleSample("他推门进来，肩上落着雪。");
        repository.saveStyleProfile(style);

        Chapter ch = writer().write(project.getId(), 1, "第一章", "开篇", null);
        assertNotNull(ch);
        String prompt = lastSystemPrompt.get();
        assertTrue(prompt.contains("风格范本"), "应注入范本段标题");
        assertTrue(prompt.contains("他推门进来，肩上落着雪。"), "范本内容应进入 prompt");
    }

    @Test
    void 范本为空时行为不变() {
        var project = new Project("空范本测试", "构思", "wuxia");
        repository.saveProject(project);
        var style = new StyleProfile(project.getId());
        style.setTone("简洁");
        repository.saveStyleProfile(style);

        writer().write(project.getId(), 1, "第一章", "开篇", null);
        assertFalse(lastSystemPrompt.get().contains("风格范本"), "无范本时不得注入范本段");
    }
}
