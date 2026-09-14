package com.writing.novel.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.writing.novel.agent.ContentRevisionAgent;
import com.writing.novel.agent.ContentRevisionAgent.RevisionCandidate;
import com.writing.novel.agent.ContentRevisionAgent.TargetType;
import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.ChapterApproval;
import com.writing.novel.domain.RevisionProposal;
import com.writing.novel.domain.ChapterSynopsis;
import com.writing.novel.domain.Character;
import com.writing.novel.domain.KnowledgeEntry;
import com.writing.novel.domain.Outline;
import com.writing.novel.domain.Volume;
import com.writing.novel.orchestrator.NovelOrchestrator;
import com.writing.novel.spi.NovelRepository;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-held preview/apply boundary for scoped Agent revisions.
 * Preview is read-only; apply re-resolves the selected target and rejects stale,
 * cross-project, active-writing, or already-consumed previews before persistence.
 */
@Component
public class AgentRevisionService {

    private final NovelRepository repository;
    private final ContentRevisionAgent revisionAgent;
    private final CanonPatchService canonPatch;
    private final WritingTaskExecutor writingTaskExecutor;
    private final ChapterRevisionService chapterRevision;
    private final ObjectMapper objectMapper;
    private final Map<String, StoredPreview> previews = new ConcurrentHashMap<>();

    public AgentRevisionService(NovelRepository repository,
                                ContentRevisionAgent revisionAgent,
                                CanonPatchService canonPatch,
                                WritingTaskExecutor writingTaskExecutor,
                                NovelOrchestrator orchestrator,
                                ObjectMapper objectMapper) {
        this.repository = repository;
        this.revisionAgent = revisionAgent;
        this.canonPatch = canonPatch;
        this.writingTaskExecutor = writingTaskExecutor;
        this.chapterRevision = new ChapterRevisionService(repository, writingTaskExecutor,
                orchestrator::refreshChapterAfterRevisionAsync);
        this.objectMapper = objectMapper;
    }

    public RevisionPreview preview(String projectId, RevisionRequest request) {
        requireProject(projectId);
        RevisionRequest valid = requireRequest(request);
        TargetSnapshot before = resolve(projectId, valid.targetType(), valid.targetId(),
                valid.chapterIndex(), valid.passageIndex());
        RevisionCandidate candidate = revisionAgent.preview(
                valid.targetType(), json(before.value()), valid.instructions().trim());
        ObjectNode after = candidateObject(candidate);
        validateCandidate(before, after);

        String previewId = UUID.randomUUID().toString();
        String sourceVersion = fingerprint(before.value());
        StoredPreview stored = new StoredPreview(previewId, projectId, before.address(),
                sourceVersion, before.value().deepCopy(), after.deepCopy(), candidate.summary());
        previews.put(previewId, stored);
        return stored.view();
    }

    public ApplyResult apply(String projectId, String previewId) {
        if (previewId == null || previewId.isBlank()) {
            throw new IllegalArgumentException("previewId 不能为空");
        }
        StoredPreview stored = previews.get(previewId);
        if (stored == null) throw new IllegalArgumentException("修订预览不存在");

        synchronized (stored) {
            if (!stored.projectId.equals(projectId)) {
                throw new IllegalArgumentException("修订预览不属于该项目");
            }
            if (stored.consumed) throw new IllegalStateException("修订预览已应用");
            if (writingTaskExecutor.hasActiveTask(projectId)) {
                throw new IllegalStateException("请先暂停写作再应用修订");
            }

            TargetSnapshot current = resolve(projectId, stored.address.targetType,
                    stored.address.targetId, stored.address.chapterIndex, stored.address.passageIndex);
            if (!stored.sourceVersion.equals(fingerprint(current.value()))) {
                throw new IllegalStateException("来源内容已变化，请重新生成预览");
            }
            validateCandidate(current, stored.after);
            Object result = applyCandidate(current, stored.after);
            stored.consumed = true;
            boolean refreshPending = result instanceof Chapter chapter && chapter.isDerivedRefreshPending();
            return new ApplyResult(stored.previewId, stored.address.targetType,
                    stored.address.targetId, stored.sourceVersion, true, result, refreshPending);
        }
    }

