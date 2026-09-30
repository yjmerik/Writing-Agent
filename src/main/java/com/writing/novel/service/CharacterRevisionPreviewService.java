package com.writing.novel.service;

import com.writing.novel.domain.Character;
import com.writing.novel.spi.NovelRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NCS-154（review 整改，修 NCS-144 批准制缺口）：人物卡修订的预览-确认链。
 * 框架修订点名既有卡时，候选卡先阶段化为待确认预览（逐字段 before/after diff，
 * 内存态、不落新表），作者 confirm 才按原卡 id upsert 落库，discard 即清空。
 * 候选空白字段保留原值（copyIfPresent 语义从 NovelOrchestrator 下沉至此）。
 * 进程重启丢失待确认预览属预期行为：作者重新发起修订即可。
 * 实例由 NovelOrchestrator 持有（仓库同源），经其访问器供 web 层使用。
 */
public class CharacterRevisionPreviewService {

    /** 单字段变更：field 为 Character 属性名（personality/ability/...） */
    public record FieldChange(String field, String before, String after) {}

    /** 单张卡的修订预览：characterId/name 取自原卡，changes 仅含真实变化的字段 */
    public record CardRevision(String characterId, String name, List<FieldChange> changes) {}

    /** 一次修订产生的整批待确认预览（同项目仅保留最新一批） */
    public static final class PendingRevisions {
        private final String previewId;
        private final String projectId;
        private final List<CardRevision> cards;
        /** 确认将落库的合并卡（原卡 + 候选非空白字段），与 cards 同序 */
        private final List<Character> merged;

        PendingRevisions(String previewId, String projectId, List<CardRevision> cards,
                         List<Character> merged) {
            this.previewId = previewId;
            this.projectId = projectId;
            this.cards = List.copyOf(cards);
            this.merged = List.copyOf(merged);
        }

        public String previewId() { return previewId; }
        public String projectId() { return projectId; }
        public List<CardRevision> cards() { return cards; }
    }

    private final NovelRepository repository;
    /** 每个项目至多一批待确认预览；再次修订覆盖旧批 */
    private final Map<String, PendingRevisions> pendingByProject = new ConcurrentHashMap<>();

    public CharacterRevisionPreviewService(NovelRepository repository) {
        this.repository = repository;
    }

    /**
     * 阶段化一批候选卡：逐卡计算 diff 与合并卡，无任何真实字段变化时抛
     * {@link IllegalStateException}；原卡/候选按位置一一对应。
     */
    public PendingRevisions stage(String projectId, List<Character> originals, List<Character> candidates) {
        if (originals.size() != candidates.size()) {
            throw new IllegalArgumentException("原卡与候选数量不一致");
        }
        List<CardRevision> cards = new ArrayList<>();
        List<Character> merged = new ArrayList<>();
        for (int i = 0; i < originals.size(); i++) {
            Character original = originals.get(i);
            List<FieldChange> changes = diff(original, candidates.get(i));
            if (changes.isEmpty()) continue;
            cards.add(new CardRevision(original.getId(), original.getName(), changes));
            merged.add(mergeInto(original, candidates.get(i)));
        }
        if (cards.isEmpty()) {
            throw new IllegalStateException("候选未产生有效字段变化");
        }
        PendingRevisions pending = new PendingRevisions(UUID.randomUUID().toString(), projectId, cards, merged);
        pendingByProject.put(projectId, pending);
        return pending;
    }

    /** 该项目当前待确认预览（无则空） */
    public Optional<PendingRevisions> find(String projectId) {
        return Optional.ofNullable(pendingByProject.get(projectId));
    }

    /** 作者确认：合并卡按原卡 id upsert 落库；返回点名已应用卡的摘要 */
    public String confirm(String projectId, String previewId) {
        PendingRevisions pending = requirePending(projectId, previewId);
        List<String> applied = new ArrayList<>();
        for (int i = 0; i < pending.cards().size(); i++) {
            Character card = pending.merged.get(i);
            boolean stillExists = repository.findCharactersByProject(projectId).stream()
                    .anyMatch(c -> c.getId().equals(card.getId()));
            if (!stillExists) {
                throw new IllegalStateException(
                        "待确认卡已不存在: " + card.getName() + "（可能被删除，请重新发起修订）");
            }
            repository.saveCharacter(card);
            applied.add(card.getName());
        }
        pendingByProject.remove(projectId);
        return "已应用 " + applied.size() + " 张人物卡修订：" + String.join("、", applied);
    }

    /** 作者放弃：清空待确认预览，库中零变动 */
    public void discard(String projectId, String previewId) {
        requirePending(projectId, previewId);
        pendingByProject.remove(projectId);
    }

    private PendingRevisions requirePending(String projectId, String previewId) {
        PendingRevisions pending = pendingByProject.get(projectId);
        if (pending == null || !pending.previewId().equals(previewId)) {
            throw new IllegalArgumentException("待确认人物卡修订不存在或已失效");
        }
        return pending;
    }

    /** 候选非空白字段覆盖到原卡副本（保留 id/姓名/项目绑定），空白保留原值 */
    static Character mergeInto(Character original, Character candidate) {
        Character merged = original;
        copyIfPresent(candidate.getRole(), merged::setRole);
        copyIfPresent(candidate.getPersonality(), merged::setPersonality);
        copyIfPresent(candidate.getAbility(), merged::setAbility);
        copyIfPresent(candidate.getMotivation(), merged::setMotivation);
        copyIfPresent(candidate.getAppearance(), merged::setAppearance);
        copyIfPresent(candidate.getRelationships(), merged::setRelationships);
        copyIfPresent(candidate.getGrowthArc(), merged::setGrowthArc);
        copyIfPresent(candidate.getSpeakingStyle(), merged::setSpeakingStyle);
        return merged;
    }

    private static void copyIfPresent(String value, java.util.function.Consumer<String> setter) {
        if (value != null && !value.isBlank()) setter.accept(value);
    }

    /** 逐字段 diff：候选空白/缺失字段视为保留原值，不产生变更项 */
    static List<FieldChange> diff(Character original, Character candidate) {
        List<FieldChange> changes = new ArrayList<>();
        collect(changes, "role", original.getRole(), candidate.getRole());
        collect(changes, "personality", original.getPersonality(), candidate.getPersonality());
        collect(changes, "ability", original.getAbility(), candidate.getAbility());
        collect(changes, "motivation", original.getMotivation(), candidate.getMotivation());
        collect(changes, "appearance", original.getAppearance(), candidate.getAppearance());
        collect(changes, "relationships", original.getRelationships(), candidate.getRelationships());
        collect(changes, "growthArc", original.getGrowthArc(), candidate.getGrowthArc());
        collect(changes, "speakingStyle", original.getSpeakingStyle(), candidate.getSpeakingStyle());
        return changes;
    }

    private static void collect(List<FieldChange> changes, String field, String before, String after) {
        if (after != null && !after.isBlank() && !after.equals(before)) {
            changes.add(new FieldChange(field, before, after));
        }
    }

    /** 原卡摘要（修订需求 prompt 用）；自 NovelOrchestrator 下沉 */
    public static String describeCard(Character c) {
        return "定位:" + c.getRole() + ";性格:" + c.getPersonality() + ";能力:" + c.getAbility()
                + ";动机:" + c.getMotivation() + ";外貌:" + c.getAppearance();
    }
}
