package com.writing.novel.web;

import com.writing.novel.agent.WritingPreferencesAgent;
import com.writing.novel.domain.WritingPreferences;
import com.writing.novel.spi.NovelRepository;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 写作偏好 REST API。
 * 支持直接 JSON 读写 + 自然语言交互（chat）。
 */
@RestController
@RequestMapping("/api/projects/{id}/preferences")
public class PreferencesController {

    private final NovelRepository repository;
    private final WritingPreferencesAgent preferencesAgent;

    public PreferencesController(NovelRepository repository, WritingPreferencesAgent preferencesAgent) {
        this.repository = repository;
        this.preferencesAgent = preferencesAgent;
    }

    /** 获取当前偏好配置 */
    @GetMapping
    public Map<String, Object> getPreferences(@PathVariable String id) {
        var prefs = repository.findWritingPreferences(id)
                .orElseGet(() -> {
                    var p = new WritingPreferences(id);
                    repository.saveWritingPreferences(p);
                    return p;
                });
        return prefs.toMap();
    }

    /** 直接更新偏好（全量替换） */
    @PutMapping
    public Map<String, Object> updatePreferences(@PathVariable String id, @RequestBody Map<String, Object> body) {
        var prefs = repository.findWritingPreferences(id)
                .orElseGet(() -> new WritingPreferences(id));
        prefs.applyUpdates(body);
        repository.saveWritingPreferences(prefs);
        return prefs.toMap();
    }

    /** 自然语言交互：作者说一句话，Agent 解析并更新配置 */
    @PostMapping("/chat")
    public Map<String, Object> chat(@PathVariable String id, @RequestBody Map<String, String> body) {
        String message = body.getOrDefault("message", "");
        var result = preferencesAgent.chat(id, message);
        return Map.of(
                "reply", result.reply(),
                "preferences", result.preferences().toMap());
    }
}
