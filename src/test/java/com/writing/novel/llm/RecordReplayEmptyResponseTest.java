package com.writing.novel.llm;

import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.llm.gateway.RecordReplayLlmGateway;
import com.writing.novel.llm.config.LlmProperties;
import com.writing.novel.llm.config.ModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * p16-replay-fix: record 模式同指纹重复调用时，空响应不得退化覆盖已录制的非空 fixture。
 * e2e 实证（dsv4-20260829b replay）：第2章 guard 首次返回 HIGH JSON（写库），同指纹二次调用
 * 恰逢瞬时空响应覆盖 fixture——replay 回放空导致后处理产出发散、第3章 prompt 指纹 miss。
 */
class RecordReplayEmptyResponseTest {

    @TempDir
    Path tempDir;
    private LlmGateway delegate;
    private int calls;
    private String[] responses;
    private RecordReplayLlmGateway gateway;

    private Path fixtureDir;

    @BeforeEach
    void setUp() throws Exception {
        fixtureDir = tempDir.resolve("fixtures");
    }

    private LlmGateway gateway(String[] responses, String mode) {
        this.responses = responses;
        this.calls = 0;
        delegate = new LlmGateway() {
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

    private LlmRequest request() {
        return LlmRequest.builder()
                .taskType(LlmTaskType.GENERAL)
                .systemPrompt("sys-" + System.nanoTime()) // 每测试独立指纹
                .userMessage("u").build();
    }

    @Test
    void record同指纹二次空响应不覆盖非空fixture() throws Exception {
        LlmRequest req = request();
        var g = gateway(new String[]{"{\"issues\":[\"HIGH\"]}", ""}, "record");
        LlmResponse first = g.complete(req);
        assertEquals("{\"issues\":[\"HIGH\"]}", first.getContent());
        LlmResponse second = g.complete(req); // 同指纹，瞬时空响应
        assertEquals("", second.getContent(), "内存返回值保持真实（空）");

        // fixture 必须保留首次非空内容
        var files = Files.list(tempDir.resolve("fixtures"))
                .filter(p -> p.getFileName().toString().endsWith(".json")).toList();
        assertEquals(1, files.size());
        String json = Files.readString(files.get(0));
        assertTrue(json.contains("HIGH"), "fixture 应保留首次非空响应，实际: " + json);
    }

    @Test
    void replay回放保留的非空fixture() throws Exception {
        // 固定指纹：record 与 replay 用同一 sys/user（request() 用 nanoTime，两次调用不同指纹）
        String fixedSys = "sys-fixed-" + System.nanoTime();
        LlmRequest req = LlmRequest.builder()
                .taskType(LlmTaskType.GENERAL)
                .systemPrompt(fixedSys).userMessage("u").build();
        gateway(new String[]{"{\"issues\":[\"HIGH\"]}", ""}, "record");
        var recordGw = gateway(new String[]{"{\"issues\":[\"HIGH\"]}", ""}, "record");
        recordGw.complete(req); // 第一次：非空 → 落盘
        recordGw.complete(req); // 第二次：同指纹瞬时空 → 被拒绝覆盖
        var g = gateway(new String[]{""}, "replay");
        LlmResponse replayed = g.complete(req);
        assertEquals("{\"issues\":[\"HIGH\"]}", replayed.getContent(),
                "replay 应拿到被保留的非空响应（与 record 首次副作用一致）");
    }
}
