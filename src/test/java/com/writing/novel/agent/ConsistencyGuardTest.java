package com.writing.novel.agent;

import com.writing.novel.domain.ContinuityIssue;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.memory.RelevanceGateway;
import com.writing.novel.spi.NovelRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * p11-q3-05: ConsistencyGuard 检测单测——补齐探索发现的缺口（三路径重写零专门测试）。
 */
class ConsistencyGuardTest {

    private static LlmGateway llmReturning(String json) {
        return new LlmGateway() {
            public LlmResponse complete(LlmRequest r) { return new LlmResponse(json, 10, 10); }
            public void streamComplete(LlmRequest r, StreamHandler h) {}
        };
    }

    private static String content(int len) {
        StringBuilder sb = new StringBuilder(len + 4);
        while (sb.length() < len) sb.append("正文片段");
        return sb.toString();
    }

    @Test
    void 解析能力越界_凭空证据_时代穿帮三类问题() {
        var repo = mock(NovelRepository.class);
        var gw = mock(RelevanceGateway.class);
        when(gw.relevantCharacters(any(), any(), anyInt())).thenReturn(List.of());
        when(gw.relevantFacts(any(), any(), anyInt())).thenReturn(List.of());
        String json = "{\"abilityViolations\":[{\"character\":\"李四\",\"violation\":\"翻墙\",\"setting\":\"不习武\"}]," +
                "\"evidenceProblems\":[{\"evidence\":\"密信\",\"reason\":\"无铺垫\"}]," +
                "\"anachronisms\":[{\"word\":\"数据库\",\"context\":\"...\",\"suggestion\":\"卷宗\"}]}";
        var guard = new ConsistencyGuard(llmReturning(json), repo, TestPromptHelper.create(), gw);
        var issues = guard.check("p", 1, content(300), List.of("李四"));
        assertEquals(3, issues.size());
        assertTrue(issues.stream().anyMatch(i -> i.getType() == ContinuityIssue.Type.CHARACTER
                && i.getSeverity() == ContinuityIssue.Severity.HIGH));
        assertTrue(issues.stream().anyMatch(i -> i.getType() == ContinuityIssue.Type.PLOT
                && i.getSeverity() == ContinuityIssue.Severity.HIGH));
        assertTrue(issues.stream().anyMatch(i -> i.getType() == ContinuityIssue.Type.WORLDBUILDING
                && i.getSeverity() == ContinuityIssue.Severity.MEDIUM));
    }

    @Test
    void 无问题返回空列表() {
        var repo = mock(NovelRepository.class);
        var gw = mock(RelevanceGateway.class);
        when(gw.relevantCharacters(any(), any(), anyInt())).thenReturn(List.of());
        when(gw.relevantFacts(any(), any(), anyInt())).thenReturn(List.of());
        var guard = new ConsistencyGuard(
                llmReturning("{\"abilityViolations\":[],\"evidenceProblems\":[],\"anachronisms\":[]}"),
                repo, TestPromptHelper.create(), gw);
        assertTrue(guard.check("p", 1, content(300)).isEmpty());
    }
}
