package com.writing.novel.service;

import com.writing.novel.domain.ContinuityIssue;
import com.writing.novel.domain.Foreshadowing;
import com.writing.novel.spi.NovelRepository;

import java.util.List;
import java.util.Optional;

/**
 * NCS-184: 回报预算器——按台账核销兑付（伏笔回收=确定性兑付事件），
 * 连续 N 章零兑付 → PLOT HIGH（进重写路由）。
 *
 * <p>兑付事件口径（确定性）：伏笔 resolvedAtChapter 落章；开篇豁免
 * （前 SETUP_CHAPTERS 章为建立期不计数）。读者评审的钩子摘要由
 * {@link #recentHooksSummary} 提供（前 N 章钩子/兑付台账注入盲评上下文）。
 */
public class PayoffLedger {

    /** 连续零兑付上限（章）——超过即 PLOT HIGH */
    private final int payoffGapLimit;
    /** 开篇建立期豁免章数 */
    private final int setupChapterExempt;
    /** 读者评审钩子摘要回看章数 */
    private final int hookSummaryWindow;

    private final NovelRepository repository;

    public PayoffLedger(NovelRepository repository) {
        this(repository, 6, 3, 3);
    }

    public PayoffLedger(NovelRepository repository, int payoffGapLimit,
                        int setupChapterExempt, int hookSummaryWindow) {
        this.repository = repository;
        this.payoffGapLimit = payoffGapLimit;
        this.setupChapterExempt = setupChapterExempt;
        this.hookSummaryWindow = hookSummaryWindow;
    }

    /**
     * 核销本章兑付并检查零兑付缺口。
     * @return PLOT HIGH issue（缺口超限且非开篇豁免期）；正常返回 empty
     */
    public Optional<ContinuityIssue> checkChapterPayoff(String projectId, int chapterIndex) {
        if (chapterIndex <= setupChapterExempt) return Optional.empty();
        int lastPayoff = lastPayoffChapterAtOrBefore(projectId, chapterIndex - 1);
        int gap = chapterIndex - Math.max(lastPayoff, 0);
        if (gap < payoffGapLimit) return Optional.empty();
        String description = "回报缺口：第 " + (lastPayoff + 1) + " 章起已连续 " + gap
                + " 章无任何兑付（伏笔回收/承诺兑现）——本章必须兑付至少一个在册钩子"
                + "（回收一条悬置伏笔或兑现一个已登记承诺），不得继续只埋不收。";
        return Optional.of(new ContinuityIssue(projectId, chapterIndex,
                ContinuityIssue.Type.PLOT, ContinuityIssue.Severity.HIGH, description));
    }

    /** 最近一次兑付章（≤upperBound；无则 0） */
    private int lastPayoffChapterAtOrBefore(String projectId, int upperBound) {
        return repository.findForeshadowingsByProject(projectId).stream()
                .filter(f -> f.getResolvedAtChapter() != null
                        && f.getResolvedAtChapter() > 0
                        && f.getResolvedAtChapter() <= upperBound)
                .mapToInt(Foreshadowing::getResolvedAtChapter)
                .max().orElse(0);
    }

    /** 在册悬置钩子（供重写指令与评审摘要列出） */
    public List<Foreshadowing> openHooks(String projectId) {
        return repository.findUnresolvedForeshadowings(projectId).stream()
                .filter(f -> f.getPriority() == Foreshadowing.Priority.HIGH || f.isMustResolve()
                        || f.getExpectedResolveChapter() != null)
                .toList();
    }

    /** 前 hookSummaryWindow 章的钩子/兑付摘要（注入读者评审上下文；无则空串） */
    public String recentHooksSummary(String projectId, int chapterIndex) {
        int from = Math.max(1, chapterIndex - hookSummaryWindow);
        StringBuilder sb = new StringBuilder();
        var all = repository.findForeshadowingsByProject(projectId);
        for (int i = from; i < chapterIndex; i++) {
            final int ch = i;
            var paid = all.stream().filter(f -> f.getResolvedAtChapter() != null
                    && f.getResolvedAtChapter() == ch).toList();
            var planted = all.stream().filter(f -> f.getPlantedAtChapter() == ch).toList();
            if (paid.isEmpty() && planted.isEmpty()) continue;
            sb.append("第 ").append(ch).append(" 章：");
            for (var f : paid) sb.append("[兑付] ").append(truncate(f.getDescription(), 40)).append(" ");
            for (var f : planted) sb.append("[埋钩] ").append(truncate(f.getDescription(), 40)).append(" ");
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
