package com.writing.novel.service;

import com.writing.novel.domain.AestheticProfile;
import com.writing.novel.persistence.sqlite.AestheticProfileRepository;

/**
 * NCS-022（方案 §13/F17）：审美档案服务——版本化存储与写作注入文本构建。
 * 条件注入：档案为空返回空串（无档案项目注入不变，指纹稳定）。
 */
public class AestheticProfileService {

    private final AestheticProfileRepository repository;

    public AestheticProfileService(AestheticProfileRepository repository) {
        this.repository = repository;
    }

    public AestheticProfile current(String projectId) {
        return repository.findLatest(projectId);
    }

    public AestheticProfile save(AestheticProfile profile) {
        return repository.append(profile);
    }

    /** 写作注入文本；空档案返回空串 */
    public String buildInjection(String projectId) {
        AestheticProfile profile = repository.findLatest(projectId);
        if (profile == null || profile.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("【作者审美档案（向此校准，非硬规则）】\n");
        if (!profile.getLikedSnippets().isEmpty()) {
            sb.append("认可的语言样本：\n");
            for (var snippet : profile.getLikedSnippets()) {
                sb.append("「").append(snippet.excerpt()).append("」（")
                        .append(snippet.chapterRef()).append("）——")
                        .append(snippet.reason()).append("\n");
            }
        }
        if (!profile.getDislikedPatterns().isBlank()) {
            sb.append("希望减少：").append(profile.getDislikedPatterns()).append("\n");
        }
        appendPref(sb, "叙述距离", profile.getNarrativeDistance());
        appendPref(sb, "语言密度", profile.getLanguageDensity());
        appendPref(sb, "幽默方式", profile.getHumorStyle());
        appendPref(sb, "意象偏好", profile.getImageryPreference());
        return sb.toString().trim();
    }

    private void appendPref(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) {
            sb.append(label).append("：").append(value).append("\n");
        }
    }
}
