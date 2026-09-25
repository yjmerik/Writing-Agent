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
import com.writing.novel.telemetry.EventContext;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
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
                                ObjectMapper objectMapper,
                                SupportWithdrawalService supportWithdrawal) {
        this.repository = repository;
        this.revisionAgent = revisionAgent;
        this.canonPatch = canonPatch;
        this.writingTaskExecutor = writingTaskExecutor;
        this.chapterRevision = new ChapterRevisionService(repository, writingTaskExecutor,
                orchestrator::refreshChapterAfterRevisionAsync, supportWithdrawal);
        this.objectMapper = objectMapper;
    }

    public RevisionPreview preview(String projectId, RevisionRequest request) {
        // NCS-086（听雪刀 P2-4）：让 Agent 修改的 LLM 调用携带项目上下文（成本归集前提）
        EventContext.setProjectId(projectId);
        try {
            return doPreview(projectId, request);
        } finally {
            EventContext.clear();
        }
    }

    private RevisionPreview doPreview(String projectId, RevisionRequest request) {
        requireProject(projectId);
        RevisionRequest valid = requireRequest(request);
        TargetSnapshot before = resolve(projectId, valid.targetType(), valid.targetId(),
                valid.chapterIndex(), valid.passageIndex());
        // p37-12: 注入适用作者要求（结构化编号行 [D-x revN]）
        var applicable = repository.findApplicableDirectives(projectId,
                valid.chapterIndex() != null ? valid.chapterIndex() : 0);
        String enrichedInstructions = valid.instructions().trim();
        if (!applicable.isEmpty()) {
            StringBuilder sb = new StringBuilder(enrichedInstructions).append("\n");
            for (int i = 0; i < applicable.size(); i++) {
                var d = applicable.get(i);
                int rev = d.getRevision() == null ? 1 : d.getRevision();
                sb.append("【作者要求-").append(i + 1).append("】[").append(d.getId())
                  .append(" rev").append(rev).append("] ").append(d.getContent()).append("\n");
            }
            enrichedInstructions = sb.toString();
        }
        RevisionCandidate candidate = revisionAgent.preview(
                valid.targetType(), json(before.value()), enrichedInstructions);
        ObjectNode after = candidateObject(candidate);
        validateCandidate(before, after);

        String previewId = UUID.randomUUID().toString();
        String sourceVersion = fingerprint(before.value());

        // p37-03: 模型延迟重读——生成期间来源被修改则候选过期（STALE）
        TargetSnapshot afterGen = resolve(projectId, valid.targetType(), valid.targetId(),
                valid.chapterIndex(), valid.passageIndex());
        boolean stale = !sourceVersion.equals(fingerprint(afterGen.value()));

        StoredPreview stored = new StoredPreview(previewId, projectId, before.address(),
                sourceVersion, before.value().deepCopy(), after.deepCopy(), candidate.summary());
        stored.stale = stale;
        stored.directiveResults = candidate.directiveResults();
        previews.put(previewId, stored);

        // p37-03: 持久候选——重启/恢复查询的权威存储
        ChapterApproval.Identity sourceIdentity = buildSourceIdentity(stored.address);
        persistProposal(stored, valid, stale, sourceIdentity);

        return stored.view(stale ? "STALE" : "READY", sourceIdentity);
    }

    /** p37-03: 章节类候选绑定 ChapterApproval.Identity；其他类型无结构化身份（sourceVersion 兼容别名）。 */
    private ChapterApproval.Identity buildSourceIdentity(TargetAddress address) {
        if ((address.targetType == TargetType.CHAPTER || address.targetType == TargetType.PASSAGE)
                && address.chapterIndex != null) {
            return repository.findChapterByIndex(address.projectId, address.chapterIndex)
                    .map(ChapterApproval.Identity::of).orElse(null);
        }
        return null;
    }

    private void persistProposal(StoredPreview stored, RevisionRequest request,
                                 boolean stale, ChapterApproval.Identity sourceIdentity) {
        if (repository.findProposal(stored.projectId, stored.previewId).isPresent()) return;
        Chapter chapter = (stored.address.targetType == TargetType.CHAPTER
                || stored.address.targetType == TargetType.PASSAGE)
                && stored.address.chapterIndex != null
                ? repository.findChapterByIndex(stored.projectId, stored.address.chapterIndex).orElse(null)
                : null;
        int baseVersion = chapter != null ? chapter.getVersion() : 0;
        String sourceHash = sourceIdentity != null ? sourceIdentity.contentHash() : stored.sourceVersion;
        RevisionProposal proposal = new RevisionProposal(
                stored.previewId, stored.projectId,
                RevisionProposal.TargetType.valueOf(stored.address.targetType.name()),
                stored.address.targetId,
                stored.address.chapterIndex,
                stored.address.passageIndex,
                baseVersion, sourceHash,
                request.instructions(), stored.before.toString(), stored.after.toString(), stored.summary,
                stale ? RevisionProposal.Status.STALE : RevisionProposal.Status.READY,
                request.requestKey() != null && !request.requestKey().isBlank()
                        ? request.requestKey() : stored.previewId,
                null,
                java.time.Instant.now(), java.time.Instant.now());
        repository.saveProposal(proposal);
    }

    /** p37-03: 持久候选列表（按 targetId 与 status 过滤）。 */
    public List<RevisionProposal> listProposals(String projectId, String targetId, String status) {
        return repository.listProposals(projectId).stream()
                .filter(p -> targetId == null || targetId.isBlank() || p.getTargetId().equals(targetId))
                .filter(p -> status == null || status.isBlank() || p.getStatus().name().equals(status))
                .toList();
    }

    /** p37-03: 持久候选详情。 */
    public java.util.Optional<RevisionProposal> findProposal(String projectId, String proposalId) {
        return repository.findProposal(projectId, proposalId);
    }

    /** p37-03: 取消持久候选（CAS READY→CANCELLED）。 */
    public RevisionProposal cancel(String projectId, String proposalId) {
        boolean cancelled = repository.cancelProposal(projectId, proposalId);
        if (!cancelled) {
            var proposal = repository.findProposal(projectId, proposalId)
                    .orElseThrow(() -> new IllegalArgumentException("修订候选不存在"));
            if (proposal.getStatus() == RevisionProposal.Status.CANCELLED) return proposal;
            throw new IllegalStateException("仅 READY 候选可取消: " + proposal.getStatus());
        }
        StoredPreview stored = previews.get(proposalId);
        if (stored != null) stored.consumed = true;
        return repository.findProposal(projectId, proposalId).orElseThrow();
    }

    public ApplyResult apply(String projectId, String previewId) {
        // NCS-086: 应用阶段（含派生刷新触发的 LLM 链）同样携带项目上下文
        EventContext.setProjectId(projectId);
        try {
            return doApply(projectId, previewId);
        } finally {
            EventContext.clear();
        }
    }

    private ApplyResult doApply(String projectId, String previewId) {
        if (previewId == null || previewId.isBlank()) {
            throw new IllegalArgumentException("previewId 不能为空");
        }

        // p37-03: 持久候选路径（CHAPTER/PASSAGE 走统一修订边界，权威来自 DB）
        var proposalOpt = repository.findProposal(projectId, previewId);
        if (proposalOpt.isPresent()) {
            RevisionProposal proposal = proposalOpt.get();
            if (proposal.getStatus() == RevisionProposal.Status.CANCELLED) {
                throw new IllegalStateException("修订候选已取消");
            }
            if (proposal.getStatus() == RevisionProposal.Status.STALE) {
                throw new IllegalStateException("来源已变化，候选已过期（STALE）");
            }
            if (proposal.getTargetType() == RevisionProposal.TargetType.CHAPTER
                    || proposal.getTargetType() == RevisionProposal.TargetType.PASSAGE) {
                ChapterRevisionService.ProposalApplyResult result =
                        chapterRevision.applyProposalInOwnedWork(projectId, previewId);
                StoredPreview mem = previews.get(previewId);
                if (mem != null) mem.consumed = true;
                Chapter applied = result.chapter();
                ChapterApproval.Identity identity = ChapterApproval.Identity.of(applied);
                // p37-12: 应用后落库意见落实证据
                if (repository.findApplicableDirectives(projectId,
                        applied.getChapterIndex()).size() > 0) {
                    // Save evidence from candidate's directiveResults
                    var storedPreview = previews.get(previewId);
                    if (storedPreview != null && storedPreview.directiveResults != null) {
                        for (var dr : storedPreview.directiveResults) {
                            var evidence = new com.writing.novel.domain.AuthorDirective.Evidence(
                                    java.util.UUID.randomUUID().toString(),
                                    dr.directiveId(), dr.directiveRevision(),
                                    applied.getChapterIndex(), identity.version(), identity.contentHash(),
                                    null, dr.quote(), dr.anchor(),
                                    "APPLIED_SYSTEM".equals(dr.status())
                                            ? com.writing.novel.domain.AuthorDirective.EvidenceStatus.APPLIED_SYSTEM
                                            : com.writing.novel.domain.AuthorDirective.EvidenceStatus.UNMET,
                                    dr.reason());
                            repository.saveDirectiveEvidence(projectId, evidence);
                        }
                    }
                }
                return new ApplyResult(previewId, TargetType.valueOf(proposal.getTargetType().name()),
                        proposal.getTargetId(), identity.contentHash(), true, applied,
                        applied.isDerivedRefreshPending(), result.commit().replayed(), identity);
            }
        }

        // 非章节类（WORLD/CHARACTER/VOLUME）：保持现有语义
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
                    stored.address.targetId, stored.sourceVersion, true, result, refreshPending,
                    false, null);
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
                                  String instructions, String requestKey) {}

    /** p37-03: sourceVersion 为兼容别名（哈希字符串），sourceIdentity 为结构化身份。 */
    public record RevisionPreview(String previewId, TargetType targetType, String targetId,
                                  String sourceVersion, JsonNode before, JsonNode after,
                                  String summary, String status,
                                  ChapterApproval.Identity sourceIdentity) {}

    /** p32-01: derivedRefreshPending=true 表示修改已保存，但派生数据（摘要/索引/收割）刷新未完成。 */
    public record ApplyResult(String previewId, TargetType targetType, String targetId,
                              String sourceVersion, boolean applied, Object result,
                              boolean derivedRefreshPending, boolean replayed,
                              ChapterApproval.Identity sourceIdentity) {}

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
        private boolean stale;
        private List<ContentRevisionAgent.DirectiveResult> directiveResults;

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
            return view(stale ? "STALE" : "READY", null);
        }

        private RevisionPreview view(String status, ChapterApproval.Identity sourceIdentity) {
            return new RevisionPreview(previewId, address.targetType, address.targetId,
                    sourceVersion, before.deepCopy(), after.deepCopy(), summary, status, sourceIdentity);
        }
    }
}
