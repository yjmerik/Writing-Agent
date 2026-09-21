package com.writing.novel.llm.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.client.ChatMessage;
import com.writing.novel.llm.client.StreamHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.regex.Pattern;

/**
 * LLM 录制/回放网关装饰器 — 确定性 E2E 测试的基石。
 *
 * RECORD 模式：透传真实调用，成功响应以 JSON fixture 落盘。
 * REPLAY 模式：按指纹+调用序号读 fixture，命中返回录制内容（零成本、零随机），
 *              缺失则 fail-fast 抛异常（提示先用 record 模式补录）。
 *
 * p16-replay: fixture 文件名带调用序号（<taskType>-<指纹>-<N>.json）——同指纹的
 * 每次调用独立保留。背景：单文件按指纹命名时，同指纹第 N 次调用覆盖前次响应，
 * record 中「调用1 产出副作用（guard HIGH 写库）→ 调用2 响应不同」的场景，
 * replay 只能回放最后一次，前次决策输入丢失 → 后续章节 prompt 漂移。
 * 序号化后回放时序与 record 完全一致；分支分叉时第 N 次缺失的 fail-fast 可诊断。
 * 兼容：-1 缺失时回退旧的无后缀文件。
 *
     * 指纹 = SHA-256(taskType + systemPrompt + userMessage + history)，
     * 忽略配置 JSON 的 updatedAt 易变字段，以及 temperature/maxTokens 采样参数。
 *
 * 配置：llm.replay.mode = "off" | "record" | "replay"（application.yml，off 需加引号）
 *       llm.replay.dir  = fixture 目录（默认 data/fixtures/llm）
 */
