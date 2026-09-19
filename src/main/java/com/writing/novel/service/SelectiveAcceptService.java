package com.writing.novel.service;

import com.writing.novel.agent.CheckResultEnvelope;
import com.writing.novel.agent.ContinuityCheckerAgent;
import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.ContinuityIssue;
import com.writing.novel.domain.RevisionProposal;
import com.writing.novel.persistence.sqlite.CheckResultRepository;
import com.writing.novel.spi.NovelRepository;
import com.writing.novel.telemetry.EventLog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * NCS-024（方案 §13/R18/UX10）：选择性组合采纳。
 * 从多份候选选择段落片段组合成新候选：新内容新哈希、来源候选记录在案；
 * 组合候选必须重新检查（不继承原候选的任何通过状态），重检通过（无 HIGH 未决问题）
 * 才能采纳；采纳后原候选保持原状（仍可供比较）。
 */
public class SelectiveAcceptService {

    /** 一次片段选择：来源候选 + 段落序号（按换行分段） */
    public record Fragment(String previewId, List<Integer> paragraphs) {}

    public record Combined(String previewId, String content, List<String> sources) {}

    private static final String COMBINE_CHECKER = "combine-recheck";

    private final NovelRepository repository;
    private final RevisionRequestService requests;
    private final ContinuityCheckerAgent checker;
    private final CheckResultRepository checkResults;

    public SelectiveAcceptService(NovelRepository repository, RevisionRequestService requests,
                                  ContinuityCheckerAgent checker, CheckResultRepository checkResults) {
        this.repository = repository;
        this.requests = requests;
        this.checker = checker;
        this.checkResults = checkResults;
    }

    /** 组合片段为新候选（独立哈希与身份；来源记录；产生即需重检） */
    public Combined combine(String projectId, String requestId, int chapterIndex, List<Fragment> fragments) {
        requests.requireScopeApproved(projectId, requestId);
        Chapter chapter = repository.findChapterByIndex(projectId, chapterIndex)
                .orElseThrow(() -> new IllegalArgumentException("章节不存在: " + chapterIndex));
        StringBuilder combined = new StringBuilder();
        List<String> sources = new ArrayList<>();
        for (Fragment fragment : fragments) {
            RevisionProposal source = repository.findProposal(projectId, fragment.previewId())
                    .orElseThrow(() -> new IllegalArgumentException("来源候选不存在: " + fragment.previewId()));
            if (source.getChapterIndex() == null || source.getChapterIndex() != chapterIndex) {
                throw new IllegalArgumentException("来源候选不属于第 " + chapterIndex + " 章");
            }
            String[] paragraphs = contentOf(source).split("\n", -1);
            for (int index : fragment.paragraphs()) {
                if (index < 0 || index >= paragraphs.length) {
                    throw new IllegalArgumentException("段落序号越界: " + index);
                }
                if (combined.length() > 0) combined.append('\n');
                combined.append(paragraphs[index]);
            }
            sources.add(source.getId());
        }
        if (combined.length() == 0) {
            throw new IllegalArgumentException("组合内容为空——至少选择一个段落");
        }
        var identity = com.writing.novel.domain.ChapterApproval.Identity.of(chapter);
        String requestKey = "combine:" + requestId + ":" + UUID.randomUUID();
        RevisionProposal proposal = new RevisionProposal(UUID.randomUUID().toString(), projectId,
                RevisionProposal.TargetType.CHAPTER, chapter.getId(), chapterIndex, null,
                identity.version(), identity.contentHash(),
                "选择性组合（来源：" + String.join(",", sources) + "）", "{}",
                "{\"id\":\"" + chapter.getId() + "\",\"projectId\":\"" + projectId
                        + "\",\"chapterIndex\":" + chapterIndex + ",\"title\":"
                        + json(chapter.getTitle()) + ",\"content\":" + json(combined.toString()) + "}",
                "组合候选", RevisionProposal.Status.READY, requestKey, null,
                java.time.Instant.now(), java.time.Instant.now());
        repository.saveProposal(proposal);
        EventLog.emit("SELECTIVE_COMBINE_CREATED", Map.of(
                "projectId", projectId, "requestId", requestId,
                "chapterIndex", chapterIndex, "sources", sources.size()));
        return new Combined(proposal.getId(), combined.toString(), sources);
    }

