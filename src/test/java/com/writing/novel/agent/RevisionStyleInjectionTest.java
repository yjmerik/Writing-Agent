package com.writing.novel.agent;

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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-127: 修订路径风格注入——passage-edit 携带全书风格约束与范本
 * （此前局部改写与全章重写标准不一致）。
 */
class RevisionStyleInjectionTest {

    @TempDir
    Path tempDir;
    private SqliteNovelRepository repository;
    private String pid;
    private final AtomicReference<String> lastSystemPrompt = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("revstyle.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repository = new SqliteNovelRepository(cp);
        var project = new Project("修订风格", "构思", "wuxia");
        repository.saveProject(project);
        pid = project.getId();
        var style = new StyleProfile(pid);
        style.setTone("白描传神");
        style.setVoice("第三人称，动作干净");
        style.addStyleSample("他推门进来，肩上落着雪。");
        repository.saveStyleProfile(style);
    }

    private LlmGateway captureGateway() {
        return new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest req) {
                lastSystemPrompt.set(req.getSystemPrompt());
                return new LlmResponse("编辑后的段落文字。", 10, 20);
            }
            @Override public void streamComplete(LlmRequest req, StreamHandler h) {}
        };
    }

    @Test
    void 段落编辑携带风格约束与范本() {
        var editor = new PassageEditor(captureGateway(), TestPromptHelper.create());
        editor.setRepository(repository);
        editor.editPassage(pid, "原段落文字。", "前文。", "后文。", "改善表达", "REWRITE");

        String prompt = lastSystemPrompt.get();
        assertTrue(prompt.contains("白描传神"), "修订 prompt 携带基调");
        assertTrue(prompt.contains("肩上落着雪"), "修订 prompt 携带范本 few-shot");
    }

    @Test
    void 无projectId或无档案_风格段为空_行为不变() {
        var editor = new PassageEditor(captureGateway(), TestPromptHelper.create());
        editor.editPassage("段落。", "前", "后", "指令", "REWRITE");   // 无仓库/无 projectId
        String prompt = lastSystemPrompt.get();
        assertFalse(prompt.contains("风格范本"), "未配置时不得注入范本段");
        assertTrue(prompt.contains("段落"), "编辑正文照常");
    }
}
