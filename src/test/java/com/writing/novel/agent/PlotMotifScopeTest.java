package com.writing.novel.agent;

import com.writing.novel.domain.ContinuityIssue;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.client.LlmClient;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.llm.LlmGateway;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * NCS-149（旅程三 O-4）：桥段重复检测不得自匹配——
 * 此前本章桥段先入库后比对，当前章永远与"最近8章（含自己）"重复，
 * 第 2 章即报 3 条假重复（实测《剑冢守夜人》每章恒现）。
 */
class PlotMotifScopeTest {

    @TempDir Path dir;
    SqliteNovelRepository repo;
    PlotMotifTracker tracker;

    /** 网关桩：每章提取出相同的两个桥段标签 */
    private final LlmGateway gateway = new LlmGateway() {
        @Override
        public com.writing.novel.llm.LlmResponse complete(LlmRequest request) {
            return new LlmResponse("{\"motifs\":[\"公堂对峙\",\"真相揭露\"]}", 10, 10, "", null);
        }

        @Override
        public void streamComplete(LlmRequest request, StreamHandler handler) {
            throw new UnsupportedOperationException();
        }
    };

    @BeforeEach
    void setUp() {
        var cp = new ConnectionProvider(dir.resolve("motif.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        PromptTemplateService prompts = mock(PromptTemplateService.class);
        when(prompts.render(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyMap())).thenReturn("模板");
        tracker = new PlotMotifTracker(gateway, repo, prompts);
    }

    private String content() {
        return "第一章正文内容，足够长以通过最小长度门槛。".repeat(30);
    }

    @Test
    void 第1章无历史可比不得自匹配报重复() {
        List<ContinuityIssue> issues = tracker.trackAndDetect("p-motif", 1, content());
        assertTrue(issues.isEmpty(),
                "当前章桥段不得与自身比较——第1章必须零重复报告，实际: "
                        + issues.stream().map(ContinuityIssue::getDescription).toList());
    }

    @Test
    void 后续章真重复仍被检出() {
        tracker.trackAndDetect("p-motif2", 1, content());
        List<ContinuityIssue> issues = tracker.trackAndDetect("p-motif2", 2, content());
        assertEquals(2, issues.size(), "第2章与第1章真重复必须各报一条");
        assertTrue(issues.get(0).getDescription().contains("最近8章"));
    }

    @Test
    void 同章重跑不与自身比较() {
        tracker.trackAndDetect("p-motif3", 1, content());
        List<ContinuityIssue> rerun = tracker.trackAndDetect("p-motif3", 1, content());
        assertTrue(rerun.isEmpty(), "同章重跑（修订后重检）不得与自身上一条记录比对报重复");
    }
}
