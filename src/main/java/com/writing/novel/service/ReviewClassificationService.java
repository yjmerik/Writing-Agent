package com.writing.novel.service;

import com.writing.novel.agent.CheckResultEnvelope;
import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.ContinuityIssue;
import com.writing.novel.spi.NovelRepository;
import com.writing.novel.persistence.sqlite.CheckResultRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NCS-023（方案 §13/UX09）：三类信息分层——必须处理 / 尚未查清 / 文学建议。
 * 独立呈现（文字+图标不只靠颜色）；问题卡含证据与检查版本；
 * 无证据的快捷"已解决"不存在（复核后关闭走既有 recheck）。
 */
public class ReviewClassificationService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ReviewClassificationService.class);

    private final NovelRepository repository;
    private final CheckResultRepository checkResults;

    public ReviewClassificationService(NovelRepository repository, CheckResultRepository checkResults) {
        this.repository = repository;
        this.checkResults = checkResults;
    }

    public Map<String, Object> classify(String projectId) {
        Map<Integer, Integer> currentVersions = new HashMap<>();
        for (Chapter c : repository.findChaptersByProject(projectId)) {
            currentVersions.put(c.getChapterIndex(), c.getVersion());
        }
        // NCS-148：台账已裁决的 issueId 集合——卡片打 adjudicated 标记供前端分层计数
        java.util.Set<String> adjudicated;
        try {
            adjudicated = repository.findAdjudicatedIssueIds(projectId);
        } catch (RuntimeException e) {
            // NCS-159: 降级为空集（分层计数缺已裁决标记），但必须留下失败痕迹
            log.warn("裁决台账查询失败，已裁决标记降级为空: projectId={}, err={}",
                    projectId, e.getMessage());
            adjudicated = java.util.Set.of();
        }

        List<Map<String, Object>> mustFix = new ArrayList<>();
        List<Map<String, Object>> unknowns = new ArrayList<>();
        List<Map<String, Object>> suggestions = new ArrayList<>();

        for (ContinuityIssue issue : repository.findUnresolvedIssues(projectId)) {
            Map<String, Object> card = issueCard(issue, currentVersions,
                    adjudicated.contains(issue.getId()));
            switch (issue.getSeverity()) {
                case HIGH, MEDIUM -> mustFix.add(card);
                case LOW -> suggestions.add(card);
            }
        }
        for (CheckResultRepository.LatestCheck check : checkResults.latestStatuses(projectId)) {
            if (check.status() != CheckResultEnvelope.Status.UNKNOWN
                    && check.status() != CheckResultEnvelope.Status.INVALID) {
                continue;
            }
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("kind", "UNKNOWN_CHECK");
            card.put("chapterIndex", check.chapterIndex());
            card.put("checker", check.checker());
            card.put("reason", check.reason());
            card.put("checkVersion", check.createdAt());
            card.put("description", "第 " + check.chapterIndex() + " 章的 "
                    + checkerLabel(check.checker()) + "未能完成（"
                    + (check.status() == CheckResultEnvelope.Status.UNKNOWN ? "未知" : "输出不可解析")
                    + "）——不能当作无问题");
            unknowns.add(card);
        }
        for (var barrier : repository.findActiveReviewBarriers(projectId)) {
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("kind", "REVIEW_BARRIER");
            card.put("chapterIndex", barrier.getChapterIndex());
            card.put("description", "第 " + barrier.getChapterIndex() + " 章待人工复核："
                    + (barrier.getReason() == null ? "" : barrier.getReason()));
            mustFix.add(card);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("projectId", projectId);
        result.put("mustFix", mustFix);
        result.put("unknown", unknowns);
        result.put("suggestions", suggestions);
        return result;
    }

    private Map<String, Object> issueCard(ContinuityIssue issue, Map<Integer, Integer> currentVersions,
                                          boolean adjudicated) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("kind", "ISSUE");
        card.put("issueId", issue.getId());
        card.put("chapterIndex", issue.getDetectedAtChapter());
        card.put("severity", issue.getSeverity().name());
        card.put("type", issue.getType().name());
        card.put("description", issue.getDescription());
        Integer bound = issue.getChapterVersion();
        card.put("chapterVersion", bound);
        Integer current = currentVersions.get(issue.getDetectedAtChapter());
        card.put("stale", bound != null && current != null && bound < current);
        card.put("adjudicated", adjudicated);
        return card;
    }

    private String checkerLabel(String checker) {
        return switch (checker) {
            case "continuity" -> "连续性检查";
            case "character-decision" -> "决策提取";
            default -> checker;
        };
    }
}
