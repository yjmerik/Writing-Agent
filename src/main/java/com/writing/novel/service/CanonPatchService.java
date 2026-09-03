package com.writing.novel.service;

import com.writing.novel.domain.Character;
import com.writing.novel.domain.KnowledgeEntry;
import com.writing.novel.domain.Outline;
import com.writing.novel.domain.StoryFact;
import com.writing.novel.memory.MemoryRevision;
import com.writing.novel.spi.NovelRepository;
import com.writing.novel.spi.StoryCanon;
import org.springframework.stereotype.Component;

import com.writing.novel.domain.ChapterSynopsis;
import com.writing.novel.domain.Volume;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * p13-h1-01: 人机真典补丁——卡面写入 + 真典 SUPERSEDE + 记忆失效走同一入口。
 */
@Component
public class CanonPatchService {

    public static final String HUMAN_PATCH_PREFIX = StoryCanon.HUMAN_PATCH_PREFIX;
    public static final int HUMAN_PATCH_PROTECT_CHAPTERS = StoryCanon.HUMAN_PATCH_PROTECT_CHAPTERS;
    private static final Set<String> WORLD_TYPES = Set.of(
            "GEOGRAPHY", "POWER_SYSTEM", "FACTION", "HISTORY", "RULE",
            "CULTURE", "ECONOMY", "RACE", "CUSTOM");

    public record PatchResult(Object entity, List<StoryFact> factsWritten, boolean invalidated) {}

    private final NovelRepository repository;
    private final StoryCanon canon; // p22-02: 只依赖真典端口
    private final MemoryRevision memoryRevision;

    public CanonPatchService(NovelRepository repository,
                             StoryCanon canon,
                             MemoryRevision memoryRevision) {
        this.repository = repository;
        this.canon = canon;
        this.memoryRevision = memoryRevision;
    }

    public PatchResult patchCharacter(String projectId, String charId, Character patch, int currentChapter) {
        Character existing = repository.findCharacter(charId)
                .orElseThrow(() -> new IllegalArgumentException("人物不存在: " + charId));
        if (!projectId.equals(existing.getProjectId())) {
            throw new IllegalArgumentException("人物不属于该项目: " + charId);
        }
        String oldSubject = existing.getName();
        List<StoryFact> oldStateFacts = activeFacts(projectId, StoryFact.FactType.CHARACTER_STATE, oldSubject);
        List<StoryFact> oldRelationshipFacts = activeFacts(
                projectId, StoryFact.FactType.RELATIONSHIP_STATE, oldSubject);
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
        boolean renamed = !Objects.equals(oldSubject, existing.getName());
        boolean stateTouched = renamed || patch.getRole() != null || patch.getPersonality() != null
                || patch.getAbility() != null || patch.getMotivation() != null
                || patch.getAppearance() != null || patch.getGrowthArc() != null;
        if (stateTouched) {
            StoryFact state = replaceOrRetire(projectId, StoryFact.FactType.CHARACTER_STATE,
                    oldSubject, existing.getName(), oldStateFacts, characterStateAssertion(existing), currentChapter);
            if (state != null) written.add(state);
        }
        if (renamed || patch.getRelationships() != null) {
            StoryFact relationship = replaceOrRetire(projectId, StoryFact.FactType.RELATIONSHIP_STATE,
                    oldSubject, existing.getName(), oldRelationshipFacts, existing.getRelationships(), currentChapter);
            if (relationship != null) written.add(relationship);
        }
        bump(projectId);
        return new PatchResult(existing, written, true);
    }

