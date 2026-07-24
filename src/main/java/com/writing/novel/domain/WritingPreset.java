package com.writing.novel.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;

/**
 * 写作预设模板——可打包、可复用、可分享的完整配置包。
 *
 * 包含：题材提示 + WritingPreferences 覆盖值 + StyleProfile 种子值。
 * 内置预设从 classpath:presets/*.json 加载；自定义预设持久化到 DB。
 */
public class WritingPreset {
    private String name;
    private String description;
    private GenreProfile genreHint;
    private Map<String, Object> preferences;
    private String styleTone;
    private String styleVoice;
    private String styleDosAndDonts;
    private boolean builtin;

    public WritingPreset() {
        this.preferences = new HashMap<>();
    }

    /** 从 JSON Map 构造（用于加载 JSON 文件） */
    @SuppressWarnings("unchecked")
    public static WritingPreset fromMap(Map<String, Object> map) {
        WritingPreset p = new WritingPreset();
        p.name = (String) map.get("name");
        p.description = (String) map.get("description");
        p.styleTone = (String) map.get("styleTone");
        p.styleVoice = (String) map.get("styleVoice");
        p.styleDosAndDonts = (String) map.get("styleDosAndDonts");
        p.builtin = Boolean.TRUE.equals(map.get("builtin"));
        String genre = (String) map.get("genreHint");
        if (genre != null && !genre.isBlank()) {
            try { p.genreHint = GenreProfile.valueOf(genre); } catch (Exception ignored) {}
        }
        Object prefs = map.get("preferences");
        if (prefs instanceof Map) {
            p.preferences = new HashMap<>((Map<String, Object>) prefs);
        }
        return p;
    }

    /** 转为 Map（用于序列化和 API 响应） */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        m.put("description", description);
        m.put("genreHint", genreHint != null ? genreHint.name() : null);
        m.put("preferences", preferences);
        m.put("styleTone", styleTone);
        m.put("styleVoice", styleVoice);
        m.put("styleDosAndDonts", styleDosAndDonts);
        m.put("builtin", builtin);
        return m;
    }

    /** 将此预设应用到一个新的 WritingPreferences 对象 */
    public com.writing.novel.domain.WritingPreferences applyToPreferences(String projectId) {
        var prefs = new com.writing.novel.domain.WritingPreferences(projectId);
        if (preferences != null && !preferences.isEmpty()) {
            prefs.applyUpdates(preferences);
        }
        return prefs;
    }

    /** 将此预设的 style 种子应用到 StyleProfile */
    public void applyToStyleProfile(com.writing.novel.domain.StyleProfile profile) {
        if (styleTone != null) profile.setTone(styleTone);
        if (styleVoice != null) profile.setVoice(styleVoice);
        if (styleDosAndDonts != null) profile.setDosAndDonts(styleDosAndDonts);
    }

    // Getters / Setters
    public String getName() { return name; }
    public void setName(String n) { this.name = n; }
    public String getDescription() { return description; }
    public void setDescription(String d) { this.description = d; }
    public GenreProfile getGenreHint() { return genreHint; }
    public void setGenreHint(GenreProfile g) { this.genreHint = g; }
    public Map<String, Object> getPreferences() { return preferences; }
    public void setPreferences(Map<String, Object> p) { this.preferences = p; }
    public String getStyleTone() { return styleTone; }
    public void setStyleTone(String s) { this.styleTone = s; }
    public String getStyleVoice() { return styleVoice; }
    public void setStyleVoice(String s) { this.styleVoice = s; }
    public String getStyleDosAndDonts() { return styleDosAndDonts; }
    public void setStyleDosAndDonts(String s) { this.styleDosAndDonts = s; }
    public boolean isBuiltin() { return builtin; }
    public void setBuiltin(boolean b) { this.builtin = b; }
}
