package com.writing.novel.web;

import com.writing.novel.llm.LlmTaskType;
import com.writing.novel.llm.config.LlmProperties;
import com.writing.novel.llm.config.LlmProtocol;
import com.writing.novel.llm.config.ModelConfig;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 模型库 + 任务路由 REST API。
 *
 * <p>解决痛点：此前每个任务类型需独立填写完整连接信息（baseURL/apiKey/模型名…），
 * 同一模型配 N 遍。现在：
 * <ul>
 *   <li><b>模型库</b>（providers）：保存命名模型（如 "qwen3.8-flash"、"deepseek-v3"），
 *       连接参数只需配置一次</li>
 *   <li><b>任务路由</b>（routing）：每个任务类型从模型库下拉选择，保存即生效</li>
 * </ul>
 * api_key 只返回掩码；PUT 空 api_key 保留原值。删除被引用的模型前需先解除路由。
 */
@RestController
@RequestMapping("/api/llm/library")
public class ModelLibraryController {

    private final LlmProperties properties;
    private final com.writing.novel.llm.client.LlmClient client;

    public ModelLibraryController(LlmProperties properties, com.writing.novel.llm.client.LlmClient client) {
        this.properties = properties;
        this.client = client;
    }

    // === DTO ===

    public record LibraryModel(String name, String label, String protocol, String baseUrl,
                               String model, String apiKeyMasked, boolean hasApiKey,
                               double temperature, int maxTokens, int contextWindow,
                               boolean streaming, int requestTimeoutSeconds,
                               List<String> usedByTasks) {}

    public record LibraryResponse(List<LibraryModel> models, List<String> taskTypes,
                                  Map<String, String> taskLabels, Map<String, String> routing,
                                  String defaultProvider) {}

    public record SaveModelRequest(String label, String protocol, String baseUrl, String apiKey,
                                   String model, Double temperature, Integer maxTokens,
                                   Integer contextWindow, Boolean streaming,
                                   Integer requestTimeoutSeconds) {}

    public record RouteRequest(String providerName) {}

    // === 模型库 CRUD ===

    @GetMapping
    public LibraryResponse list() {
        var providers = properties.getProviders();
        var routing = properties.getRouting().getTasks();
        var defaultProviders = properties.getRouting().getDefaultProviders();
        String defaultProvider = defaultProviders.isEmpty() ? null : defaultProviders.get(0);

        // 反向索引：provider → 引用它的任务列表
        Map<String, List<String>> usedBy = new HashMap<>();
        routing.forEach((task, ids) -> {
            if (!ids.isEmpty()) {
                usedBy.computeIfAbsent(ids.get(0), k -> new ArrayList<>()).add(task);
            }
        });

        List<LibraryModel> models = new ArrayList<>();
        providers.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> models.add(toView(e.getKey(), e.getValue(),
                        usedBy.getOrDefault(e.getKey(), List.of()))));

        // 任务类型列表（固定顺序 + 中文标签）
        LinkedHashMap<String, String> labels = new LinkedHashMap<>();
        labels.put("WORLDBUILDING", "世界观构建");
        labels.put("CHARACTER_DESIGN", "人物设计");
        labels.put("PLOTTING", "情节规划");
        labels.put("CHAPTER_WRITING", "章节写作");
        labels.put("CONTINUITY_CHECK", "连续性检查");
        labels.put("STYLE_GUARDIAN", "风格守护");
        labels.put("MEMORY_SUMMARY", "记忆摘要");
        labels.put("GENERAL", "通用任务");
        labels.put("EXTRACTION", "提取任务");

        // 当前路由映射（task → provider name）
        Map<String, String> currentRouting = new LinkedHashMap<>();
        labels.forEach((task, label) -> {
            var ids = routing.get(task);
            currentRouting.put(task, ids != null && !ids.isEmpty() ? ids.get(0) : null);
        });

