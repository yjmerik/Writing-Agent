package com.writing.novel.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * NCS-022（方案 §13/F17）：作者审美档案——可执行的艺术偏好表达。
 * 认可片段（含理由与出处）、希望减少的表达、叙述距离/语言密度/幽默/意象偏好。
 * 保存"喜欢这段的什么"比只有"文笔细腻"更可执行；单次选择不自动升级为硬规则。
 */
public class AestheticProfile {

    /** 认可片段：出处章 + 原文节选 + 作者认可的理由 */
    public record LikedSnippet(String chapterRef, String excerpt, String reason) {}

    private String id;
    private String projectId;
    private int revision;
    private List<LikedSnippet> likedSnippets = new ArrayList<>();
    private String dislikedPatterns = "";   // 希望减少的表达（分号分隔）
    private String narrativeDistance = "";  // 叙述距离偏好
    private String languageDensity = "";    // 语言密度偏好
    private String humorStyle = "";         // 幽默方式偏好
    private String imageryPreference = "";  // 意象偏好
    private Instant updatedAt;

    public AestheticProfile() {
        this.id = UUID.randomUUID().toString();
        this.updatedAt = Instant.now();
    }

    public boolean isEmpty() {
        return likedSnippets.isEmpty() && dislikedPatterns.isBlank() && narrativeDistance.isBlank()
                && languageDensity.isBlank() && humorStyle.isBlank() && imageryPreference.isBlank();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public int getRevision() { return revision; }
    public void setRevision(int revision) { this.revision = revision; }
    public List<LikedSnippet> getLikedSnippets() { return likedSnippets; }
    public void setLikedSnippets(List<LikedSnippet> likedSnippets) {
        this.likedSnippets = likedSnippets == null ? new ArrayList<>() : likedSnippets; }
    public String getDislikedPatterns() { return dislikedPatterns; }
    public void setDislikedPatterns(String v) { this.dislikedPatterns = v == null ? "" : v; }
    public String getNarrativeDistance() { return narrativeDistance; }
    public void setNarrativeDistance(String v) { this.narrativeDistance = v == null ? "" : v; }
    public String getLanguageDensity() { return languageDensity; }
    public void setLanguageDensity(String v) { this.languageDensity = v == null ? "" : v; }
    public String getHumorStyle() { return humorStyle; }
    public void setHumorStyle(String v) { this.humorStyle = v == null ? "" : v; }
    public String getImageryPreference() { return imageryPreference; }
    public void setImageryPreference(String v) { this.imageryPreference = v == null ? "" : v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
