package com.writing.novel.memory;

import com.writing.novel.domain.Character;
import com.writing.novel.domain.KnowledgeEntry;
import com.writing.novel.spi.NovelRepository;

import java.util.List;
import java.util.Optional;

/**
 * 第一层记忆：结构化知识库服务。
 * 封装世界观条目与人物的 CRUD，写入时同步索引到向量库。
 */
public class KnowledgeBaseService {

    private final NovelRepository repository;
    private final VectorRetrievalService vectorRetrieval;
    /** p10-w1-01: 写入后 bump，使 ContextAssembler 的 worldbuilding/knowledgeQuote 稳定段缓存失效 */
    private final MemoryRevision memoryRevision;

    public KnowledgeBaseService(NovelRepository repository, VectorRetrievalService vectorRetrieval) {
        this(repository, vectorRetrieval, null);
    }

    /** p10-w1-01: 带记忆版本号的构造器（生产装配用） */
    public KnowledgeBaseService(NovelRepository repository, VectorRetrievalService vectorRetrieval,
                                MemoryRevision memoryRevision) {
        this.repository = repository;
        this.vectorRetrieval = vectorRetrieval;
        this.memoryRevision = memoryRevision;
    }

    public void saveEntry(KnowledgeEntry entry) {
        repository.saveKnowledgeEntry(entry);
        vectorRetrieval.index(new IndexedText(
                entry.getId(), entry.getProjectId(),
                entry.getName() + "：" + entry.getContent(), "WORLDBUILDING"));
        invalidate(entry.getProjectId());
    }

    /**
     * Replace one project's worldbuilding as a coherent memory set.
     * Candidate vectors use IDs disjoint from the current set and are prepared before the database
     * transaction. Any pre-commit failure removes those candidates while leaving the old database
     * and vectors intact; after commit, removing old IDs is deterministic.
     */
    public void replaceEntries(String projectId, List<KnowledgeEntry> candidates) {
        java.util.Objects.requireNonNull(projectId, "projectId");
        java.util.Objects.requireNonNull(candidates, "candidates");
        java.util.Set<String> oldIds = repository.findKnowledgeEntriesByProject(projectId).stream()
                .map(KnowledgeEntry::getId)
                .collect(java.util.stream.Collectors.toSet());
        java.util.Set<String> candidateIds = new java.util.HashSet<>();
        for (KnowledgeEntry candidate : candidates) {
            if (candidate == null || !projectId.equals(candidate.getProjectId())
                    || candidate.getId() == null || candidate.getId().isBlank()) {
                throw new IllegalArgumentException("世界观候选缺少独立项目 ID");
            }
            if (!candidateIds.add(candidate.getId()) || oldIds.contains(candidate.getId())) {
                throw new IllegalArgumentException("世界观候选 ID 与现有条目冲突: " + candidate.getId());
            }
        }
        List<IndexedText> candidateTexts = candidates.stream()
                .map(entry -> new IndexedText(entry.getId(), entry.getProjectId(),
                        entry.getName() + "：" + entry.getContent(), "WORLDBUILDING"))
                .toList();
        try {
            vectorRetrieval.indexBatch(candidateTexts);
            repository.replaceKnowledgeEntries(projectId, candidates);
        } catch (RuntimeException failure) {
            for (String candidateId : candidateIds) {
                try {
                    vectorRetrieval.remove(candidateId);
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        }
        oldIds.forEach(vectorRetrieval::remove);
        invalidate(projectId);
    }

    public List<KnowledgeEntry> findAllEntries(String projectId) {
        return repository.findKnowledgeEntriesByProject(projectId);
    }

    public List<KnowledgeEntry> findEntriesByType(String projectId, String type) {
        return repository.findKnowledgeEntriesByType(projectId, type);
    }

    public String getIndexedText(String id) {
        return vectorRetrieval.getText(id);
    }

    public void saveCharacter(Character character) {
        repository.saveCharacter(character);
        // 完整索引全部字段，提升召回率
        String indexText = character.getName() + "（" + character.getRole() + "）"
                + nullSafe(character.getPersonality()) + nullSafe(character.getAbility())
                + nullSafe(character.getMotivation()) + nullSafe(character.getAppearance())
                + nullSafe(character.getRelationships()) + nullSafe(character.getGrowthArc())
                + nullSafe(character.getSpeakingStyle()) + nullSafe(character.getKnowledgeDomains());
        vectorRetrieval.index(new IndexedText(
                character.getId(), character.getProjectId(), indexText, "CHARACTER"));
        invalidate(character.getProjectId());
    }

    /** p10-w1-01: 写入后使该项目稳定段缓存失效（memoryRevision==null 时为空操作，向后兼容） */
    private void invalidate(String projectId) {
        if (memoryRevision != null) {
            memoryRevision.bump(projectId);
        }
    }

    public List<Character> findAllCharacters(String projectId) {
        return repository.findCharactersByProject(projectId);
    }

    public Optional<Character> findCharacterByName(String projectId, String name) {
        return repository.findCharactersByProject(projectId).stream()
                .filter(c -> name.equals(c.getName()))
                .findFirst();
    }

    public Optional<Character> findCharacter(String id) {
        return repository.findCharacter(id);
    }

    private static String nullSafe(String s) {
        return s == null ? "" : "，" + s;
    }
}
