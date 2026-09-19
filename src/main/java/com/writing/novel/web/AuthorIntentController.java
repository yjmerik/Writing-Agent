package com.writing.novel.web;

import com.writing.novel.domain.AuthorIntent;
import com.writing.novel.persistence.sqlite.AuthorIntentRepository;
import com.writing.novel.spi.NovelRepository;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NCS-003（方案 §05/J02）: 创作约定 API。
 * GET 返回最新修订（无修订时为合法空契约，修订 0）；PUT 追加新修订并保留历史；
 * 空对象/缺失字段表示"未确定项保留"，不以 null 覆盖已有语义。
 */
@RestController
@RequestMapping("/api/projects/{id}/intent")
public class AuthorIntentController {

    private final NovelRepository repository;
    private final AuthorIntentRepository intentRepository;

    public AuthorIntentController(NovelRepository repository, AuthorIntentRepository intentRepository) {
        this.repository = repository;
        this.intentRepository = intentRepository;
    }

    @GetMapping
    public Map<String, Object> current(@PathVariable String id) {
        requireProject(id);
        return toMap(intentRepository.findLatest(id).orElse(emptyIntent(id)));
    }

    @PutMapping
    public Map<String, Object> save(@PathVariable String id, @RequestBody Map<String, Object> body) {
        requireProject(id);
        AuthorIntent intent = intentRepository.findLatest(id).orElse(emptyIntent(id));
        // 未提供的字段沿用上一修订——保存是渐进的，不以 null 清空
        intent.setPremise(mergeField(body, "premise", intent.getPremise(), AuthorIntent.MAX_FIELD_CHARS));
        intent.setGenreExpectation(mergeField(body, "genreExpectation", intent.getGenreExpectation(), AuthorIntent.MAX_FIELD_CHARS));
        intent.setThemes(mergeField(body, "themes", intent.getThemes(), AuthorIntent.MAX_FIELD_CHARS));
        intent.setPov(mergeField(body, "pov", intent.getPov(), AuthorIntent.MAX_FIELD_CHARS));
        intent.setLanguageSamples(mergeField(body, "languageSamples", intent.getLanguageSamples(), AuthorIntent.MAX_SAMPLE_CHARS));
        intent.setLengthRange(mergeField(body, "lengthRange", intent.getLengthRange(), AuthorIntent.MAX_FIELD_CHARS));
        intent.setAutonomy(mergeField(body, "autonomy", intent.getAutonomy(), AuthorIntent.MAX_FIELD_CHARS));
        intent.setUnwantedPatterns(mergeField(body, "unwantedPatterns", intent.getUnwantedPatterns(), AuthorIntent.MAX_FIELD_CHARS));
        return toMap(intentRepository.append(id, intent));
    }

    @GetMapping("/history")
    public Map<String, Object> history(@PathVariable String id) {
        requireProject(id);
        List<Map<String, Object>> revisions = new ArrayList<>();
        for (AuthorIntent intent : intentRepository.history(id)) {
            revisions.add(toMap(intent));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("projectId", id);
        result.put("revisions", revisions);
        return result;
    }

    private AuthorIntent emptyIntent(String projectId) {
        AuthorIntent intent = new AuthorIntent();
        intent.setProjectId(projectId);
        return intent;
    }

    /** 提供则取新值并校验长度；未提供沿用旧值 */
    private String mergeField(Map<String, Object> body, String key, String previous, int maxChars) {
        Object value = body.get(key);
        if (!(value instanceof String text)) return previous;
        String trimmed = text.trim();
        if (trimmed.length() > maxChars) {
            throw new IllegalArgumentException(key + " 超过最大长度 " + maxChars + " 字符（当前 " + trimmed.length() + "）");
        }
        return trimmed;
    }

    private void requireProject(String projectId) {
        if (repository.findProject(projectId).isEmpty()) {
            throw new IllegalArgumentException("项目不存在: " + projectId);
        }
    }

    private Map<String, Object> toMap(AuthorIntent intent) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("projectId", intent.getProjectId());
        m.put("revision", intent.getRevision());
        m.put("premise", intent.getPremise());
        m.put("genreExpectation", intent.getGenreExpectation());
        m.put("themes", intent.getThemes());
        m.put("pov", intent.getPov());
        m.put("languageSamples", intent.getLanguageSamples());
        m.put("lengthRange", intent.getLengthRange());
        m.put("autonomy", intent.getAutonomy());
        m.put("unwantedPatterns", intent.getUnwantedPatterns());
        m.put("updatedAt", intent.getUpdatedAt());
        return m;
    }
}
