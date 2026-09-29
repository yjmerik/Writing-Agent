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
import org.mockito.Mockito;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-118: 伏笔族后处理 fail-loud——提取/回收检测/重要性审计的解析终态失败
 * 抛 PostProcessStageFailure（旧 catch→空结果伪装成功：该章伏笔永久丢失且无人知晓）。
 */
class ForeshadowFailLoudTest {

    @TempDir
    Path tempDir;
    private SqliteNovelRepository repository;
    private String pid;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("foreshadow.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repository = new SqliteNovelRepository(cp);
        var project = new com.writing.novel.domain.Project("fail-loud", "构思", "wuxia");
        repository.saveProject(project);
        pid = project.getId();
    }

    private static LlmGateway garbageGateway() {
        return new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest req) { return new LlmResponse("broken", 1, 1); }
            @Override public void streamComplete(LlmRequest req, StreamHandler h) {}
        };
    }

    @Test
    void 提取终态失败抛阶段异常() {
        var extractor = new ForeshadowingExtractor(garbageGateway(), repository, TestPromptHelper.create());
        var chapter = new com.writing.novel.domain.Chapter(pid, 1, "第一章");
        chapter.setContent("正文内容");
        assertThrows(PostProcessStageFailure.class, () -> extractor.extract(pid, chapter),
                "解析重试仍失败必须抛阶段异常（旧：静默返回 0 伪装无伏笔）");
    }

    @Test
    void 回收检测失败抛阶段异常() {
        repository.saveForeshadowing(new com.writing.novel.domain.Foreshadowing(pid, "断剑之谜", "ctx", 1));
        var detector = new ForeshadowingResolutionDetector(garbageGateway(), repository, TestPromptHelper.create());
        assertThrows(PostProcessStageFailure.class,
                () -> detector.detectAndResolve(pid, 3, "本章正文提及断剑并揭晓来历。"),
                "回收检测失败必须抛阶段异常（旧：静默 0 伪装本章无回收）");
    }

    @Test
    void 重要性审计失败抛阶段异常() {
        repository.saveForeshadowing(new com.writing.novel.domain.Foreshadowing(pid, "旧线", "ctx", 1));
        var auditor = new ForeshadowingImportanceAuditor(garbageGateway(), repository, TestPromptHelper.create());
        assertThrows(PostProcessStageFailure.class, () -> auditor.audit(pid),
                "审计失败必须抛阶段异常（旧：静默 0）");
    }

    @Test
    void 成功路径不受影响() {
        LlmGateway ok = new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest req) {
                return new LlmResponse("{\"foreshadowings\":[]}", 1, 1);
            }
            @Override public void streamComplete(LlmRequest req, StreamHandler h) {}
        };
        var extractor = new ForeshadowingExtractor(ok, repository, TestPromptHelper.create());
        var chapter = new com.writing.novel.domain.Chapter(pid, 1, "第一章");
        chapter.setContent("正文");
        assertEquals(0, extractor.extract(pid, chapter), "合法空结果正常返回 0");
    }

    @Test
    void 阶段异常携带阶段名() {
        var e = new PostProcessStageFailure("foreshadow-extract", "INVALID: bad");
        assertEquals("foreshadow-extract", e.stage());
        assertTrue(e.getMessage().contains("foreshadow-extract"));
    }
}