        return new LibraryResponse(models, new ArrayList<>(labels.keySet()), labels,
                currentRouting, defaultProvider);
    }

    /**
     * 新增/更新模型（PUT 空 apiKey 保留原值）。
     * NCS-162（落 NCS-142「失败即时不入库」）：保存前连通性探测，失败返回 400
     * 结构化错误且配置不入库；?force=true 离线逃生门跳过探测。
     */
    @PutMapping("/{name}")
    public org.springframework.http.ResponseEntity<Object> save(@PathVariable String name,
            @org.springframework.web.bind.annotation.RequestParam(required = false, defaultValue = "false") boolean force,
            @RequestBody SaveModelRequest body) {
        requireValidName(name);
        ModelConfig existing = properties.getProviders().get(name);
        ModelConfig cfg = existing != null ? copyConfig(existing) : new ModelConfig();

        if (body.protocol() != null && !body.protocol().isBlank()) {
            cfg.setProtocol(parseProtocol(body.protocol()));
        }
        if (body.baseUrl() != null) cfg.setBaseUrl(body.baseUrl().trim());
        if (body.model() != null) cfg.setModel(body.model().trim());
        if (body.apiKey() != null && !body.apiKey().isBlank()) {
            cfg.setApiKey(body.apiKey().trim());
        }
        if (body.temperature() != null) cfg.setTemperature(body.temperature());
        if (body.maxTokens() != null) cfg.setMaxTokens(body.maxTokens());
        if (body.contextWindow() != null) cfg.setContextWindow(body.contextWindow());
        if (body.streaming() != null) cfg.setStreaming(body.streaming());
        if (body.requestTimeoutSeconds() != null) cfg.setRequestTimeoutSeconds(body.requestTimeoutSeconds());

        validate(cfg);
        if (!force) {
            Map<String, Object> probe = com.writing.novel.llm.LlmProbe.run(client, copyConfig(cfg));
            if (!Boolean.TRUE.equals(probe.get("ok"))) {
                return org.springframework.http.ResponseEntity.status(
                        org.springframework.http.HttpStatus.BAD_REQUEST).body(Map.of(
                        "saved", false,
                        "code", "MODEL_PROBE_FAILED",
                        "error", "连通性探测未通过，配置未入库（离线场景可在保存时选择强制保存）",
                        "probe", probe));
            }
        }
        properties.getProviders().put(name, cfg);
        return org.springframework.http.ResponseEntity.ok(toView(name, cfg, List.of()));
    }

    /**
     * NCS-142：连通性自检（已存条目）——以库中该模型的当前配置发一次微型补全。
     * 空 apiKey 保留原值；探测强制单次尝试 + 短超时 + 受限 maxTokens。
     */
    @org.springframework.web.bind.annotation.PostMapping("/{name}/test")
    public Map<String, Object> testSaved(@PathVariable String name) {
        ModelConfig cfg = properties.getProviders().get(name);
        if (cfg == null) {
            throw new IllegalArgumentException("模型库中不存在: " + name);
        }
        return com.writing.novel.llm.LlmProbe.run(client, copyConfig(cfg));
    }

    /** NCS-142：连通性自检（表单候选）——保存前以表单值探测，坏端点/坏 key 当场可见 */
    @org.springframework.web.bind.annotation.PostMapping("/test")
    public Map<String, Object> testCandidate(@RequestBody SaveModelRequest body) {
        ModelConfig candidate = new ModelConfig();
        if (body.protocol() != null && !body.protocol().isBlank()) {
            candidate.setProtocol(parseProtocol(body.protocol()));
        }
        candidate.setBaseUrl(body.baseUrl() == null ? "" : body.baseUrl().trim());
        candidate.setModel(body.model() == null ? "" : body.model().trim());
        if (body.apiKey() != null) candidate.setApiKey(body.apiKey().trim());
        candidate.setContextWindow(body.contextWindow() == null ? 0 : body.contextWindow());
        if (candidate.getBaseUrl().isBlank() || candidate.getModel().isBlank()) {
            throw new IllegalArgumentException("探测候选必须提供 baseUrl 与 model");
        }
        return com.writing.novel.llm.LlmProbe.run(client, candidate);
    }

    /** 删除模型（被路由引用时拒绝） */
    @DeleteMapping("/{name}")
    public Map<String, Object> delete(@PathVariable String name) {        var providers = properties.getProviders();
        if (!providers.containsKey(name)) {
            throw new IllegalArgumentException("模型不存在: " + name);
        }
        // 检查是否有任务路由引用
        var routing = properties.getRouting().getTasks();
        List<String> referenced = new ArrayList<>();
        routing.forEach((task, ids) -> {
            if (ids.contains(name)) referenced.add(task);
        });
        if (!referenced.isEmpty()) {
            throw new IllegalArgumentException("模型被以下任务路由引用，请先解除: "
                    + String.join(", ", referenced));
        }
        // 检查默认路由引用
        if (properties.getRouting().getDefaultProviders().contains(name)) {
            throw new IllegalArgumentException("模型被默认路由引用，请先修改默认路由");
        }
        providers.remove(name);
        return Map.of("deleted", name);
    }

    // === 任务路由 ===

    /** 设置任务类型使用哪个模型库条目 */
    @PutMapping("/routing/{taskType}")
    public Map<String, Object> route(@PathVariable String taskType, @RequestBody RouteRequest body) {
        String providerName = body.providerName();
        if (providerName == null || providerName.isBlank()) {
            // 清除路由 → 回退默认
            properties.getRouting().getTasks().remove(taskType);
            return Map.of("taskType", taskType, "providerName", "", "cleared", true);
        }
        providerName = providerName.trim();
        if (!properties.getProviders().containsKey(providerName)) {
            throw new IllegalArgumentException("模型库中不存在: " + providerName);
        }
        properties.getRouting().getTasks().put(taskType, new ArrayList<>(List.of(providerName)));
        return Map.of("taskType", taskType, "providerName", providerName);
    }

    /** 设置默认模型（未路由任务的 fallback） */
    @PutMapping("/routing/default")
    public Map<String, Object> routeDefault(@RequestBody RouteRequest body) {
        String providerName = body.providerName();
        if (providerName == null || providerName.isBlank()) {
            properties.getRouting().getDefaultProviders().clear();
            return Map.of("default", "", "cleared", true);
        }
        if (!properties.getProviders().containsKey(providerName.trim())) {
            throw new IllegalArgumentException("模型库中不存在: " + providerName);
        }
        properties.getRouting().getDefaultProviders().clear();
        properties.getRouting().getDefaultProviders().add(providerName.trim());
        return Map.of("default", providerName.trim());
    }

    // === helpers ===

    private static LibraryModel toView(String name, ModelConfig cfg, List<String> usedBy) {
        String masked = ModelConfigController.mask(cfg.getApiKey());
        return new LibraryModel(name, name, // label = name（可后续扩展自定义显示名）
                cfg.getProtocol() == null ? LlmProtocol.OPENAI_CHAT_COMPLETIONS.name() : cfg.getProtocol().name(),
                cfg.getBaseUrl(), cfg.getModel(), masked, !masked.isEmpty(),
                cfg.getTemperature(), cfg.getMaxTokens(), cfg.getContextWindow(),
                cfg.isStreaming(), cfg.getRequestTimeoutSeconds(), usedBy);
    }

    private static ModelConfig copyConfig(ModelConfig src) {
        ModelConfig c = new ModelConfig();
        c.setBaseUrl(src.getBaseUrl());
        c.setApiKey(src.getApiKey());
        c.setModel(src.getModel());
        c.setProtocol(src.getProtocol());
        c.setTemperature(src.getTemperature());
        c.setMaxTokens(src.getMaxTokens());
        c.setStreaming(src.isStreaming());
        c.setContextWindow(src.getContextWindow());
        c.setRequestTimeoutSeconds(src.getRequestTimeoutSeconds());
        return c;
    }

    private static void requireValidName(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_\\-.]{1,64}")) {
            throw new IllegalArgumentException("模型名只能包含字母/数字/_/-/.，长度 1~64: " + name);
        }
    }

    private static LlmProtocol parseProtocol(String value) {
        try {
            return LlmProtocol.valueOf(value.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知协议: " + value);
        }
    }

    private static void validate(ModelConfig cfg) {
        try {
            var uri = java.net.URI.create(cfg.getBaseUrl() == null ? "" : cfg.getBaseUrl());
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    || uri.getHost() == null) throw new IllegalArgumentException();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("base_url 必须是合法的 http(s) 地址");
        }
        if (cfg.getModel() == null || cfg.getModel().isBlank()) {
            throw new IllegalArgumentException("模型 ID 不能为空");
        }
        if (!Double.isFinite(cfg.getTemperature()) || cfg.getTemperature() < 0 || cfg.getTemperature() > 2) {
            throw new IllegalArgumentException("temperature 必须在 0~2 之间");
        }
        if (cfg.getMaxTokens() <= 0) throw new IllegalArgumentException("max_tokens 必须大于 0");
        if (cfg.getContextWindow() > 0 && cfg.getContextWindow() <= cfg.getMaxTokens()) {
            throw new IllegalArgumentException("context_window 必须大于 max_tokens");
        }
        if (cfg.getRequestTimeoutSeconds() <= 0 || cfg.getRequestTimeoutSeconds() > 86400) {
            throw new IllegalArgumentException("request_timeout_seconds 必须在 1~86400 之间");
        }
        if (!LlmProperties.isLocalBaseUrl(cfg.getBaseUrl())
                && (cfg.getApiKey() == null || cfg.getApiKey().isBlank())) {
            throw new IllegalArgumentException("远程端点必须配置 API Key");
        }
    }
}
