package com.writing.novel.agent;

import com.writing.novel.agent.util.PostProcessStageFailure;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-119: 关系/桥段族 fail-loud——RelationshipEvolutionTracker 与 PlotMotifTracker
 * 的终态失败抛 PostProcessStageFailure（旧 catch→0/空：本章关系变化与桥段记录永久丢失且无人知晓）。
 */
class TrackerFailLoudTest {

    @TempDir
    Path tempDir;
    private SqliteNovelRepository repository;
    private String pid;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("tracker.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repository = new SqliteNovelRepository(cp);
        var project = new com.writing.novel.domain.Project("tracker", "构思", "wuxia");
        repository.saveProject(project);
        pid = project.getId();
    }

    private static LlmGateway garbage() {
        return new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest req) { return new LlmResponse("broken", 1, 1); }
            @Override public void streamComplete(LlmRequest req, StreamHandler h) {}
        };
    }

    private static String longContent() {
        return "足够长的正文用于触发检测门槛。".repeat(30);
    }

    @Test
    void 关系演化解析失败抛阶段异常() {
        var tracker = new RelationshipEvolutionTracker(garbage(), repository, TestPromptHelper.create());
        assertThrows(PostProcessStageFailure.class,
                () -> tracker.detectAndApply(pid, 3, longContent(), "林凡,赵九"),
                "旧：静默 0 伪装无关系变化");
    }

    @Test
    void 桥段提取解析失败抛阶段异常() {
        var tracker = new PlotMotifTracker(garbage(), repository, TestPromptHelper.create());
        assertThrows(PostProcessStageFailure.class,
                () -> tracker.trackAndDetect(pid, 3, longContent()),
                "旧：静默空列表伪装无桥段");
    }

    @Test
    void 成功路径正常() {
        LlmGateway ok = new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest req) {
                if (req.getSystemPrompt().contains("关系")) {
                    return new LlmResponse("{\"evolutions\":[]}", 1, 1);
                }
                return new LlmResponse("{\"motifs\":[]}", 1, 1);
            }
            @Override public void streamComplete(LlmRequest req, StreamHandler h) {}
        };
        var rel = new RelationshipEvolutionTracker(ok, repository, TestPromptHelper.create());
        assertEquals(0, rel.detectAndApply(pid, 3, longContent(), "林凡"));
        var motif = new PlotMotifTracker(ok, repository, TestPromptHelper.create());
        assertTrue(motif.trackAndDetect(pid, 3, longContent()).isEmpty());
    }

    @Test
    void 短正文直接返回不触发LLM() {
        var tracker = new RelationshipEvolutionTracker(garbage(), repository, TestPromptHelper.create());
        assertEquals(0, tracker.detectAndApply(pid, 3, "短", "林凡"), "短正文门槛行为不变");
    }
}
