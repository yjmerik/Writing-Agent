package com.writing.novel.service;

import com.writing.novel.domain.ContinuityIssue;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * p11-q2-01: 章节质检委员会——汇总四检测器报告（不重写），供 Arbiter 统一仲裁（对应用户目标2）。
 * <p>
 * 替代 checkContinuityAfterWriting 的串行互斥模式：先各自出报告，再合并为 CouncilReport，
 * 让多维质量问题在一次重写里联合修正（q2-03）。本类为纯汇总层，不调用 LLM、不重写。
 */
@Component
public class QualityCouncil {

    /** 委员会汇总报告：全部问题 + 是否含 HIGH + 各维度评分 + 合并纠偏指令 */
    public record CouncilReport(List<ContinuityIssue> issues, boolean hasHigh,
                                Map<String, Integer> scores, String combinedFeedback) {}

    /**
     * 汇总四检测器结果。
     *
     * @param continuityIssues ContinuityChecker 发现
     * @param guardIssues      ConsistencyGuard 发现
     * @param reviewOverall    ReaderReview 总分（1-10）
     * @param reviewSuggestion ReaderReview 改进建议
     * @param styleScore       StyleGuardian 风格分（0-100，可空）
     */
    public CouncilReport collect(List<ContinuityIssue> continuityIssues,
                                 List<ContinuityIssue> guardIssues,
                                 int reviewOverall, String reviewSuggestion,
                                 Integer styleScore) {
        List<ContinuityIssue> all = new ArrayList<>();
        if (continuityIssues != null) all.addAll(continuityIssues);
        if (guardIssues != null) all.addAll(guardIssues);
        boolean hasHigh = all.stream()
                .anyMatch(i -> i.getSeverity() == ContinuityIssue.Severity.HIGH);
        Map<String, Integer> scores = new HashMap<>();
        scores.put("readerOverall", reviewOverall);
        if (styleScore != null) scores.put("style", styleScore);
        StringBuilder fb = new StringBuilder();
        all.stream()
                .filter(i -> i.getSeverity() == ContinuityIssue.Severity.HIGH)
                .forEach(i -> fb.append("- [必须修正] ").append(i.getDescription()).append("\n"));
        if (reviewOverall < 7 && reviewSuggestion != null && !reviewSuggestion.isBlank()) {
            fb.append("- [读者反馈] ").append(reviewSuggestion).append("\n");
        }
        return new CouncilReport(all, hasHigh, scores, fb.toString().trim());
    }
}
