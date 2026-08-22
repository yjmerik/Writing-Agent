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
    void 回放miss时转储完整请求供指纹诊断() throws Exception {
        LlmGateway replayer = new RecordReplayLlmGateway(
                explodingDelegate(), RecordReplayLlmGateway.Mode.REPLAY, fixtureDir);
        assertThrows(IllegalStateException.class,
                () -> replayer.complete(request("发散的请求")));
        String[] misses = fixtureDir.toFile().list((d, n) -> n.endsWith(".miss.json"));
        assertEquals(1, misses != null ? misses.length : 0, "应转储一个 .miss.json");
        String dumped = java.nio.file.Files.readString(fixtureDir.resolve(misses[0]));
        assertTrue(dumped.contains("你是测试助手"), "miss 转储应含完整 systemPrompt");
        assertTrue(dumped.contains("发散的请求"), "miss 转储应含完整 userMessage");
    }

    @Test
    void 录制fixture保存完整请求() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        LlmGateway recorder = new RecordReplayLlmGateway(
                fixedDelegate("内容", calls), RecordReplayLlmGateway.Mode.RECORD, fixtureDir);
        recorder.complete(request("完整请求"));
        String[] fixtures = fixtureDir.toFile().list((d, n) -> n.endsWith(".json"));
        assertEquals(1, fixtures.length);
        String json = java.nio.file.Files.readString(fixtureDir.resolve(fixtures[0]));
        assertTrue(json.contains("你是测试助手"), "fixture 应含完整 systemPrompt");
        assertTrue(json.contains("完整请求"), "fixture 应含完整 userMessage");
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

    /**
     * mc-p3-01: 录制时失败的请求也必须能回放——复现相同异常类型，
     * 让 ContentFilterException 等触发相同的重试路径（record→replay 完全确定）。
     */
    @Test
    void 录制失败可回放相同异常() {
        LlmGateway failingDelegate = new LlmGateway() {
            @Override
            public LlmResponse complete(LlmRequest req) {
                throw new com.writing.novel.llm.client.ContentFilterException("内容审查拦截: 暴力描写");
            }
            @Override
            public void streamComplete(LlmRequest req, StreamHandler handler) {}
        };
        LlmGateway recorder = new RecordReplayLlmGateway(
                failingDelegate, RecordReplayLlmGateway.Mode.RECORD, fixtureDir);
        assertThrows(com.writing.novel.llm.client.ContentFilterException.class,
                () -> recorder.complete(request("含敏感内容")));
        assertTrue(fixtureDir.resolve("GENERAL-" + firstFixtureHash() + ".json").toFile().exists()
                || fixtureDir.toFile().list().length >= 1, "失败请求应录制 fixture");

        LlmGateway replayer = new RecordReplayLlmGateway(
                explodingDelegate(), RecordReplayLlmGateway.Mode.REPLAY, fixtureDir);
        com.writing.novel.llm.client.ContentFilterException ex = assertThrows(
                com.writing.novel.llm.client.ContentFilterException.class,
                () -> replayer.complete(request("含敏感内容")));
        assertTrue(ex.getMessage().contains("内容审查拦截"), "应复现录制时的异常消息: " + ex.getMessage());
    }

    private String firstFixtureHash() {
        String[] files = fixtureDir.toFile().list();
        for (String f : files) {
            if (f.endsWith(".json")) return f.replace("GENERAL-", "").replace(".json", "");
        }
        return "";
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
