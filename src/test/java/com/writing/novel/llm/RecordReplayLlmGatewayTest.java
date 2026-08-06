package com.writing.novel.llm;

import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.llm.gateway.RecordReplayLlmGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RecordReplayLlmGatewayTest {

    @TempDir
    Path fixtureDir;

    private static LlmRequest request(String userMessage) {
        return LlmRequest.builder()
                .taskType(LlmTaskType.GENERAL)
                .systemPrompt("你是测试助手")
                .userMessage(userMessage)
                .build();
    }

    private static LlmGateway fixedDelegate(String content, AtomicInteger callCount) {
        return new LlmGateway() {
            @Override
            public LlmResponse complete(LlmRequest req) {
                callCount.incrementAndGet();
                return new LlmResponse(content, 10, 20);
            }

            @Override
            public void streamComplete(LlmRequest req, StreamHandler handler) {
                callCount.incrementAndGet();
                handler.onToken(content);
                handler.onComplete();
            }
        };
    }

    private static LlmGateway explodingDelegate() {
        return new LlmGateway() {
            @Override
            public LlmResponse complete(LlmRequest req) {
                throw new AssertionError("REPLAY 模式不应触达真实网关");
            }

            @Override
            public void streamComplete(LlmRequest req, StreamHandler handler) {
                throw new AssertionError("REPLAY 模式不应触达真实网关");
            }
        };
    }

    @Test
    void 录制后回放返回相同内容且不触达真实网关() {
        AtomicInteger calls = new AtomicInteger();
        LlmGateway recorder = new RecordReplayLlmGateway(
                fixedDelegate("第一章正文内容", calls), RecordReplayLlmGateway.Mode.RECORD, fixtureDir);
        LlmResponse recorded = recorder.complete(request("写第一章"));
        assertEquals("第一章正文内容", recorded.getContent());
        assertEquals(1, calls.get());

        LlmGateway replayer = new RecordReplayLlmGateway(
                explodingDelegate(), RecordReplayLlmGateway.Mode.REPLAY, fixtureDir);
        LlmResponse replayed = replayer.complete(request("写第一章"));
        assertEquals("第一章正文内容", replayed.getContent());
        assertEquals(10, replayed.getPromptTokens());
        assertEquals(20, replayed.getCompletionTokens());
    }

    @Test
    void 回放缺失fixture时快速失败并提示补录() {
        LlmGateway replayer = new RecordReplayLlmGateway(
                explodingDelegate(), RecordReplayLlmGateway.Mode.REPLAY, fixtureDir);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> replayer.complete(request("从未录制过的请求")));
        assertTrue(ex.getMessage().contains("record"));
    }

    @Test
    void 不同请求产生不同fixture互不串扰() {
        AtomicInteger calls = new AtomicInteger();
        LlmGateway recorder = new RecordReplayLlmGateway(
                fixedDelegate("内容A", calls), RecordReplayLlmGateway.Mode.RECORD, fixtureDir);
        recorder.complete(request("请求A"));

        LlmGateway recorder2 = new RecordReplayLlmGateway(
                fixedDelegate("内容B", calls), RecordReplayLlmGateway.Mode.RECORD, fixtureDir);
        recorder2.complete(request("请求B"));

        LlmGateway replayer = new RecordReplayLlmGateway(
                explodingDelegate(), RecordReplayLlmGateway.Mode.REPLAY, fixtureDir);
        assertEquals("内容A", replayer.complete(request("请求A")).getContent());
        assertEquals("内容B", replayer.complete(request("请求B")).getContent());
    }

    @Test
    void 回放忽略prompt中易变的ISO时间戳() {
        AtomicInteger calls = new AtomicInteger();
        LlmGateway recorder = new RecordReplayLlmGateway(
                fixedDelegate("偏好已更新", calls), RecordReplayLlmGateway.Mode.RECORD, fixtureDir);
        recorder.complete(requestWithTimestamp("2026-08-05T16:03:14.317767Z"));

        LlmGateway replayer = new RecordReplayLlmGateway(
                explodingDelegate(), RecordReplayLlmGateway.Mode.REPLAY, fixtureDir);
        LlmResponse replayed = replayer.complete(requestWithTimestamp("2026-08-06T00:04:04.765757Z"));

        assertEquals("偏好已更新", replayed.getContent());
    }

    @Test
    void 流式录制后可流式回放() {
        AtomicInteger calls = new AtomicInteger();
        LlmGateway recorder = new RecordReplayLlmGateway(
                fixedDelegate("流式正文", calls), RecordReplayLlmGateway.Mode.RECORD, fixtureDir);
        StringBuilder recordedOut = new StringBuilder();
        recorder.streamComplete(request("流式请求"), collectingHandler(recordedOut));
        assertEquals("流式正文", recordedOut.toString());

        LlmGateway replayer = new RecordReplayLlmGateway(
                explodingDelegate(), RecordReplayLlmGateway.Mode.REPLAY, fixtureDir);
        StringBuilder replayedOut = new StringBuilder();
        replayer.streamComplete(request("流式请求"), collectingHandler(replayedOut));
        assertEquals("流式正文", replayedOut.toString());
    }

    private static StreamHandler collectingHandler(StringBuilder out) {
        return new StreamHandler() {
            @Override
            public void onToken(String token) { out.append(token); }

            @Override
            public void onComplete() {}

            @Override
            public void onError(Throwable error) { fail(error); }
        };
    }

    private static LlmRequest requestWithTimestamp(String updatedAt) {
        return LlmRequest.builder()
                .taskType(LlmTaskType.GENERAL)
                .systemPrompt("当前配置: {\"updatedAt\":\"" + updatedAt + "\",\"minChapterLength\":2000}")
                .userMessage("设置每章字数")
                .build();
    }
}
