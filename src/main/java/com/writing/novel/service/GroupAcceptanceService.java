package com.writing.novel.service;

import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.RevisionProposal;
import com.writing.novel.spi.NovelRepository;
import com.writing.novel.telemetry.EventLog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * NCS-016（方案 §12/R14/F01）：依赖组整组接受与重基。
 *
 * <p>整组接受：全部候选的基准与当前一致才可接受；正文、版本、候选与请求回执
 * 在同一事务中提交，任一章失败整体回滚，不呈现半套已接受修复。基准漂移：候选标 STALE 保留
 * （可比较、可重基），请求不落入半完成。commandId 幂等：重复同一 commandId 重放同一收据，
 * 不再写正文。保护锚点：候选内容必须保留声明于候选章的原文 span。
 */
public class GroupAcceptanceService {

    public record Receipt(String commandId, boolean applied, boolean replayed,
                          List<Integer> chapters, String message) {}

    private final NovelRepository repository;
    private final RevisionRequestService requests;
    private final ChapterRevisionService chapterRevision;
    /** NCS-026: 运行经验记录；null 时不记录（旧装配/测试） */
    private final RevisionExperienceService experience;

    public GroupAcceptanceService(NovelRepository repository,
                                  RevisionRequestService requests,
                                  ChapterRevisionService chapterRevision) {
        this(repository, requests, chapterRevision, null);
    }

    public GroupAcceptanceService(NovelRepository repository,
                                  RevisionRequestService requests,
                                  ChapterRevisionService chapterRevision,
                                  RevisionExperienceService experience) {
        this.repository = repository;
        this.requests = requests;
        this.chapterRevision = chapterRevision;
        this.experience = experience;
    }

    public Receipt acceptGroup(String projectId, String requestId, String commandId) {
        if (commandId == null || commandId.isBlank()) {
            throw new IllegalArgumentException("commandId 不能为空（幂等键）");
        }
        var request = requests.get(projectId, requestId);
        List<RevisionProposal> group = repository.listProposals(projectId).stream()
                .filter(p -> p.getRequestKey() != null && p.getRequestKey().startsWith("rr:" + requestId + ":"))
                .toList();
        if (group.isEmpty()) {
            throw new IllegalStateException("修订请求没有候选（先运行修复生成）");
        }
        // 幂等重放：全部已 APPLIED 且请求已完成 → 同收据
        boolean allApplied = group.stream().allMatch(p -> p.getStatus() == RevisionProposal.Status.APPLIED);
        if (allApplied && request.getStatus() == com.writing.novel.domain.RevisionRequest.Status.COMPLETED
                && request.getAcceptance() != null && request.getAcceptance().contains(commandId)) {
            return new Receipt(commandId, true, true,
                    group.stream().map(RevisionProposal::getChapterIndex).toList(), "幂等重放：同 commandId 收据");
        }

        // ① 前置校验：基准一致 + 保护锚点（不写任何正文）
        List<RevisionProposal> ready = new ArrayList<>();
        for (RevisionProposal proposal : group) {
            if (proposal.getStatus() != RevisionProposal.Status.READY) {
                if (proposal.getStatus() == RevisionProposal.Status.APPLIED) continue;
                throw new IllegalStateException("候选状态不可接受: " + proposal.getStatus());
            }
            Chapter current = repository.findChapterByIndex(projectId, proposal.getChapterIndex())
                    .orElseThrow(() -> new IllegalStateException("候选章节不存在: " + proposal.getChapterIndex()));
            var identity = com.writing.novel.domain.ChapterApproval.Identity.of(current);
            if (identity.version() != proposal.getBaseVersion()
                    || !identity.contentHash().equals(proposal.getSourceHash())) {
                repository.markProposalStale(projectId, proposal.getId());
                EventLog.emit("GROUP_REVISION_REBASED", Map.of(
                        "projectId", projectId, "requestId", requestId,
                        "chapterIndex", proposal.getChapterIndex(), "commandId", commandId));
                throw new IllegalStateException("第 " + proposal.getChapterIndex()
                        + " 章已有新稿，候选保留为过期建议（STALE）——请基于新稿重新整理建议");
            }
            verifyProtectedSpans(projectId, request.getProtectAnchorsJson(), proposal, current.getContent());
            ready.add(proposal);
        }
        if (ready.isEmpty()) {
            throw new IllegalStateException("没有可接受的新候选（可能已全部应用）");
        }

        // ② 正文、版本、候选状态及请求回执在同一事务中落盘。
        var commit = repository.commitProposalGroup(projectId, requestId, commandId,
                ready.stream().map(RevisionProposal::getId).toList());
        List<Integer> chapters = commit.chapters();
        for (int chapter : chapters) chapterRevision.refreshAfterGroupCommit(projectId, chapter);
        // NCS-026: 接受样本入运行经验（同类后续修订可参考）
        if (experience != null) {
            String changeClass = chapters.size() > 1 ? "CROSS_CHAPTER" : "FACT_LOCAL";
            experience.recordAccepted(projectId, changeClass,
                    request.getIntent(),
                    "整组接受 " + chapters.size() + " 章修订（" + chapters + "）");
        }
        EventLog.emit("GROUP_REVISION_APPLIED", Map.of(
                "projectId", projectId, "requestId", requestId,
                "commandId", commandId, "chapters", chapters.size()));
        return new Receipt(commandId, true, commit.replayed(), chapters,
                "已采纳 " + chapters.size() + " 章修订");
    }

    /** 保护锚点核对：span 存在于候选章基准正文时，必须在候选正文中原样保留 */
    private void verifyProtectedSpans(String projectId, String protectAnchorsJson,
                                      RevisionProposal proposal, String beforeContent) {
        if (protectAnchorsJson == null || protectAnchorsJson.isBlank() || "[]".equals(protectAnchorsJson)) {
            return;
        }
        try {
            var arr = new com.fasterxml.jackson.databind.ObjectMapper().readTree(protectAnchorsJson);
            for (var anchor : arr) {
                int anchorChapter = anchor.path("chapterIndex").asInt(-1);
                String span = anchor.path("span").asText("");
                if (anchorChapter != proposal.getChapterIndex() || span.isBlank()) continue;
                if (beforeContent == null || !beforeContent.contains(span)) {
                    continue; // 锚点不适用于该章（基准正文本就不含此 span）
                }
                String afterContent = proposalContent(proposal);
                if (afterContent != null && !afterContent.contains(span)) {
                    throw new IllegalStateException("候选移除了保护锚点声明的原文（第 "
                            + anchorChapter + " 章「" + span + "」）——保护内容不得改动，整组拒绝");
                }
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            // 保护锚点 JSON 非法不阻塞接受（保守放行，记录事件）
            EventLog.emit("GROUP_REVISION_PROTECT_PARSE_FAILED", Map.of(
                    "projectId", projectId, "error", EventLog.truncate(e.getMessage(), 100)));
        }
    }

    private String proposalContent(RevisionProposal proposal) {
        try {
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(proposal.getAfterJson());
            return node.path("content").asText(null);
        } catch (Exception e) {
            return null;
        }
    }
}