    public PatchResult patchWorld(String projectId, String entryId, KnowledgeEntry patch, int currentChapter) {
        validateWorldType(patch.getType());
        KnowledgeEntry existing = repository.findKnowledgeEntry(entryId)
                .orElseThrow(() -> new IllegalArgumentException("世界观条目不存在: " + entryId));
        if (!projectId.equals(existing.getProjectId())) {
            throw new IllegalArgumentException("世界观条目不属于该项目: " + entryId);
        }
        String oldSubject = existing.getName();
        List<StoryFact> oldFacts = activeFacts(projectId, StoryFact.FactType.WORLD_RULE, oldSubject);
        existing.setType(patch.getType());
        if (patch.getName() != null) existing.setName(patch.getName());
        if (patch.getContent() != null) existing.setContent(patch.getContent());
        repository.saveKnowledgeEntry(existing);

        List<StoryFact> written = new ArrayList<>();
        if (!Objects.equals(oldSubject, existing.getName()) || patch.getContent() != null) {
            StoryFact replacement = replaceOrRetire(projectId, StoryFact.FactType.WORLD_RULE,
                    oldSubject, existing.getName(), oldFacts, existing.getContent(), currentChapter);
            if (replacement != null) written.add(replacement);
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
            overwriteUnwrittenOnly(existing, incoming, currentChapter);
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

    /** p26-09: 已由 OutlineStructureService 完整验证的人工结构，按 payload 原子替换。 */
    public PatchResult replaceOutline(String projectId, Outline replacement, int currentChapter) {
        Outline existing = repository.findOutlineByProject(projectId)
                .orElseThrow(() -> new IllegalArgumentException("大纲不存在: " + projectId));
        repository.saveOutline(replacement);
        List<StoryFact> written = new ArrayList<>();
        if (replacement.getPremise() != null && !replacement.getPremise().isBlank()
                && !Objects.equals(existing.getPremise(), replacement.getPremise())) {
            written.add(writeHumanFact(projectId, StoryFact.FactType.PLOT_COMMITMENT,
                    "总纲", replacement.getPremise(), currentChapter));
        }
        bump(projectId);
        return new PatchResult(replacement, written, true);
    }

    private void overwriteUnwrittenOnly(Outline existing, Outline incoming, int currentChapter) {
        Map<Integer, ChapterSynopsis> incomingByChapter = new LinkedHashMap<>();
        Map<Integer, Volume> incomingByVolume = new LinkedHashMap<>();
        for (Volume volume : incoming.getVolumes()) {
            incomingByVolume.put(volume.getVolumeIndex(), volume);
            for (ChapterSynopsis synopsis : volume.getSynopses()) {
                incomingByChapter.put(synopsis.getChapterIndex(), synopsis);
            }
        }
        for (Volume volume : existing.getVolumes()) {
            boolean hasWritten = volume.getSynopses().stream()
                    .anyMatch(s -> s.getChapterIndex() <= currentChapter);
            Volume patchVolume = incomingByVolume.get(volume.getVolumeIndex());
            if (patchVolume != null && !hasWritten) {
                if (patchVolume.getTitle() != null) volume.setTitle(patchVolume.getTitle());
                if (patchVolume.getSummary() != null) volume.setSummary(patchVolume.getSummary());
            }
            for (ChapterSynopsis synopsis : volume.getSynopses()) {
                if (synopsis.getChapterIndex() <= currentChapter) continue;
                ChapterSynopsis patch = incomingByChapter.remove(synopsis.getChapterIndex());
                if (patch == null) continue;
                if (patch.getTitle() != null) synopsis.setTitle(patch.getTitle());
                if (patch.getSynopsis() != null) synopsis.setSynopsis(patch.getSynopsis());
                if (patch.getWritingNotes() != null) synopsis.setWritingNotes(patch.getWritingNotes());
            }
        }
        for (Volume patchVolume : incoming.getVolumes()) {
            Volume target = existing.getVolumes().stream()
                    .filter(v -> v.getVolumeIndex() == patchVolume.getVolumeIndex())
                    .findFirst().orElse(null);
            for (ChapterSynopsis patch : patchVolume.getSynopses()) {
                if (patch.getChapterIndex() <= currentChapter) continue;
                if (!incomingByChapter.containsKey(patch.getChapterIndex())) continue;
                incomingByChapter.remove(patch.getChapterIndex());
                if (target == null) {
                    target = new Volume(existing.getProjectId(), patchVolume.getVolumeIndex(),
                            patchVolume.getTitle(), patchVolume.getSummary());
                    existing.addVolume(target);
                }
                ChapterSynopsis created = new ChapterSynopsis(
                        existing.getProjectId(), patch.getChapterIndex(),
                        patch.getTitle(), patch.getSynopsis());
                if (patch.getWritingNotes() != null) created.setWritingNotes(patch.getWritingNotes());
                target.addSynopsis(created);
            }
        }
    }

    public PatchResult patchFact(String projectId, String factId, String assertion, Integer importance, int currentChapter) {
        StoryFact old = canon.findById(factId)
                .orElseThrow(() -> new IllegalArgumentException("真典事实不存在: " + factId));
        if (!projectId.equals(old.getProjectId())) {
            throw new IllegalArgumentException("真典事实不属于该项目: " + factId);
        }
        String text = assertion == null || assertion.isBlank() ? old.getAssertion() : assertion;
        StoryFact newer = canon.applyHumanPatch(projectId, old.getFactType(), old.getSubject(),
                text, currentChapter, importance);
        bump(projectId);
        return new PatchResult(newer, List.of(newer), true);
    }

    public PatchResult createWorld(String projectId, KnowledgeEntry entry, int currentChapter) {
        validateWorldType(entry.getType());
        if (!projectId.equals(entry.getProjectId())) {
            throw new IllegalArgumentException("世界观条目不属于该项目: " + entry.getId());
        }
        repository.saveKnowledgeEntry(entry);
        List<StoryFact> written = new ArrayList<>();
        if (entry.getContent() != null && !entry.getContent().isBlank()) {
            written.add(writeHumanFact(projectId, StoryFact.FactType.WORLD_RULE, entry.getName(),
                    entry.getName() + "：" + entry.getContent(), currentChapter));
        }
        bump(projectId);
        return new PatchResult(entry, written, true);
    }

    public PatchResult createCharacter(String projectId, Character created, int currentChapter) {
        if (!projectId.equals(created.getProjectId())) {
            throw new IllegalArgumentException("人物不属于该项目: " + created.getId());
        }
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

    public StoryFact retireFact(String projectId, String factId) {
        StoryFact existing = canon.retire(projectId, factId);
        bump(projectId);
        return existing;
    }

    public List<StoryFact> listActive(String projectId) {
        return canon == null ? List.of() : canon.recall(projectId);
    }

    /** p22-02: 人工写入统一走端口（ACTIVE 唯一性在实现内保证） */
    StoryFact writeHumanFact(String projectId, StoryFact.FactType type,
                             String subject, String assertion, int chapter) {
        return canon.applyHumanPatch(projectId, type, subject, assertion, chapter, 5);
    }

    private List<StoryFact> activeFacts(String projectId, StoryFact.FactType type, String subject) {
        return canon.recall(projectId).stream()
                .filter(f -> f.getFactType() == type && Objects.equals(subject, f.getSubject()))
                .toList();
    }

    private StoryFact replaceOrRetire(String projectId, StoryFact.FactType type,
                                      String oldSubject, String newSubject, List<StoryFact> oldFacts,
                                      String assertion, int currentChapter) {
        if (assertion == null || assertion.isBlank()) {
            oldFacts.forEach(f -> canon.retire(projectId, f.getId()));
            return null;
        }
        StoryFact newer = writeHumanFact(projectId, type, newSubject, assertion, currentChapter);
        if (!Objects.equals(oldSubject, newSubject)) {
            oldFacts.forEach(old -> canon.supersedeFact(old, newer));
        }
        return newer;
    }

    private String characterStateAssertion(Character character) {
        List<String> parts = new ArrayList<>();
        addFactPart(parts, "角色", character.getRole());
        addFactPart(parts, "性格", character.getPersonality());
        addFactPart(parts, "能力/境界", character.getAbility());
        addFactPart(parts, "动机", character.getMotivation());
        addFactPart(parts, "外貌", character.getAppearance());
        addFactPart(parts, "成长弧", character.getGrowthArc());
        return String.join("；", parts);
    }

    private void addFactPart(List<String> parts, String label, String value) {
        if (value != null && !value.isBlank()) parts.add(label + "：" + value);
    }

    private void bump(String projectId) {
        if (memoryRevision != null) memoryRevision.bump(projectId);
    }

    private void validateWorldType(String type) {
        if (!WORLD_TYPES.contains(type)) {
            throw new IllegalArgumentException("无效的世界观类型");
        }
    }
}
