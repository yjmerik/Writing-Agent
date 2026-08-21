package com.writing.novel.service;

import com.writing.novel.domain.Character;
import com.writing.novel.domain.KnowledgeEntry;
import com.writing.novel.domain.Outline;
import com.writing.novel.domain.StoryFact;
import com.writing.novel.memory.MemoryRevision;
import com.writing.novel.persistence.sqlite.StoryFactRepository;
import com.writing.novel.spi.NovelRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * p13-h1-01: 人机真典补丁——卡面写入 + 真典 SUPERSEDE + 记忆失效走同一入口。
 */
@Component
public class CanonPatchService {

    public static final String HUMAN_PATCH_PREFIX = "【HUMAN_PATCH】";
    public static final int HUMAN_PATCH_PROTECT_CHAPTERS = 5;

    public record PatchResult(Object entity, List<StoryFact> factsWritten, boolean invalidated) {}

    private final NovelRepository repository;
    private final StoryFactRepository factRepo;
    private final MemoryRevision memoryRevision;

    public CanonPatchService(NovelRepository repository,
                             StoryFactRepository factRepo,
                             MemoryRevision memoryRevision) {
        this.repository = repository;
        this.factRepo = factRepo;
        this.memoryRevision = memoryRevision;
    }

    public PatchResult patchCharacter(String projectId, String charId, Character patch, int currentChapter) {
        Character existing = repository.findCharacter(charId)
                .orElseThrow(() -> new IllegalArgumentException("人物不存在: " + charId));
        if (patch.getName() != null) existing.setName(patch.getName());
        if (patch.getRole() != null) existing.setRole(patch.getRole());
        if (patch.getPersonality() != null) existing.setPersonality(patch.getPersonality());
        if (patch.getAppearance() != null) existing.setAppearance(patch.getAppearance());
        if (patch.getSpeakingStyle() != null) existing.setSpeakingStyle(patch.getSpeakingStyle());
        // 可变状态：卡面作展示兜底，真典为人读主源
        if (patch.getAbility() != null) existing.setAbility(patch.getAbility());
        if (patch.getMotivation() != null) existing.setMotivation(patch.getMotivation());
        if (patch.getGrowthArc() != null) existing.setGrowthArc(patch.getGrowthArc());
        if (patch.getRelationships() != null) existing.setRelationships(patch.getRelationships());
        repository.saveCharacter(existing);

        List<StoryFact> written = new ArrayList<>();
        if (patch.getAbility() != null && !patch.getAbility().isBlank()) {
            written.add(writeHumanFact(projectId, StoryFact.FactType.CHARACTER_STATE,
                    existing.getName(), "能力/境界：" + patch.getAbility(), currentChapter));
        }
        if (patch.getMotivation() != null && !patch.getMotivation().isBlank()) {
            written.add(writeHumanFact(projectId, StoryFact.FactType.CHARACTER_STATE,
                    existing.getName(), "动机：" + patch.getMotivation(), currentChapter));
        }
        if (patch.getGrowthArc() != null && !patch.getGrowthArc().isBlank()) {
            written.add(writeHumanFact(projectId, StoryFact.FactType.CHARACTER_STATE,
                    existing.getName(), "成长弧：" + patch.getGrowthArc(), currentChapter));
        }
        if (patch.getRelationships() != null && !patch.getRelationships().isBlank()) {
            written.add(writeHumanFact(projectId, StoryFact.FactType.RELATIONSHIP_STATE,
                    existing.getName(), patch.getRelationships(), currentChapter));
        }
        bump(projectId);
        return new PatchResult(existing, written, true);
    }

    public PatchResult patchWorld(String projectId, String entryId, KnowledgeEntry patch, int currentChapter) {
        KnowledgeEntry existing = repository.findKnowledgeEntry(entryId)
                .orElseThrow(() -> new IllegalArgumentException("世界观条目不存在: " + entryId));
        if (patch.getName() != null) existing.setName(patch.getName());
        if (patch.getContent() != null) existing.setContent(patch.getContent());
        repository.saveKnowledgeEntry(existing);

        List<StoryFact> written = new ArrayList<>();
        if (patch.getContent() != null && !patch.getContent().isBlank()) {
            written.add(writeHumanFact(projectId, StoryFact.FactType.WORLD_RULE,
                    existing.getName(), patch.getContent(), currentChapter));
        }
        bump(projectId);
        return new PatchResult(existing, written, true);
    }