public class RecordReplayLlmGateway implements LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(RecordReplayLlmGateway.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern UPDATED_AT_FIELD = Pattern.compile("(\"updatedAt\"\\s*:\\s*\")[^\"]+(\")");

    public enum Mode { RECORD, REPLAY }

    private final LlmGateway delegate;
    private final Mode mode;
    private final Path fixtureDir;
    /** p16-replay: 指纹 → 本次运行内已发生的调用次数（record 与 replay 各自计数，语义一致） */
    private final java.util.concurrent.ConcurrentHashMap<String, Integer> callCounts =
            new java.util.concurrent.ConcurrentHashMap<>();

    public RecordReplayLlmGateway(LlmGateway delegate, Mode mode, Path fixtureDir) {
        this.delegate = delegate;
        this.mode = mode;
        this.fixtureDir = fixtureDir;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        int n = callCounts.merge(fingerprint(request), 1, Integer::sum);
        Path fixture = fixturePath(request, n);
        if (mode == Mode.REPLAY) {
            return loadFixture(fixture, request, n);
        }
        try {
            LlmResponse response = delegate.complete(request);
            saveFixture(fixture, request, response);
            return response;
        } catch (RuntimeException e) {
            // mc-p3-01: 失败也录制——回放时复现同样的异常与重试路径，保证 record→replay 完全确定
            saveErrorFixture(fixture, request, e);
            throw e;
        }
    }

    @Override
    public void streamComplete(LlmRequest request, StreamHandler handler) {
        int n = callCounts.merge(fingerprint(request), 1, Integer::sum);
        Path fixture = fixturePath(request, n);
        if (mode == Mode.REPLAY) {
            LlmResponse replayed = loadFixture(fixture, request, n);
            handler.onToken(replayed.getContent());
            handler.onComplete(replayed);
            return;
        }
        // RECORD：包装 handler 累积完整内容，完成时落盘
        StringBuilder accumulated = new StringBuilder();
        delegate.streamComplete(request, new StreamHandler() {
            @Override
            public void onToken(String token) {
                accumulated.append(token);
                handler.onToken(token);
            }

            @Override
            public void onComplete() {
                saveFixture(fixture, request, new LlmResponse(accumulated.toString(), 0, 0));
                handler.onComplete();
            }

            @Override
            public void onComplete(LlmResponse response) {
                saveFixture(fixture, request, response);
                handler.onComplete(response);
            }

            @Override
            public void onError(Throwable error) {
                handler.onError(error);
            }
        });
    }

    // ---- fixture 读写 ----

    private LlmResponse loadFixture(Path fixture, LlmRequest request, int callIndex) {
        if (!Files.exists(fixture) && callIndex == 1) {
            // p16-replay: 兼容旧无后缀 fixture（手工目录/旧基线）
            Path legacy = fixtureDir.resolve(fixture.getFileName().toString().replaceAll("-\\d+\\.json$", ".json"));
            if (Files.exists(legacy)) fixture = legacy;
        }
        if (!Files.exists(fixture)) {
            dumpMissedRequest(fixture, request, callIndex);
            int recorded = callCounts.getOrDefault(fingerprint(request), 1) - 1;
            throw new IllegalStateException(
                    "回放 fixture 缺失（第 " + callIndex + " 次调用）: " + fixture.getFileName()
                            + "（taskType=" + request.getTaskType()
                            + "，该指纹 record 时仅 " + recorded + " 次）——分支分叉或 record 不完整，"
                            + "请先以 llm.replay.mode=record 运行同一流程补录。");
        }
        try {
            JsonNode node = MAPPER.readTree(Files.readString(fixture, StandardCharsets.UTF_8));
            // mc-p3-01: 录制时失败的请求——回放时复现同样的异常（触发相同的重试路径）
            if (node.has("error")) {
                String errorClass = node.path("error").asText();
                String message = node.path("errorMessage").asText("");
                log.info("回放录制失败: {} {}", errorClass, message);
                throw rethrow(errorClass, message);
            }
            return new LlmResponse(
                    node.path("content").asText(),
                    node.path("promptTokens").asInt(0),
                    node.path("completionTokens").asInt(0),
                    // NCS-065: 旧 fixture 无 finishReason 字段默认 stop
                    node.path("finishReason").asText("stop"),
                    node.hasNonNull("execution")
                            ? MAPPER.treeToValue(node.get("execution"), LlmResponse.Execution.class) : null);
        } catch (IOException e) {
            throw new IllegalStateException("回放 fixture 解析失败: " + fixture, e);
        }
    }

    /** 按录制类名重抛异常——ContentFilterException 等重试路径依赖具体类型 */
    private static RuntimeException rethrow(String className, String message) {
        try {
            Class<?> clazz = Class.forName(className);
            if (RuntimeException.class.isAssignableFrom(clazz)) {
                var ctor = clazz.getConstructor(String.class);
                return (RuntimeException) ctor.newInstance(message);
            }
        } catch (Exception ignored) { /* 回退到通用异常 */ }
        return new IllegalStateException(message);
    }

    private void saveFixture(Path fixture, LlmRequest request, LlmResponse response) {
        // p16-replay-fix: 空响应不得退化覆盖已录制的非空 fixture——record 轮同指纹二次调用
        // 恰逢瞬时空响应时，覆盖会让 replay 丢失首次（已产生副作用的）真实响应
        if (response.getContent() == null || response.getContent().isEmpty()) {
            if (Files.exists(fixture)) {
                try {
                    JsonNode prev = MAPPER.readTree(Files.readString(fixture));
                    if (!prev.path("content").asText("").isEmpty()) {
                        log.warn("录制到空响应但 fixture 已有非空内容，保留原 fixture: {}", fixture.getFileName());
                        return;
                    }
                } catch (IOException ignored) { }
            }
        }
        try {
            Files.createDirectories(fixtureDir);
            ObjectNode node = MAPPER.createObjectNode();
            node.put("taskType", request.getTaskType().name());
            node.put("recordedAt", Instant.now().toString());
            node.put("systemPrompt", nullSafe(request.getSystemPrompt()));
            node.put("userMessage", nullSafe(request.getUserMessage()));
            node.put("systemPromptPreview", preview(request.getSystemPrompt()));
            node.put("userMessagePreview", preview(request.getUserMessage()));
            node.put("content", response.getContent());
            node.put("promptTokens", response.getPromptTokens());
            node.put("completionTokens", response.getCompletionTokens());
            // NCS-065: 截断信号随 fixture 落盘，回放保留
            node.put("finishReason", response.getFinishReason());
            if (response.getExecution() != null) node.set("execution", MAPPER.valueToTree(response.getExecution()));
            Files.writeString(fixture, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node),
                    StandardCharsets.UTF_8);
            log.info("已录制 LLM fixture: {}", fixture.getFileName());
        } catch (IOException e) {
            // 录制失败不影响业务返回，仅告警
            log.warn("LLM fixture 录制失败: {}", e.getMessage());
        }
    }

    private void dumpMissedRequest(Path fixture, LlmRequest request, int callIndex) {
        try {
            Files.createDirectories(fixtureDir);
            String name = fixture.getFileName().toString().replaceFirst("\\.json$", "") + ".miss.json";
            ObjectNode node = MAPPER.createObjectNode();
            node.put("taskType", request.getTaskType().name());
            node.put("missedAt", Instant.now().toString());
            node.put("expectedFixture", fixture.getFileName().toString());
            node.put("systemPrompt", nullSafe(request.getSystemPrompt()));
            node.put("userMessage", nullSafe(request.getUserMessage()));
            Files.writeString(fixtureDir.resolve(name),
                    MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node),
                    StandardCharsets.UTF_8);
            log.warn("回放 miss 已转储完整请求: {}", name);
        } catch (Exception e) {
            log.warn("miss 转储失败: {}", e.getMessage());
        }
    }

    /** mc-p3-01: 录制失败的请求——含错误类名与消息，供回放复现 */
    private void saveErrorFixture(Path fixture, LlmRequest request, RuntimeException error) {
        try {
            Files.createDirectories(fixtureDir);
            ObjectNode node = MAPPER.createObjectNode();
            node.put("taskType", request.getTaskType().name());
            node.put("recordedAt", Instant.now().toString());
            node.put("error", error.getClass().getName());
            node.put("errorMessage", error.getMessage() == null ? "" : error.getMessage());
            Files.writeString(fixture, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node),
                    StandardCharsets.UTF_8);
            log.info("已录制 LLM 失败 fixture: {} ({})", fixture.getFileName(), error.getClass().getSimpleName());
        } catch (IOException e) {
            log.warn("LLM 失败 fixture 录制失败: {}", e.getMessage());
        }
    }

    private Path fixturePath(LlmRequest request, int callIndex) {
        return fixtureDir.resolve(request.getTaskType().name() + "-" + fingerprint(request) + "-" + callIndex + ".json");
    }

    private String fingerprint(LlmRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append(request.getTaskType().name()).append('\u0000');
        sb.append(normalizeVolatileMetadata(request.getSystemPrompt())).append('\u0000');
        sb.append(nullSafe(request.getUserMessage()));
        if (request.getHistory() != null) {
            for (ChatMessage m : request.getHistory()) {
                sb.append('\u0000').append(m.role()).append(':').append(normalizeVolatileMetadata(m.content()));
            }
        }
        return sha256Hex(sb.toString()).substring(0, 16);
    }

    private static String nullSafe(String s) { return s == null ? "" : s; }

    private static String normalizeVolatileMetadata(String text) {
        return UPDATED_AT_FIELD.matcher(nullSafe(text)).replaceAll("$1<volatile>$2");
    }

    private static String preview(String s) {
        if (s == null) return "";
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
