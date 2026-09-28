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

    /** NCS-108: yml 路由基线快照（providers 形态删除覆盖时恢复原路由） */
    private final Map<String, java.util.List<String>> baselineRoutes = new LinkedHashMap<>();
    private final java.util.List<String> baselineDefaultProviders = new java.util.ArrayList<>();
    /** legacy 面快照：构造时 models 中显式存在的 scope（删除覆盖时按来源恢复） */
    private final java.util.Set<String> baselineModelScopes = new java.util.LinkedHashSet<>();

    public LlmModelConfigStore(LlmProperties properties,
                               @Value("${llm.model-config-file:./data/config/llm-models.json}") String filePath) {
        this.properties = properties;
        this.ioEnabled = filePath != null && !filePath.isBlank();
        this.file = ioEnabled ? Path.of(filePath) : null;
        // NCS-108: 基线按「当前生效配置」快照——providers/routing 与 legacy 两种形态一致
        baseline.put(DEFAULT_SCOPE, copy(effectiveDefault()));
        for (LlmTaskType task : LlmTaskType.values()) {
            baseline.put(task.name(), copy(properties.forTaskType(task.name())));
        }
        baselineRoutes.putAll(properties.getRouting().getTasks());
        baselineDefaultProviders.addAll(properties.getRouting().getDefaultProviders());
        baselineModelScopes.addAll(properties.getModels().keySet());
        if (ioEnabled) {
            loadOverrides();
            overrides.forEach(this::applyToProperties);
        }
    }

    /** 默认生效配置：providers 形态取 defaultProviders 首位；legacy 取 defaultModel */
    private ModelConfig effectiveDefault() {
        var defaults = properties.getRouting().getDefaultProviders();
        if (!defaults.isEmpty()) {
            ModelConfig cfg = properties.getProviders().get(defaults.get(0));
            if (cfg != null) return cfg;
        }
        return properties.getDefaultModel() == null ? new ModelConfig() : properties.getDefaultModel();
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

    /** 当前生效配置——providers/routing 与 legacy 两形态统一经 forTaskType 解析 */
    public synchronized ModelConfig effectiveForScope(String scope) {
        if (DEFAULT_SCOPE.equals(scope)) return copy(effectiveDefault());
        return copy(properties.forTaskType(scope));
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
        // NCS-108: 恢复 yml 原路由（providers 形态恢复任务/默认路由）；legacy 形态同时恢复 models/defaultModel 面
        if (DEFAULT_SCOPE.equals(scope)) {
            properties.getRouting().setDefaultProviders(new java.util.ArrayList<>(baselineDefaultProviders));
            properties.setDefaultModel(copy(baseline.get(DEFAULT_SCOPE)));
        } else {
            java.util.List<String> route = baselineRoutes.get(scope);
            if (route != null) {
                properties.getRouting().getTasks().put(scope, new java.util.ArrayList<>(route));
            } else {
                properties.getRouting().getTasks().remove(scope);
            }
            if (baselineModelScopes.contains(scope)) {
                properties.getModels().put(scope, copy(baseline.get(scope)));   // legacy 面恢复
            } else {
                properties.getModels().remove(scope);
            }
        }
        persist();
    }

    /** 供展示层读取覆盖层副本 */
    public synchronized Map<String, ModelConfig> overridesSnapshot() {
        Map<String, ModelConfig> snapshot = new LinkedHashMap<>();
        overrides.forEach((scope, cfg) -> snapshot.put(scope, copy(cfg)));
        return snapshot;
    }

    /**
     * NCS-108: 覆盖写入 providers/routing 层（providers 形态下写 models 不生效——
     * forTaskType 优先路由）；legacy 形态同样经路由生效，行为统一。
     */
    private void applyToProperties(String scope, ModelConfig cfg) {
        if (DEFAULT_SCOPE.equals(scope)) {
            properties.setDefaultModel(cfg);   // legacy/defaultModel 面始终镜像
            // providers 形态才接管默认路由——legacy 下设 defaultProviders 会使未显式配置任务
            // 绕过 models 回退（回归实测），保持 legacy 语义不动路由
            if (!baselineDefaultProviders.isEmpty() || !properties.getRouting().getTasks().isEmpty()) {
                String providerId = "ui-" + scope.toLowerCase();
                properties.getProviders().put(providerId, cfg);
                properties.getRouting().setDefaultProviders(java.util.List.of(providerId));
            }
        } else {
            String providerId = "ui-" + scope.toLowerCase();
            properties.getProviders().put(providerId, cfg);
            properties.getRouting().getTasks().put(scope, java.util.List.of(providerId));
            properties.getModels().put(scope, cfg);   // legacy 面镜像
        }
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
