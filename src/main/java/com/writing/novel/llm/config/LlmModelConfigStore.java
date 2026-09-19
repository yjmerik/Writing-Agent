package com.writing.novel.llm.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.llm.LlmTaskType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * p43-01: 模型配置页面的持久化与运行时合并层。
 * <p>页面改动写入 {@code data/config/llm-models.json}（覆盖层），启动时叠加到 LlmProperties
 * 之上（yml 为只读基线）；删除覆盖即回退基线值。DefaultLlmGateway 每次请求动态读取
 * LlmProperties，因此 store 更新后立即生效、无需重启。
 * <p>scope 为 {@code "DEFAULT"} 或 {@link LlmTaskType} 名称。空路径（如测试置空
 * {@code llm.model-config-file}）时禁用文件 I/O，行为与现状一致。
 */
@Component
public class LlmModelConfigStore {

    public static final String DEFAULT_SCOPE = "DEFAULT";
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final LlmProperties properties;
    private final Path file;
    private final boolean ioEnabled;
    /** yml 基线快照（构造时、叠加覆盖层之前深拷贝），删除覆盖时恢复用 */
    private final Map<String, ModelConfig> baseline = new LinkedHashMap<>();
    /** 覆盖层内容（scope → 完整合并后的配置），即 JSON 文件中的 entries */
    private final Map<String, ModelConfig> overrides = new LinkedHashMap<>();

    public LlmModelConfigStore(LlmProperties properties,
                               @Value("${llm.model-config-file:./data/config/llm-models.json}") String filePath) {
        this.properties = properties;
        this.ioEnabled = filePath != null && !filePath.isBlank();
        this.file = ioEnabled ? Path.of(filePath) : null;
        baseline.put(DEFAULT_SCOPE, copy(properties.getDefaultModel()));
        properties.getModels().forEach((scope, cfg) -> baseline.put(scope, copy(cfg)));
        if (ioEnabled) {
            loadOverrides();
            overrides.forEach(this::applyToProperties);
        }
    }

    /** scope 合法性：DEFAULT 或任务类型名 */
    public static boolean isValidScope(String scope) {
        if (DEFAULT_SCOPE.equals(scope)) return true;
        try {
            LlmTaskType.valueOf(scope);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** 当前生效配置——任务类型 scope 未显式配置时回退 defaultModel（与 forTaskType 一致） */
    public synchronized ModelConfig effectiveForScope(String scope) {
        if (DEFAULT_SCOPE.equals(scope)) {
            return copy(properties.getDefaultModel() == null ? new ModelConfig() : properties.getDefaultModel());
        }
        ModelConfig cfg = properties.getModels().get(scope);
        return copy(cfg != null ? cfg : properties.getDefaultModel());
    }

    /** 是否存在页面配置的覆盖（可删除） */
    public synchronized boolean hasOverride(String scope) {
        return overrides.containsKey(scope);
    }

    /** yml 基线是否显式配置了该 scope（展示层区分 YML / FALLBACK 来源） */
    public synchronized boolean hasBaseline(String scope) {
        return baseline.containsKey(scope);
    }

    /** 保存覆盖：立即生效 + 落盘。cfg 必须是调用方合并完成的完整配置。 */
    public synchronized void save(String scope, ModelConfig cfg) {
        if (!isValidScope(scope)) throw new IllegalArgumentException("未知的配置范围: " + scope);
        overrides.put(scope, copy(cfg));
        applyToProperties(scope, cfg);
        persist();
    }

    /** 删除覆盖：运行时回退 yml 基线（基线无此 scope 则移除，任务恢复跟随默认） */
    public synchronized void delete(String scope) {
        if (!isValidScope(scope)) throw new IllegalArgumentException("未知的配置范围: " + scope);
        if (!overrides.containsKey(scope)) {
            throw new IllegalStateException("该范围没有页面配置，无法删除: " + scope);
        }
        overrides.remove(scope);
        ModelConfig base = baseline.get(scope);
        if (base != null) {
            applyToProperties(scope, base);
        } else if (DEFAULT_SCOPE.equals(scope)) {
            properties.setDefaultModel(new ModelConfig());
        } else {
            properties.getModels().remove(scope);
        }
        persist();
    }

    /** 供展示层读取覆盖层副本 */
    public synchronized Map<String, ModelConfig> overridesSnapshot() {
        Map<String, ModelConfig> snapshot = new LinkedHashMap<>();
        overrides.forEach((scope, cfg) -> snapshot.put(scope, copy(cfg)));
        return snapshot;
    }

    private void applyToProperties(String scope, ModelConfig cfg) {
        if (DEFAULT_SCOPE.equals(scope)) properties.setDefaultModel(cfg);
        else properties.getModels().put(scope, cfg);
    }

    private void loadOverrides() {
        if (!Files.exists(file)) return;
        try {
            var root = MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
            var entries = root.path("entries");
            if (!entries.isObject()) return;
            entries.properties().forEach(entry -> {
                if (!isValidScope(entry.getKey())) {
                    throw new IllegalStateException("llm-models.json 含未知配置范围: " + entry.getKey());
                }
                try {
                    overrides.put(entry.getKey(), MAPPER.treeToValue(entry.getValue(), ModelConfig.class));
                } catch (IOException e) {
                    throw new IllegalStateException("llm-models.json 条目解析失败: " + entry.getKey(), e);
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("读取 llm-models.json 失败: " + file, e);
        }
    }

    private void persist() {
        if (!ioEnabled) return;
        try {
            Files.createDirectories(file.getParent() != null ? file.getParent() : file);
            var root = MAPPER.createObjectNode();
            root.put("version", 1);
            var entries = root.putObject("entries");
            overrides.forEach(entries::putPOJO);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root),
                    StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("写入 llm-models.json 失败: " + file, e);
        }
    }

    /** 深拷贝（页面保存合并与基线恢复用） */
    public static ModelConfig copy(ModelConfig src) {
        if (src == null) return null;
        ModelConfig c = new ModelConfig();
        c.setBaseUrl(src.getBaseUrl());
        c.setApiKey(src.getApiKey());
        c.setModel(src.getModel());
        c.setProtocol(src.getProtocol());
        c.setTemperature(src.getTemperature());
        c.setMaxTokens(src.getMaxTokens());
        c.setStreaming(src.isStreaming());
        c.setContextWindow(src.getContextWindow());
        c.setStyleGuide(src.getStyleGuide());
        c.setThinkingEnabled(src.isThinkingEnabled());
        c.setInputPriceYuanPerKTokens(src.getInputPriceYuanPerKTokens());
        c.setOutputPriceYuanPerKTokens(src.getOutputPriceYuanPerKTokens());
        c.setRequestTimeoutSeconds(src.getRequestTimeoutSeconds());
        c.setSingleAttempt(src.isSingleAttempt());
        return c;
    }
}