    private Object applyCandidate(TargetSnapshot current, ObjectNode candidate) {
        return switch (current.address.targetType) {
            case WORLD -> applyWorld(current, candidate);
            case CHARACTER -> applyCharacter(current, candidate);
            case VOLUME -> applyVolume(current, candidate);
            // p37-14: 章节类候选走统一修订边界（持久候选 + 原子提交），不直接写正文
            case CHAPTER, PASSAGE -> applyChapterViaProposal(current, candidate);
        };
    }

    /** p37-14: 把已核验的候选落盘为持久修订候选，经 ChapterRevision 边界原子应用。 */
    private Object applyChapterViaProposal(TargetSnapshot current, ObjectNode candidate) {
        Chapter chapter = repository.findChapterByIndex(current.address.projectId,
                        current.address.chapterIndex)
                .orElseThrow(() -> new IllegalArgumentException("章节不存在: " + current.address.chapterIndex));
        ChapterApproval.Identity sourceIdentity = ChapterApproval.Identity.of(chapter);
        boolean isChapter = current.address.targetType == TargetType.CHAPTER;
        RevisionProposal proposal = new RevisionProposal(UUID.randomUUID().toString(),
                current.address.projectId,
                isChapter ? RevisionProposal.TargetType.CHAPTER : RevisionProposal.TargetType.PASSAGE,
                chapter.getId(), chapter.getChapterIndex(),
                isChapter ? null : current.address.passageIndex,
                sourceIdentity.version(), sourceIdentity.contentHash(),
                null, current.value().toString(), candidate.deepCopy().toString(), null,
                RevisionProposal.Status.READY, "preview-" + UUID.randomUUID(), null,
                java.time.Instant.now(), java.time.Instant.now());
        repository.saveProposal(proposal);
        ChapterRevisionService.ProposalApplyResult result =
                chapterRevision.applyProposalInOwnedWork(current.address.projectId, proposal.getId());
        return result.chapter();
    }

    private Object applyWorld(TargetSnapshot current, ObjectNode candidate) {
        KnowledgeEntry patch = new KnowledgeEntry(current.address.projectId,
                requiredText(candidate, "type"), requiredText(candidate, "name"),
                requiredText(candidate, "content"));
        return canonPatch.patchWorld(current.address.projectId, current.address.targetId,
                patch, canonPatch.currentChapter(current.address.projectId)).entity();
    }

    private Object applyCharacter(TargetSnapshot current, ObjectNode candidate) {
        Character patch = new Character(current.address.projectId,
                requiredText(candidate, "name"), requiredText(candidate, "role"));
        setIfPresent(candidate, "personality", patch::setPersonality);
        setIfPresent(candidate, "ability", patch::setAbility);
        setIfPresent(candidate, "motivation", patch::setMotivation);
        setIfPresent(candidate, "appearance", patch::setAppearance);
        setIfPresent(candidate, "relationships", patch::setRelationships);
        setIfPresent(candidate, "growthArc", patch::setGrowthArc);
        setIfPresent(candidate, "speakingStyle", patch::setSpeakingStyle);
        return canonPatch.patchCharacter(current.address.projectId, current.address.targetId,
                patch, canonPatch.currentChapter(current.address.projectId)).entity();
    }

