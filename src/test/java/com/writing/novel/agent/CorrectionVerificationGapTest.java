package com.writing.novel.agent;

import com.writing.novel.domain.ContinuityIssue;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.telemetry.EventLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * NCS-069: 复验缺项补问。
 * 观察到的失败（quality-closure-rerecord-20260921.json）：纠偏复验输入 2 项，模型只返回 1 项
 * UNKNOWN——严格覆盖校验直接整体失败 → pauseForQuality 屏障。本特性保留严格校验，
 * 缺项时对缺失 issueId 发起一次补问并合并结论；补问后仍缺才屏障。
 */
class CorrectionVerificationGapTest {

    private LlmGateway gateway;
    private ConsistencyGuard guard;
    private Path eventsFile;
    private final String content = "裴照微取得会票。会票上有盐霜印记。".repeat(20);

    @BeforeEach
    void setUp(@TempDir Path tmp) throws Exception {
        gateway = mock(LlmGateway.class);
        guard = new ConsistencyGuard(gateway, mock(com.writing.novel.spi.NovelRepository.class),
                TestPromptHelper.create(), null);
        eventsFile = tmp.resolve("events.jsonl");
        System.setProperty(EventLog.PATH_PROPERTY, eventsFile.toString());
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(EventLog.PATH_PROPERTY);
    }

    private List<ContinuityIssue> twoFindings() {
        return List.of(
                new ContinuityIssue("p", 3, ContinuityIssue.Type.PLOT,
                        ContinuityIssue.Severity.HIGH, "会票来源未交代"),
                new ContinuityIssue("p", 3, ContinuityIssue.Type.CHARACTER,
                        ContinuityIssue.Severity.HIGH, "盐帮人物动机不明"));
    }

    @Test
    void 缺项一次补问后覆盖完整不屏障() {
        when(gateway.complete(any())).thenReturn(
                new LlmResponse("{\"chapterVersion\":1,\"results\":["
                        + "{\"issueId\":\"1\",\"status\":\"RESOLVED\",\"evidence\":\"取得会票\",\"reason\":\"已交代\"}]}", 1, 1),
                new LlmResponse("{\"chapterVersion\":1,\"results\":["
                        + "{\"issueId\":\"2\",\"status\":\"UNRESOLVED\",\"reason\":\"仍未交代\"}]}", 1, 1));

        var verdicts = guard.verifyCorrections("p", 3, 1, content, twoFindings(), "settings");

        assertEquals(2, verdicts.size(), "补问合并后必须覆盖全部问题");
        assertEquals("RESOLVED", verdicts.get(0).status());
        assertEquals("UNRESOLVED", verdicts.get(1).status());
        verify(gateway, times(2)).complete(any());

        // 补问请求只包含缺失项
        ArgumentCaptor<LlmRequest> captor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(gateway, times(2)).complete(captor.capture());
        String refillInput = captor.getAllValues().get(1).getUserMessage();
        assertTrue(refillInput.contains("盐帮人物动机不明"), "补问输入应包含缺失项描述");
        assertFalse(refillInput.contains("会票来源未交代"), "补问输入不应包含已有结论项");

        String events = readEvents();
        assertTrue(events.contains("\"event\":\"CORRECTION_GAP_REFILL\""), "补问必须可观测: " + events);
        assertTrue(events.contains("\"missing\":\"2\"") || events.contains("\"missing\":[\"2\"]")
                || events.contains("\"missing\":\"2\","), events);
    }

    @Test
    void 补问仍缺才屏障() {
        when(gateway.complete(any())).thenReturn(
                new LlmResponse("{\"chapterVersion\":1,\"results\":["
                        + "{\"issueId\":\"1\",\"status\":\"UNKNOWN\",\"reason\":\"部分回答\"}]}", 1, 1),
                new LlmResponse("{\"chapterVersion\":1,\"results\":["
                        + "{\"issueId\":\"1\",\"status\":\"UNKNOWN\",\"reason\":\"仍然缺项\"}]}", 1, 1));

        var ex = assertThrows(IllegalStateException.class,
                () -> guard.verifyCorrections("p", 3, 1, content, twoFindings(), "settings"));
        assertTrue(ex.getMessage().contains("逐项复验"), ex.getMessage());
        // 补问恰好一次
        verify(gateway, times(2)).complete(any());
    }

    @Test
    void 完整响应不触发补问() {
        when(gateway.complete(any())).thenReturn(new LlmResponse("{\"chapterVersion\":1,\"results\":["
                + "{\"issueId\":\"1\",\"status\":\"RESOLVED\",\"evidence\":\"取得会票\",\"reason\":\"ok\"},"
                + "{\"issueId\":\"2\",\"status\":\"UNKNOWN\",\"reason\":\" unclear\"}]}", 1, 1));

        var verdicts = guard.verifyCorrections("p", 3, 1, content, twoFindings(), "settings");

        assertEquals(2, verdicts.size());
        verify(gateway, times(1)).complete(any());
        String events = readEvents();
        assertFalse(events.contains("CORRECTION_GAP_REFILL"), "完整响应不得触发补问: " + events);
    }

    private String readEvents() {
        try {
            return Files.exists(eventsFile) ? Files.readString(eventsFile) : "";
        } catch (Exception e) {
            return "";
        }
    }
}
