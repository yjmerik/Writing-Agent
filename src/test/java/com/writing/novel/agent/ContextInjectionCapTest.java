package com.writing.novel.agent;

import com.writing.novel.domain.Foreshadowing;
import com.writing.novel.domain.NarrativeThread;
import com.writing.novel.domain.ThreadStatus;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.llm.prompt.PromptTemplateService;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * p16-04: 三处全量注入加 cap——ForeshadowingExtractor 既有清单 /
 * ContinuityChecker 叙事线索台账 / collectSettingsForCheck 待回收伏笔。
 * 同族全量加载反模式的漏网点（写作主上下文已走 RelevanceGateway 限额）。
 */
class ContextInjectionCapTest {

    @TempDir
    Path tempDir;
    private SqliteNovelRepository repo;
    private CapturingGateway gateway;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("cap.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        gateway = new CapturingGateway("[]");
    }

    private void seedForeshadowings(String projectId, int resolvedCount, int unresolvedCount) {
        for (int i = 0; i < resolvedCount; i++) {
            Foreshadowing f = new Foreshadowing(projectId, "已回收伏笔" + i, "ctx", 1);
            f.resolve(1);
            repo.saveForeshadowing(f);
        }
        for (int i = 0; i < unresolvedCount; i++) {
            Foreshadowing f = new Foreshadowing(projectId, "未回收伏笔" + i, "ctx", 2 + i);
            f.setMustResolve(i == 0 || i == 1); // 前 2 条 mustResolve
            repo.saveForeshadowing(f);
        }
    }

    @Test
    void 伏笔提取器既有清单有上限且未回收优先() {
        String projectId = "proj-fe";
        seedForeshadowings(projectId, 40, 25);
        var extractor = new ForeshadowingExtractor(gateway, repo, TestPromptHelper.create());

        var chapter = new com.writing.novel.domain.Chapter(projectId, 30, "第30章");
        chapter.setContent("正文内容");
        extractor.extract(projectId, chapter);

        // 只统计「已有伏笔清单」段内的条目（模板正文自带示例 bullet，不计入）
        String listSection = gateway.lastSystemPrompt.substring(
                Math.max(0, gateway.lastSystemPrompt.indexOf("已有伏笔清单")));
        int listed = listSection.split("\n- ", -1).length - 1;
        assertTrue(listed <= 20, "既有伏笔清单应 ≤20 条，实际 " + listed);
        assertTrue(listSection.contains("未回收伏笔0"), "未回收伏笔应优先保留");
        assertTrue(listSection.contains("未回收伏笔1"), "mustResolve 伏笔必须保留");
        assertFalse(listSection.contains("已回收伏笔"), "已回收伏笔在未回收数满额时应被挤出清单");
    }

    @Test
    void 连续性检查线索台账有上限_悬挂最久优先() {
        String projectId = "proj-thread";
        for (int i = 0; i < 40; i++) {
            NarrativeThread t = new NarrativeThread(projectId, "线索" + i, 1 + i, "desc");
            t.setStatus(ThreadStatus.ACTIVE);
            repo.saveNarrativeThread(t);
        }
        var checker = new ContinuityCheckerAgent(gateway, repo, TestPromptHelper.create());

        String history = checker.buildHistorySection(projectId);

        int threadLines = history.split("■ ", -1).length - 1;
        assertTrue(threadLines <= 15, "线索台账应 ≤15 条，实际 " + threadLines);
        assertTrue(history.contains("线索0"), "悬挂最久（首现最早）的线索应优先保留");
        assertFalse(history.contains("线索39"), "最晚出现的线索应被裁剪");
    }

    private static class CapturingGateway implements LlmGateway {
        final String content;
        String lastSystemPrompt;

        CapturingGateway(String content) { this.content = content; }

        @Override
        public LlmResponse complete(LlmRequest request) {
            lastSystemPrompt = request.getSystemPrompt();
            return new LlmResponse(content, 10, 5);
        }

        @Override
        public void streamComplete(LlmRequest request, StreamHandler handler) {
            throw new UnsupportedOperationException();
        }
    }
}
