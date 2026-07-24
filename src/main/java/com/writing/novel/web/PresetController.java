package com.writing.novel.web;

import com.writing.novel.domain.WritingPreset;
import com.writing.novel.service.PresetRegistry;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 预设模板 REST API。
 * 支持列出/查看/创建预设。
 */
@RestController
@RequestMapping("/api/presets")
public class PresetController {

    private final PresetRegistry presetRegistry;

    public PresetController(PresetRegistry presetRegistry) {
        this.presetRegistry = presetRegistry;
    }

    /** 列出所有可用预设 */
    @GetMapping
    public List<Map<String, Object>> list() {
        return presetRegistry.listPresets().stream()
                .map(WritingPreset::toMap)
                .toList();
    }

    /** 查看预设详情 */
    @GetMapping("/{name}")
    public Map<String, Object> get(@PathVariable String name) {
        return presetRegistry.getPreset(name)
                .orElseThrow(() -> new IllegalArgumentException("预设不存在: " + name))
                .toMap();
    }

    /** 保存自定义预设（从当前项目配置打包） */
    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        String name = (String) body.get("name");
        String description = (String) body.getOrDefault("description", "");
        @SuppressWarnings("unchecked")
        Map<String, Object> preferences = (Map<String, Object>) body.get("preferences");
        String genreHint = (String) body.get("genreHint");
        String styleTone = (String) body.get("styleTone");
        String styleVoice = (String) body.get("styleVoice");
        String styleDosAndDonts = (String) body.get("styleDosAndDonts");

        WritingPreset preset = presetRegistry.createPresetFromConfig(
                name, description, preferences, genreHint,
                styleTone, styleVoice, styleDosAndDonts);
        return preset.toMap();
    }
}
