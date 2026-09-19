package com.writing.novel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.domain.AestheticProfile;
import com.writing.novel.service.AestheticProfileService;
import com.writing.novel.spi.NovelRepository;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * NCS-022（方案 §13）：审美档案 API——GET 当前 / PUT 追加新修订。
 */
@RestController
@RequestMapping("/api/projects/{id}/aesthetic-profile")
public class AestheticProfileController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final NovelRepository repository;
    private final AestheticProfileService service;

    public AestheticProfileController(NovelRepository repository, AestheticProfileService service) {
        this.repository = repository;
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> current(@PathVariable String id) {
        requireProject(id);
        return toMap(service.current(id));
    }

    @PutMapping
    public Map<String, Object> save(@PathVariable String id, @RequestBody Map<String, Object> body) {
        requireProject(id);
        AestheticProfile profile = new AestheticProfile();
        profile.setProjectId(id);
        try {
            if (body.get("likedSnippets") != null) {
                profile.setLikedSnippets(MAPPER.convertValue(body.get("likedSnippets"),
                        MAPPER.getTypeFactory().constructCollectionType(
                                java.util.List.class, AestheticProfile.LikedSnippet.class)));
            }
            profile.setDislikedPatterns(text(body, "dislikedPatterns"));
            profile.setNarrativeDistance(text(body, "narrativeDistance"));
            profile.setLanguageDensity(text(body, "languageDensity"));
            profile.setHumorStyle(text(body, "humorStyle"));
            profile.setImageryPreference(text(body, "imageryPreference"));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("审美档案字段无效: " + e.getMessage());
        }
        return toMap(service.save(profile));
    }

    private String text(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private void requireProject(String projectId) {
        if (repository.findProject(projectId).isEmpty()) {
            throw new IllegalArgumentException("项目不存在: " + projectId);
        }
    }

    private Map<String, Object> toMap(AestheticProfile p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("projectId", p.getProjectId());
        m.put("revision", p.getRevision());
        m.put("likedSnippets", p.getLikedSnippets());
        m.put("dislikedPatterns", p.getDislikedPatterns());
        m.put("narrativeDistance", p.getNarrativeDistance());
        m.put("languageDensity", p.getLanguageDensity());
        m.put("humorStyle", p.getHumorStyle());
        m.put("imageryPreference", p.getImageryPreference());
        m.put("updatedAt", p.getUpdatedAt() == null ? "" : p.getUpdatedAt().toString());
        return m;
    }
}
