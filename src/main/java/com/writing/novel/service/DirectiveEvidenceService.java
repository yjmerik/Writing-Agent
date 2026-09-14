package com.writing.novel.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.agent.DirectiveEvidenceAgent;
import com.writing.novel.domain.AuthorDirective;
import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.ChapterApproval;
import com.writing.novel.spi.NovelRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * p37-16：章节生成/修订完成后，落库作者要求的逐章核对证据。
 * 只做固定（pin）→核对→过期判定→仓储写入：
 * 先固定 chapter identity 与各意见 revision；核对结果回来时重读当前稿，
 * 来源已变化 → 只存 STALE 历史（绝不把旧结果落到新稿）；写入经 saveDirectiveEvidence
 * 幂等去重（失败可重试不重复）。无适用要求不调用额外模型。
 * 核对器失败 = 要求保持未验证（调用方决定是否吞掉并留事件）。
 */
@Service
public class DirectiveEvidenceService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final NovelRepository repository;
    private final DirectiveEvidenceAgent evidenceAgent;

    public DirectiveEvidenceService(NovelRepository repository, DirectiveEvidenceAgent evidenceAgent) {
        this.repository = repository;
        this.evidenceAgent = evidenceAgent;
    }

    public record CaptureResult(int chapterIndex, int chapterVersion, boolean stale, int evidenceCount) {
    }

    public CaptureResult captureEvidence(String projectId, int chapterIndex) {
        return captureEvidence(projectId, chapterIndex, null);
    }

    /** midStep 供测试注入"慢核对期间作者修改"。 */
    public CaptureResult captureEvidence(String projectId, int chapterIndex, Runnable midStep) {
        List<AuthorDirective> applicable = repository.findApplicableDirectives(projectId, chapterIndex);
        if (applicable.isEmpty()) {
            // 无适用要求：不读章节、不调用额外模型
            return new CaptureResult(chapterIndex, 0, false, 0);
        }
        Chapter pinned = repository.findChapterByIndex(projectId, chapterIndex)
                .orElseThrow(() -> new IllegalArgumentException("章节不存在: " + chapterIndex));
        ChapterApproval.Identity pinnedIdentity = ChapterApproval.Identity.of(pinned);
        List<KnownRef> pinnedDirectives = applicable.stream()
                .map(d -> new KnownRef(d.getId(), d.getRevision() == null ? 1 : d.getRevision()))
                .toList();
        String chapterJson;
        String directivesJson;
        try {
            chapterJson = MAPPER.writeValueAsString(java.util.Map.of(
                    "chapterIndex", chapterIndex,
                    "title", pinned.getTitle(),
                    "content", pinned.getContent()));
            directivesJson = MAPPER.writeValueAsString(applicable.stream()
                    .map(d -> java.util.Map.of(
                            "directiveId", d.getId(),
                            "revision", d.getRevision() == null ? 1 : d.getRevision(),
                            "kind", d.getKind().name(),
                            "fromChapter", d.getFromChapter() == null ? -1 : d.getFromChapter(),
                            "toChapter", d.getToChapter() == null ? -1 : d.getToChapter(),
                            "content", d.getContent()))
                    .collect(Collectors.toList()));
        } catch (Exception e) {
            throw new IllegalStateException("核对输入序列化失败", e);
        }

        if (midStep != null) midStep.run();

        List<AuthorDirective.Evidence> candidates = evidenceAgent.evaluate(
                projectId, chapterIndex, pinnedIdentity.version(), chapterJson, directivesJson);

        // 重读当前稿：来源已变化 → 旧结果只存 STALE 历史，绝不落到新稿
        Chapter current = repository.findChapterByIndex(projectId, chapterIndex)
                .orElseThrow(() -> new IllegalStateException("章节不存在"));
        ChapterApproval.Identity currentIdentity = ChapterApproval.Identity.of(current);
        boolean stale = !currentIdentity.equals(pinnedIdentity);

        int written = 0;
        for (AuthorDirective.Evidence candidate : candidates) {
            Integer pinnedRevision = pinnedDirectives.stream()
                    .filter(k -> k.id().equals(candidate.directiveId()))
                    .findFirst().map(KnownRef::revision).orElse(null);
            if (pinnedRevision == null) continue;
            AuthorDirective.EvidenceStatus status = stale
                    ? AuthorDirective.EvidenceStatus.STALE
                    : candidate.status();
            String reason = stale ? "核对完成前正文已变化，仅作历史留痕" : candidate.reason();
            AuthorDirective.Evidence row = new AuthorDirective.Evidence(
                    java.util.UUID.randomUUID().toString(),
                    candidate.directiveId(),
                    pinnedRevision,
                    chapterIndex,
                    pinnedIdentity.version(),
                    pinnedIdentity.contentHash(),
                    candidate.proposalId(),
                    stale ? candidate.quote() : candidate.quote(),
                    candidate.anchor(),
                    status,
                    reason);
            repository.saveDirectiveEvidence(projectId, row);
            written++;
        }
        return new CaptureResult(chapterIndex, pinnedIdentity.version(), stale, written);
    }

    private record KnownRef(String id, int revision) {
    }
}