    public PatchResult patchOutline(String projectId, Outline incoming, int currentChapter) {
        Outline existing = repository.findOutlineByProject(projectId)
                .orElseThrow(() -> new IllegalArgumentException("大纲不存在: " + projectId));
        if (incoming.getPremise() != null && !incoming.getPremise().isBlank()) {
            existing.setPremise(incoming.getPremise());
        }
        if (incoming.getVolumes() != null && !incoming.getVolumes().isEmpty()) {
            existing.getVolumes().clear();
            incoming.getVolumes().forEach(existing::addVolume);
        }
        repository.saveOutline(existing);
        List<StoryFact> written = new ArrayList<>();
        if (incoming.getPremise() != null && !incoming.getPremise().isBlank()) {
            written.add(writeHumanFact(projectId, StoryFact.FactType.PLOT_COMMITMENT,
                    "总纲", incoming.getPremise(), currentChapter));
        }
        bump(projectId);
        return new PatchResult(existing, written, true);
    }

    public PatchResult patchFact(String projectId, String factId, String assertion, Integer importance, int currentChapter) {
        StoryFact old = factRepo.findById(factId)
                .orElseThrow(() -> new IllegalArgumentException("真典事实不存在: " + factId));
        if (!projectId.equals(old.getProjectId())) {
            throw new IllegalArgumentException("真典事实不属于该项目: " + factId);
        }
        String text = assertion == null || assertion.isBlank() ? old.getAssertion() : assertion;
        StoryFact newer = new StoryFact(projectId, old.getFactType(), old.getSubject(),
                text.startsWith(HUMAN_PATCH_PREFIX) ? text : HUMAN_PATCH_PREFIX + text,
                currentChapter);
        newer.setImportance(importance != null ? importance : 5);
        newer.setLastValidatedChapter(currentChapter);
        factRepo.supersede(old, newer);
        bump(projectId);
        return new PatchResult(newer, List.of(newer), true);
    }

    public PatchResult createWorld(String projectId, KnowledgeEntry entry, int currentChapter) {
        repository.saveKnowledgeEntry(entry);
        StoryFact.FactType factType = "ITEM".equals(entry.getType())
                ? StoryFact.FactType.OBJECT_STATE : StoryFact.FactType.WORLD_RULE;
        List<StoryFact> written = new ArrayList<>();
        if (entry.getContent() != null && !entry.getContent().isBlank()) {
            written.add(writeHumanFact(projectId, factType, entry.getName(),
                    entry.getName() + "：" + entry.getContent(), currentChapter));
        }
        bump(projectId);
        return new PatchResult(entry, written, true);
    }

    public PatchResult createCharacter(String projectId, Character created, int currentChapter) {
        repository.saveCharacter(created);
        List<StoryFact> written = new ArrayList<>();
        String assertion = created.getAbility() != null && !created.getAbility().isBlank()
                ? "能力/境界：" + created.getAbility()
                : "角色：" + (created.getRole() != null ? created.getRole() : created.getName());
        written.add(writeHumanFact(projectId, StoryFact.FactType.CHARACTER_STATE,
                created.getName(), assertion, currentChapter));
        bump(projectId);
        return new PatchResult(created, written, true);
    }

    public StoryFact addFact(String projectId, StoryFact.FactType type, String subject,
                             String assertion, int currentChapter) {
        StoryFact created = writeHumanFact(projectId, type, subject, assertion, currentChapter);
        bump(projectId);
        return created;
    }

    public int currentChapter(String projectId) {
        return repository.findProject(projectId).map(p -> p.getCurrentChapterIndex()).orElse(0);
    }

    public List<StoryFact> listActive(String projectId) {
        return factRepo == null ? List.of() : factRepo.findActive(projectId);
    }

    /** FactHarvester：5 章内的人补丁不得自动 supercede。 */
    public boolean isProtectedHumanPatch(StoryFact fact, int currentChapter) {
        if (fact == null || fact.getAssertion() == null) return false;
        if (!fact.getAssertion().startsWith(HUMAN_PATCH_PREFIX)) return false;
        return currentChapter - fact.getLastValidatedChapter() <= HUMAN_PATCH_PROTECT_CHAPTERS;
    }

    StoryFact writeHumanFact(String projectId, StoryFact.FactType type,
                             String subject, String assertion, int chapter) {
        String text = assertion.startsWith(HUMAN_PATCH_PREFIX)
                ? assertion : HUMAN_PATCH_PREFIX + assertion;
        StoryFact newer = new StoryFact(projectId, type, subject, text, chapter);
        newer.setImportance(5);
        newer.setLastValidatedChapter(chapter);
        if (factRepo != null) {
            for (StoryFact old : factRepo.findActiveBySubject(projectId, subject)) {
                if (old.getFactType() == type && !isProtectedHumanPatch(old, chapter)) {
                    factRepo.supersede(old, newer);
                    return newer;
                }
            }
            factRepo.save(newer);
        }
        return newer;
    }

    private void bump(String projectId) {
        if (memoryRevision != null) memoryRevision.bump(projectId);
    }
}
