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
 * RECORD 模式：透传真实调用，成功响应以 JSON fixture 落盘（按请求指纹命名）。
 * REPLAY 模式：按请求指纹读 fixture，命中返回录制内容（零成本、零随机），
 *              缺失则 fail-fast 抛异常（提示先用 record 模式补录）。
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

    public RecordReplayLlmGateway(LlmGateway delegate, Mode mode, Path fixtureDir) {
        this.delegate = delegate;
        this.mode = mode;
        this.fixtureDir = fixtureDir;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        Path fixture = fixturePath(request);
        if (mode == Mode.REPLAY) {
            return loadFixture(fixture, request);
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
        Path fixture = fixturePath(request);
        if (mode == Mode.REPLAY) {
            LlmResponse replayed = loadFixture(fixture, request);
            handler.onToken(replayed.getContent());
            handler.onComplete();
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
            public void onError(Throwable error) {
                handler.onError(error);
            }
        });
    }

    // ---- fixture 读写 ----

    private LlmResponse loadFixture(Path fixture, LlmRequest request) {
        if (!Files.exists(fixture)) {
            throw new IllegalStateException(
                    "回放 fixture 缺失: " + fixture + "（taskType=" + request.getTaskType()
                            + "）。请先以 llm.replay.mode=record 运行同一流程补录。");
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
                    node.path("completionTokens").asInt(0));
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
        try {
            Files.createDirectories(fixtureDir);
            ObjectNode node = MAPPER.createObjectNode();
            node.put("taskType", request.getTaskType().name());
            node.put("recordedAt", Instant.now().toString());
            node.put("systemPromptPreview", preview(request.getSystemPrompt()));
            node.put("userMessagePreview", preview(request.getUserMessage()));
            node.put("content", response.getContent());
            node.put("promptTokens", response.getPromptTokens());
            node.put("completionTokens", response.getCompletionTokens());
            Files.writeString(fixture, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node),
                    StandardCharsets.UTF_8);
            log.info("已录制 LLM fixture: {}", fixture.getFileName());
        } catch (IOException e) {
            // 录制失败不影响业务返回，仅告警
            log.warn("LLM fixture 录制失败: {}", e.getMessage());
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

    private Path fixturePath(LlmRequest request) {
        return fixtureDir.resolve(request.getTaskType().name() + "-" + fingerprint(request) + ".json");
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