    /** 组合候选强制重检（连续性检查；结果入信封） */
    public void recheck(String projectId, String previewId) {
        RevisionProposal proposal = requireCombined(projectId, previewId);
        String content = contentOf(proposal);
        var issues = checker.check(projectId, proposal.getChapterIndex(), content, "");
        boolean hasHigh = issues.stream()
                .anyMatch(i -> i.getSeverity() == ContinuityIssue.Severity.HIGH);
        if (!hasHigh) {
            checkResults.save(projectId, proposal.getChapterIndex(), COMBINE_CHECKER,
                    CheckResultEnvelope.Status.SUCCESS, "");
        }
        // HIGH 时 checker 的 check 已落 issue（信封 SUCCESS+问题并存）——needsRecheck 由高严重问题判定
    }

    /** 是否仍需（或不可）采纳：未重检或重检存在未决 HIGH */
    public boolean needsRecheck(String projectId, String previewId) {
        RevisionProposal proposal = requireCombined(projectId, previewId);
        boolean rechecked = checkResults.latestStatuses(projectId).stream()
                .anyMatch(c -> c.chapterIndex() == proposal.getChapterIndex()
                        && COMBINE_CHECKER.equals(c.checker()));
        if (!rechecked) return true;
        return repository.findUnresolvedIssues(projectId).stream()
                .anyMatch(i -> i.getDetectedAtChapter() == proposal.getChapterIndex()
                        && i.getSeverity() == ContinuityIssue.Severity.HIGH);
    }

    /** 采纳组合候选（前置：重检通过）；原候选不动 */
    public boolean accept(String projectId, String previewId) {
        RevisionProposal proposal = requireCombined(projectId, previewId);
        if (needsRecheck(projectId, previewId)) {
            throw new IllegalStateException("组合候选未完成重检或重检存在严重未决问题——不继承原候选通过状态");
        }
        Chapter chapter = repository.findChapterByIndex(projectId, proposal.getChapterIndex())
                .orElseThrow(() -> new IllegalStateException("章节不存在"));
        var currentIdentity = com.writing.novel.domain.ChapterApproval.Identity.of(chapter);
        if (currentIdentity.version() != proposal.getBaseVersion()
                || !currentIdentity.contentHash().equals(proposal.getSourceHash())) {
            repository.markProposalStale(projectId, proposal.getId());
            throw new IllegalStateException("章节已有新稿，组合候选过期（STALE）");
        }
        String content = contentOf(proposal);
        if (content.length() < 500 && chapter.getStatus() != Chapter.Status.DRAFTING) {
            throw new IllegalStateException("组合内容少于 500 字门槛");
        }
        var before = new com.writing.novel.domain.ChapterVersion(UUID.randomUUID().toString(), projectId,
                chapter.getChapterIndex(), chapter.getVersion(), chapter.getTitle(), chapter.getContent(),
                "SELECTIVE_COMBINE", java.time.Instant.now());
        chapter.setContent(content);
        if (chapter.getStatus() == Chapter.Status.DRAFTING && content.length() >= 500) {
            chapter.setStatus(Chapter.Status.DONE);
        }
        if (!repository.saveChapterRevision(before, chapter, proposal.getBaseVersion())) {
            throw new IllegalStateException("章节版本已变化");
        }
        // 消费组合候选（原候选不动）
        repository.markProposalStale(projectId, proposal.getId());
        EventLog.emit("SELECTIVE_COMBINE_ACCEPTED", Map.of(
                "projectId", projectId, "previewId", previewId,
                "chapterIndex", proposal.getChapterIndex()));
        return true;
    }

    private RevisionProposal requireCombined(String projectId, String previewId) {
        RevisionProposal proposal = repository.findProposal(projectId, previewId)
                .orElseThrow(() -> new IllegalArgumentException("候选不存在: " + previewId));
        if (proposal.getRequestKey() == null || !proposal.getRequestKey().startsWith("combine:")) {
            throw new IllegalArgumentException("不是组合候选: " + previewId);
        }
        return proposal;
    }

    private String contentOf(RevisionProposal proposal) {
        try {
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(proposal.getAfterJson());
            return node.path("content").asText("");
        } catch (Exception e) {
            throw new IllegalStateException("候选内容无效", e);
        }
    }

    private String json(String s) {
        return "\"" + (s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n")) + "\"";
    }
}
