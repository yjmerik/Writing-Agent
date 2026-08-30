package com.writing.novel.llm.gateway;

import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.LlmTaskType;
import com.writing.novel.llm.client.StreamHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * p16-replay: 调用序号化 fixture——同指纹的每次调用独立录制/回放（-1/-2/-3…）。
 *
 * 根因背景：单文件按指纹命名时，同指纹第 N 次调用会覆盖前次响应。record 时
 * 「调用1 产出副作用（如 guard HIGH 写库）→ 调用2 响应不同」的场景，replay 只能
 * 回放最后一次响应，导致前次副作用对应的决策输入丢失、后续章节 prompt 漂移。
 * 序号化后每次调用的响应各自保留，回放时序与 record 完全一致；分支分叉时
 * 第 N 次缺失的 fail-fast 也比静默错位可诊断得多。
 */
class RecordReplayCallSequenceTest {

    @TempDir
    Path tempDir;
    private Path fixtureDir;
    private int calls;
    private String[] responses;

    private LlmGateway gateway(String mode) {
        calls = 0;
        if (fixtureDir == null) {
            fixtureDir = tempDir.resolve("fx-shared");
        }
        LlmGateway delegate = new LlmGateway() {
            @Override
            public LlmResponse complete(LlmRequest request) {
                String content = responses[Math.min(calls, responses.length - 1)];
                calls++;
                return new LlmResponse(content, 1, 1);
            }
            @Override
            public void streamComplete(LlmRequest request, StreamHandler handler) {}
        };
        return new RecordReplayLlmGateway(delegate,
                "replay".equals(mode) ? RecordReplayLlmGateway.Mode.REPLAY : RecordReplayLlmGateway.Mode.RECORD,
                fixtureDir);
    }

    private LlmRequest request(String sys) {
        return LlmRequest.builder()
                .taskType(LlmTaskType.GENERAL)
                .systemPrompt(sys).userMessage("u").build();
    }

    @Test
    void 同指纹三次调用各自录制独立文件() throws Exception {
        LlmRequest req = request("sys-seq");
        var gw = gateway("record");
        responses = new String[]{"R1", "R2", "R3"};
        assertEquals("R1", gw.complete(req).getContent());
        assertEquals("R2", gw.complete(req).getContent());
        assertEquals("R3", gw.complete(req).getContent());

        try (Stream<Path> files = Files.list(fixtureDir)) {
            List<String> names = files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".json")).sorted().toList();
            assertEquals(3, names.size(), "三次调用应各自成文件: " + names);
            assertTrue(names.get(0).contains("-1.json"), names.toString());
            assertTrue(names.get(1).contains("-2.json"), names.toString());
            assertTrue(names.get(2).contains("-3.json"), names.toString());
        }
    }

    @Test
    void replay按调用次序回放各自响应() {
        LlmRequest req = request("sys-seq2");
        var rec = gateway("record");
        responses = new String[]{"R1", "R2", "R3"};
        rec.complete(req);
        rec.complete(req);
        rec.complete(req);

        var rep = gateway("replay");
        assertEquals("R1", rep.complete(req).getContent(), "第1次回放应得 R1");
        assertEquals("R2", rep.complete(req).getContent(), "第2次回放应得 R2");
        assertEquals("R3", rep.complete(req).getContent(), "第3次回放应得 R3");
    }

    @Test
    void replay次数超出录制时failFast且可诊断() throws Exception {
        LlmRequest req = request("sys-seq3");
        var rec = gateway("record");
        responses = new String[]{"R1"};
        rec.complete(req); // record 只调了 1 次

        var rep = gateway("replay");
        assertEquals("R1", rep.complete(req).getContent());
        var ex = assertThrows(IllegalStateException.class, () -> rep.complete(req),
                "replay 第2次调用（record 未发生）应 fail-fast");
        assertTrue(ex.getMessage().contains("2"), "错误信息应指出是第 2 次调用缺失: " + ex.getMessage());

        try (Stream<Path> files = Files.list(fixtureDir)) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().endsWith("-2.miss.json")),
                    "miss 转储应带调用序号");
        }
    }

    @Test
    void 不同指纹的序号互不干扰() {
        LlmRequest a = request("sys-A");
        LlmRequest b = request("sys-B");
        var rec = gateway("record");
        responses = new String[]{"A1", "B1", "A2"};
        assertEquals("A1", rec.complete(a).getContent());
        assertEquals("B1", rec.complete(b).getContent());
        assertEquals("A2", rec.complete(a).getContent()); // A 的第 2 次

        var rep = gateway("replay");
        assertEquals("A1", rep.complete(a).getContent());
        assertEquals("B1", rep.complete(b).getContent());
        assertEquals("A2", rep.complete(a).getContent());
    }

    @Test
    void 兼容旧无后缀fixture_首次调用可命中() throws Exception {
        LlmRequest req = request("sys-legacy");
        var rec = gateway("record");
        responses = new String[]{"legacy"};
        rec.complete(req);
        // 把录制的 -1 文件改名为旧格式（无序号后缀）
        try (Stream<Path> files = Files.list(fixtureDir)) {
            Path first = files.findFirst().orElseThrow();
            Files.move(first, fixtureDir.resolve(
                    first.getFileName().toString().replaceAll("-\\d+\\.json$", ".json")));
        }

        var rep = gateway("replay");
        assertEquals("legacy", rep.complete(req).getContent(),
                "旧格式 fixture 应在首次调用时兼容命中");
    }
}
