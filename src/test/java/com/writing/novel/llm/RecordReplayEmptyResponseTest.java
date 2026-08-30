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
    void record同指纹多次调用序号共存_各自保留() throws Exception {
        LlmRequest req = request();
        var g = gateway(new String[]{"{\"issues\":[\"HIGH\"]}", ""}, "record");
        assertEquals("{\"issues\":[\"HIGH\"]}", g.complete(req).getContent());
        assertEquals("", g.complete(req).getContent()); // 同指纹第二次（瞬时空响应）

        // p16-replay 序号化：两次调用各自成文件，首次非空不再被覆盖丢失
        try (var files = Files.list(tempDir.resolve("fixtures"))) {
            var names = files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".json")).sorted().toList();
            assertEquals(2, names.size(), "两次调用各自成文件: " + names);
            assertTrue(Files.readString(tempDir.resolve("fixtures").resolve(names.get(0))).contains("HIGH"),
                    "-1 应为首次非空响应");
        }
    }

    @Test
    void replay回放保留的非空fixture() throws Exception {
        // 固定指纹：record 与 replay 用同一 sys/user（request() 用 nanoTime，两次调用不同指纹）
        String fixedSys = "sys-fixed-" + System.nanoTime();
        LlmRequest req = LlmRequest.builder()
                .taskType(LlmTaskType.GENERAL)
                .systemPrompt(fixedSys).userMessage("u").build();
        var rec = gateway(new String[]{"{\"issues\":[\"HIGH\"]}", ""}, "record");
        rec.complete(req); // 第一次：非空 → -1
        rec.complete(req); // 第二次：同指纹瞬时空 → -2
        var g = gateway(new String[]{""}, "replay");
        assertEquals("{\"issues\":[\"HIGH\"]}", g.complete(req).getContent(),
                "replay 第1次应回放非空响应（与 record 首次副作用一致）");
        assertEquals("", g.complete(req).getContent(), "replay 第2次回放空响应（时序一致）");
    }
}
