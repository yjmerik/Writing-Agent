package com.writing.novel.service;

import com.writing.novel.domain.GenreProfile;
import com.writing.novel.domain.NarrativePolicy;
import com.writing.novel.persistence.sqlite.NarrativePolicyRepository;
import com.writing.novel.spi.NovelRepository;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * NCS-190: 叙事策略解析——题材默认 + 项目级覆盖 → 生效 NarrativePolicy。
 *
 * <p>本 feature 只提供对象与存取（REST 读写回路）；prompt 消费方接入在 NCS-191+。
 * 无题材项目按 GENERAL 宽松默认（与 ContextAssembler 现状 genre==null 视为快节奏一致）。
 */
public class NarrativePolicyService {

    private final NovelRepository repository;
    private final NarrativePolicyRepository policyRepository;

    public NarrativePolicyService(NovelRepository repository, NarrativePolicyRepository policyRepository) {
        this.repository = repository;
        this.policyRepository = policyRepository;
    }

    /** 生效策略：项目题材默认 + 持久化覆盖 */
    public NarrativePolicy resolve(String projectId) {
        GenreProfile genre = repository.findProject(projectId)
                .map(com.writing.novel.domain.Project::getGenre)
                .orElse(GenreProfile.GENERAL);
        NarrativePolicy policy = NarrativePolicy.forGenre(genre);
        Map<String, Object> overrides = policyRepository.findOverrides(projectId);
        if (!overrides.isEmpty()) {
            policy.applyUpdates(overrides);
        }
        return policy;
    }

    /** 题材默认（不含覆盖） */
    public NarrativePolicy defaultsFor(String projectId) {
        GenreProfile genre = repository.findProject(projectId)
                .map(com.writing.novel.domain.Project::getGenre)
                .orElse(GenreProfile.GENERAL);
        return NarrativePolicy.forGenre(genre);
    }

    /** 当前持久化的覆盖（空 Map=无覆盖） */
    public Map<String, Object> overridesOf(String projectId) {
        return policyRepository.findOverrides(projectId);
    }

    /**
     * 部分覆盖写入：与既有覆盖合并后整体校验（比例 [0,1]、区间 min≤max、非负），
     * 违规抛 IllegalArgumentException（REST 层映射 400），不落库。
     */
    public NarrativePolicy saveOverrides(String projectId, Map<String, Object> updates) {
        requireProject(projectId);
        Map<String, Object> merged = new LinkedHashMap<>(policyRepository.findOverrides(projectId));
        merged.putAll(updates == null ? Map.of() : updates);
        NarrativePolicy effective = NarrativePolicy.forGenre(currentGenre(projectId));
        effective.applyUpdates(merged);
        effective.validate();
        policyRepository.saveOverrides(projectId, merged);
        return effective;
    }

    public void clearOverrides(String projectId) {
        requireProject(projectId);
        policyRepository.deleteOverrides(projectId);
    }

    private GenreProfile currentGenre(String projectId) {
        return repository.findProject(projectId)
                .map(com.writing.novel.domain.Project::getGenre)
                .orElse(GenreProfile.GENERAL);
    }

    private void requireProject(String projectId) {
        if (repository.findProject(projectId).isEmpty()) {
            throw new IllegalArgumentException("项目不存在: " + projectId);
        }
    }
}