    private Object applyVolume(TargetSnapshot current, ObjectNode candidate) {
        Outline existing = repository.findOutlineByProject(current.address.projectId)
                .orElseThrow(() -> new IllegalArgumentException("大纲不存在: " + current.address.projectId));
        Volume selected = existing.getVolumes().stream()
                .filter(volume -> volume.getId().equals(current.address.targetId))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("卷不存在: " + current.address.targetId));
        int writtenBoundary = canonPatch.currentChapter(current.address.projectId);
        if (selected.getSynopses().stream().anyMatch(s -> s.getChapterIndex() <= writtenBoundary)) {
            throw new IllegalStateException("已写章节所属卷不可由 Agent 修改");
        }
        Outline patch = new Outline(current.address.projectId, null);
        patch.addVolume(new Volume(current.address.projectId, selected.getVolumeIndex(),
                requiredText(candidate, "title"), requiredText(candidate, "summary")));
        return canonPatch.patchOutline(current.address.projectId, patch, writtenBoundary).entity();
    }

    private TargetSnapshot resolve(String projectId, TargetType targetType, String targetId,
                                   Integer chapterIndex, Integer passageIndex) {
        if (targetType == null) throw new IllegalArgumentException("targetType 不能为空");
        if (targetId == null || targetId.isBlank()) throw new IllegalArgumentException("targetId 不能为空");
        TargetAddress address = new TargetAddress(projectId, targetType, targetId,
                chapterIndex, passageIndex);
        ObjectNode value = switch (targetType) {
            case WORLD -> worldSnapshot(projectId, targetId);
            case CHARACTER -> characterSnapshot(projectId, targetId);
            case VOLUME -> volumeSnapshot(projectId, targetId);
            case CHAPTER -> chapterSnapshot(address, false);
            case PASSAGE -> chapterSnapshot(address, true);
        };
        return new TargetSnapshot(address, value);
    }

    private ObjectNode worldSnapshot(String projectId, String targetId) {
        KnowledgeEntry entry = repository.findKnowledgeEntry(targetId)
                .orElseThrow(() -> new IllegalArgumentException("世界观条目不存在: " + targetId));
        requireOwner(projectId, entry.getProjectId(), "世界观条目");
        return objectMapper.createObjectNode()
                .put("id", entry.getId()).put("projectId", entry.getProjectId())
                .put("type", entry.getType()).put("name", entry.getName())
                .put("content", entry.getContent());
    }

    private ObjectNode characterSnapshot(String projectId, String targetId) {
        Character character = repository.findCharacter(targetId)
                .orElseThrow(() -> new IllegalArgumentException("人物不存在: " + targetId));
        requireOwner(projectId, character.getProjectId(), "人物");
        ObjectNode node = objectMapper.createObjectNode()
                .put("id", character.getId()).put("projectId", character.getProjectId())
                .put("name", character.getName()).put("role", character.getRole());
        putNullable(node, "personality", character.getPersonality());
        putNullable(node, "ability", character.getAbility());
        putNullable(node, "motivation", character.getMotivation());
        putNullable(node, "appearance", character.getAppearance());
        putNullable(node, "relationships", character.getRelationships());
        putNullable(node, "growthArc", character.getGrowthArc());
        putNullable(node, "speakingStyle", character.getSpeakingStyle());
        return node;
    }

    private ObjectNode volumeSnapshot(String projectId, String targetId) {
        Outline outline = repository.findOutlineByProject(projectId)
                .orElseThrow(() -> new IllegalArgumentException("大纲不存在: " + projectId));
        Volume volume = outline.getVolumes().stream().filter(v -> targetId.equals(v.getId()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("卷不存在: " + targetId));
        requireOwner(projectId, volume.getProjectId(), "卷");
        return volumeNode(volume);
    }

    private ObjectNode chapterSnapshot(TargetAddress address, boolean passageOnly) {
        if (address.chapterIndex == null || address.chapterIndex <= 0) {
            throw new IllegalArgumentException("chapterIndex 必须为正数");
        }
        Chapter chapter = repository.findChapterByIndex(address.projectId, address.chapterIndex)
                .orElseThrow(() -> new IllegalArgumentException("章节不存在: " + address.chapterIndex));
        requireOwner(address.projectId, chapter.getProjectId(), "章节");
        if (!address.targetId.equals(chapter.getId())) {
            throw new IllegalArgumentException("章节目标身份不匹配");
        }
        if (!passageOnly) {
            return objectMapper.createObjectNode()
                    .put("id", chapter.getId()).put("projectId", chapter.getProjectId())
                    .put("chapterIndex", chapter.getChapterIndex()).put("title", chapter.getTitle())
                    .put("content", chapter.getContent()).put("status", chapter.getStatus().name())
                    .put("version", chapter.getVersion());
        }
        if (address.passageIndex == null || address.passageIndex < 0) {
            throw new IllegalArgumentException("passageIndex 不能为负数");
        }
        String[] passages = chapter.getContent().split("\\n", -1);
        if (address.passageIndex >= passages.length) {
            throw new IllegalArgumentException("段落不存在: " + address.passageIndex);
        }
        return objectMapper.createObjectNode()
                .put("id", chapter.getId()).put("projectId", chapter.getProjectId())
                .put("chapterIndex", chapter.getChapterIndex())
                .put("passageIndex", address.passageIndex)
                .put("content", passages[address.passageIndex])
                .put("version", chapter.getVersion());
    }

    private void validateCandidate(TargetSnapshot current, ObjectNode candidate) {
        TargetAddress address = current.address;
        if (!address.targetId.equals(requiredText(candidate, "id"))) {
            throw new IllegalStateException("Agent 修改了目标身份");
        }
        if (!address.projectId.equals(requiredText(candidate, "projectId"))) {
            throw new IllegalStateException("Agent 修改了项目身份");
        }
        switch (address.targetType) {
            case WORLD -> {
                requiredText(candidate, "type");
                requiredText(candidate, "name");
                requiredText(candidate, "content");
            }
            case CHARACTER -> {
                requiredText(candidate, "name");
                requiredText(candidate, "role");
            }
            case VOLUME -> validateVolumeIdentity(current.value, candidate);
            case CHAPTER -> {
                requireIndex(candidate, "chapterIndex", address.chapterIndex);
                requiredText(candidate, "title");
                requiredText(candidate, "content");
            }
            case PASSAGE -> {
                requireIndex(candidate, "chapterIndex", address.chapterIndex);
                requireIndex(candidate, "passageIndex", address.passageIndex);
                requiredText(candidate, "content");
            }
        }
    }

    private void validateVolumeIdentity(ObjectNode before, ObjectNode candidate) {
        requireIndex(candidate, "volumeIndex", before.path("volumeIndex").asInt());
        requiredText(candidate, "title");
        requiredText(candidate, "summary");
        JsonNode beforeSynopses = before.path("synopses");
        JsonNode afterSynopses = candidate.path("synopses");
        if (!beforeSynopses.isArray() || !afterSynopses.isArray()
                || beforeSynopses.size() != afterSynopses.size()) {
            throw new IllegalStateException("Agent 修改了卷内章节结构");
        }
        for (int i = 0; i < beforeSynopses.size(); i++) {
            JsonNode oldSynopsis = beforeSynopses.get(i);
            JsonNode newSynopsis = afterSynopses.get(i);
            if (!Objects.equals(oldSynopsis.path("id").asText(), newSynopsis.path("id").asText())
                    || oldSynopsis.path("chapterIndex").asInt() != newSynopsis.path("chapterIndex").asInt()
                    || !Objects.equals(oldSynopsis.path("projectId").asText(),
                    newSynopsis.path("projectId").asText())) {
                throw new IllegalStateException("Agent 修改了卷内章节身份");
            }
        }
        if (!beforeSynopses.equals(afterSynopses)) {
            throw new IllegalStateException("卷修订不能修改章级内容");
        }
    }

    private ObjectNode volumeNode(Volume volume) {
        ObjectNode node = objectMapper.createObjectNode()
                .put("id", volume.getId()).put("projectId", volume.getProjectId())
                .put("volumeIndex", volume.getVolumeIndex()).put("title", volume.getTitle())
                .put("summary", volume.getSummary());
        ArrayNode synopses = node.putArray("synopses");
        for (ChapterSynopsis synopsis : volume.getSynopses()) {
            ObjectNode item = synopses.addObject()
                    .put("id", synopsis.getId()).put("projectId", synopsis.getProjectId())
                    .put("chapterIndex", synopsis.getChapterIndex()).put("title", synopsis.getTitle())
                    .put("synopsis", synopsis.getSynopsis()).put("tensionLevel", synopsis.getTensionLevel());
            putNullable(item, "writingNotes", synopsis.getWritingNotes());
            putNullable(item, "beats", synopsis.getBeats());
            putNullable(item, "climax", synopsis.getClimax());
            putNullable(item, "cast", synopsis.getCast());
            putNullable(item, "plotPurpose", synopsis.getPlotPurpose());
            putNullable(item, "volumeArcPosition", synopsis.getVolumeArcPosition());
            putNullable(item, "foreshadowingsToPlant", synopsis.getForeshadowingsToPlant());
            putNullable(item, "foreshadowingsToResolve", synopsis.getForeshadowingsToResolve());
        }
        return node;
    }

    private RevisionRequest requireRequest(RevisionRequest request) {
        if (request == null) throw new IllegalArgumentException("修订请求不能为空");
        if (request.instructions == null || request.instructions.isBlank()) {
            throw new IllegalArgumentException("instructions 不能为空");
        }
        return request;
    }

    private void requireProject(String projectId) {
        if (repository.findProject(projectId).isEmpty()) {
            throw new IllegalArgumentException("项目不存在: " + projectId);
        }
    }

    private void requireOwner(String expected, String actual, String label) {
        if (!Objects.equals(expected, actual)) {
            throw new IllegalArgumentException(label + "不属于该项目");
        }
    }

    private ObjectNode candidateObject(RevisionCandidate candidate) {
        try {
            JsonNode node = objectMapper.readTree(candidate.structuredJson());
            if (!(node instanceof ObjectNode object)) {
                throw new IllegalStateException("Agent 候选必须是 JSON 对象");
            }
            return object;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Agent 候选不是有效 JSON", e);
        }
    }

    private String json(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("目标内容无法序列化", e);
        }
    }

    private String fingerprint(JsonNode node) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(json(node).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalStateException(field + " 不能为空");
        }
        return value.asText();
    }

    private static void setIfPresent(JsonNode node, String field,
                                     java.util.function.Consumer<String> setter) {
        JsonNode value = node.get(field);
        if (value != null && !value.isNull()) setter.accept(value.asText());
    }

    private static void putNullable(ObjectNode node, String field, String value) {
        if (value == null) node.putNull(field); else node.put(field, value);
    }

    private static void requireIndex(JsonNode node, String field, Integer expected) {
        if (expected == null || !node.path(field).canConvertToInt()
                || node.path(field).asInt() != expected) {
            throw new IllegalStateException("Agent 修改了 " + field);
        }
    }

    public record RevisionRequest(TargetType targetType, String targetId,
                                  Integer chapterIndex, Integer passageIndex,
                                  String instructions) {}

    public record RevisionPreview(String previewId, TargetType targetType, String targetId,
                                  String sourceVersion, JsonNode before, JsonNode after,
                                  String summary) {}

    /** p32-01: derivedRefreshPending=true 表示修改已保存，但派生数据（摘要/索引/收割）刷新未完成。 */
    public record ApplyResult(String previewId, TargetType targetType, String targetId,
                              String sourceVersion, boolean applied, Object result,
                              boolean derivedRefreshPending) {}

    private record TargetAddress(String projectId, TargetType targetType, String targetId,
                                 Integer chapterIndex, Integer passageIndex) {}

    private record TargetSnapshot(TargetAddress address, ObjectNode value) {}

    private static final class StoredPreview {
        private final String previewId;
        private final String projectId;
        private final TargetAddress address;
        private final String sourceVersion;
        private final ObjectNode before;
        private final ObjectNode after;
        private final String summary;
        private boolean consumed;

        private StoredPreview(String previewId, String projectId, TargetAddress address,
                              String sourceVersion, ObjectNode before, ObjectNode after,
                              String summary) {
            this.previewId = previewId;
            this.projectId = projectId;
            this.address = address;
            this.sourceVersion = sourceVersion;
            this.before = before;
            this.after = after;
            this.summary = summary;
        }

        private RevisionPreview view() {
            return new RevisionPreview(previewId, address.targetType, address.targetId,
                    sourceVersion, before.deepCopy(), after.deepCopy(), summary);
        }
    }
}
