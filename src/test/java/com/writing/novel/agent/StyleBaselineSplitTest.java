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
 * NCS-128: 风格基线意图/状态二分——卷末 updateProfile 只演化 state、
 * 意图快照锁定不动；漂移检测基准取意图（检测器不再追随被检对象）。
 */
class StyleBaselineSplitTest {

    @TempDir
    Path tempDir;
    private SqliteNovelRepository repository;
    private String pid;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("split.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repository = new SqliteNovelRepository(cp);
        var project = new Project("二分", "构思", "wuxia");
        repository.saveProject(project);
        pid = project.getId();
        var style = new StyleProfile(pid);
        style.setTone("沉雄遒劲");
        style.setVoice("白描动作");
        style.setDosAndDonts("不用现代词");
        repository.saveStyleProfile(style);
    }

    private StyleGuardianAgent agentReturning(String llmOutput, AtomicReference<String> userMsg) {
        LlmGateway gateway = new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest req) {
                if (userMsg != null) userMsg.set(req.getUserMessage());
                return new LlmResponse(llmOutput, 10, 50);
            }
            @Override public void streamComplete(LlmRequest req, StreamHandler h) {}
        };
        return new StyleGuardianAgent(gateway, repository, TestPromptHelper.create());
    }

    @Test
    void 卷末更新演化state_意图快照锁定不动() {
        var updated = agentReturning(
                "【基调】口语轻快（跑偏方向）\n【叙事】短句连发\n【注意事项】随意", null)
                .updateProfile(pid, "近期章节文本足够长以触发更新。".repeat(30));
        assertNotNull(updated);
        assertEquals("沉雄遒劲", updated.intentTone(), "意图基准不被卷末演化覆写（旧逻辑跑偏被合法化）");
        assertEquals("口语轻快（跑偏方向）", updated.getTone(), "state 字段随卷演化");
        assertTrue(updated.isIntentLocked());

        // 持久化往返：意图快照保留
        var reloaded = repository.findStyleProfileByProject(pid).orElseThrow();
        assertEquals("沉雄遒劲", reloaded.intentTone());
        assertEquals("口语轻快（跑偏方向）", reloaded.getTone());
        assertTrue(reloaded.isIntentLocked());
    }

    @Test
    void 未锁定时意图访问回退当前值() {
        var style = repository.findStyleProfileByProject(pid).orElseThrow();
        assertFalse(style.isIntentLocked());
        assertEquals("沉雄遒劲", style.intentTone(), "未锁定回退当前值（旧数据兼容）");
    }
}
