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
     * The database transaction completes before any vector is changed, so a rejected database
     * candidate cannot remove the currently retrievable setup.
     */
    public void replaceEntries(String projectId, List<KnowledgeEntry> candidates) {
        List<String> oldIds = repository.findKnowledgeEntriesByProject(projectId).stream()
                .map(KnowledgeEntry::getId)
                .toList();
        repository.replaceKnowledgeEntries(projectId, candidates);
        oldIds.forEach(vectorRetrieval::remove);
        vectorRetrieval.indexBatch(candidates.stream()
                .map(entry -> new IndexedText(entry.getId(), entry.getProjectId(),
                        entry.getName() + "：" + entry.getContent(), "WORLDBUILDING"))
                .toList());
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
