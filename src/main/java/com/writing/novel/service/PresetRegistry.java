package com.writing.novel.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.writing.novel.domain.WritingPreset;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/**
 * 预设注册中心——加载内置预设 + 管理自定义预设。
 *
 * 内置预设从 classpath:presets/*.json 加载（只读）。
 * p17-05: 自定义预设持久化到 custom_presets 表（跨项目共享，重启可恢复）。
 */
@Service
public class PresetRegistry {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Map<String, WritingPreset> presets = new LinkedHashMap<>();
    private final com.writing.novel.spi.NovelRepository repository;

    public PresetRegistry() {
        this(null);
    }

    /** p17-05: 带持久层的构造器（Spring 装配用；repository 可为 null=纯内存模式） */
    @org.springframework.beans.factory.annotation.Autowired
    public PresetRegistry(com.writing.novel.spi.NovelRepository repository) {
        this.repository = repository;
        loadBuiltinPresets();
        loadCustomPresets();
    }

    private void loadBuiltinPresets() {
        try {
            var resolver = new PathMatchingResourcePatternResolver();
            var resources = resolver.getResources("classpath:presets/*.json");
            for (var res : resources) {
                try (InputStream is = res.getInputStream()) {
                    Map<String, Object> map = MAPPER.readValue(is, new TypeReference<Map<String, Object>>() {});
                    WritingPreset preset = WritingPreset.fromMap(map);
                    preset.setBuiltin(true);
                    presets.put(preset.getName(), preset);
                }
            }
        } catch (IOException e) {
            // 预设加载失败不阻断启动
        }
    }

    /** p17-05: 从持久层恢复自定义预设（内置同名优先，自定义不覆盖内置） */
    private void loadCustomPresets() {
        if (repository == null) return;
        try {
            repository.findCustomPresets().forEach((name, json) -> {
                try {
                    Map<String, Object> map = MAPPER.readValue(json,
                            new TypeReference<Map<String, Object>>() {});
                    WritingPreset preset = WritingPreset.fromMap(map);
                    preset.setBuiltin(false);
                    presets.putIfAbsent(name, preset); // 同名时内置优先
                } catch (Exception ignored) { /* 单条损坏不阻断启动 */ }
            });
        } catch (Exception ignored) { /* 持久层不可用不阻断启动 */ }
    }

    /** 获取所有预设 */
    public List<WritingPreset> listPresets() {
        return new ArrayList<>(presets.values());
    }

    /** 按名称获取预设 */
    public Optional<WritingPreset> getPreset(String name) {
        return Optional.ofNullable(presets.get(name));
    }

    /** 注册自定义预设（p17-05: 落库持久化） */
    public void registerPreset(WritingPreset preset) {
        preset.setBuiltin(false);
        presets.put(preset.getName(), preset);
        if (repository != null) {
            try {
                repository.saveCustomPreset(preset.getName(), MAPPER.writeValueAsString(preset.toMap()));
            } catch (Exception e) {
                // 持久化失败不影响内存注册
            }
        }
    }

    /** 从当前项目配置生成自定义预设 */
    public WritingPreset createPresetFromConfig(String name, String description,
                                                 Map<String, Object> preferences,
                                                 String genreHint,
                                                 String styleTone, String styleVoice, String styleDosAndDonts) {
        WritingPreset preset = new WritingPreset();
        preset.setName(name);
        preset.setDescription(description);
        preset.setPreferences(preferences != null ? new HashMap<>(preferences) : new HashMap<>());
        if (genreHint != null) {
            try { preset.setGenreHint(com.writing.novel.domain.GenreProfile.valueOf(genreHint)); }
            catch (Exception ignored) {}
        }
        preset.setStyleTone(styleTone);
        preset.setStyleVoice(styleVoice);
        preset.setStyleDosAndDonts(styleDosAndDonts);
        registerPreset(preset);
        return preset;
    }
}
