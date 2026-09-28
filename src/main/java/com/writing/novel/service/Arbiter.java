package com.writing.novel.service;

import com.writing.novel.service.QualityCouncil.CouncilReport;
import org.springframework.stereotype.Component;

/**
 * p11-q2-02: 章节质检仲裁器——基于 QualityCouncil 报告决定章节动作（对应用户目标2）。
 * <p>
 * 解除现有"检测器互斥、第一次命中触发重写"的模式：由 Arbiter 统一调度重写预算，
 * 把多维问题在一次合并重写里联合修正（q2-03）。三分类根因仍由 RepairArbiter 处理（q0-04）。
 */
@Component
public class Arbiter {

    public enum Action { PASS, REWRITE, BLOCK }

    /** NCS-100: 风格一致性低于此阈值触发预算内重写（与 style-check 持久化口径一致） */
    public static final int STYLE_REWRITE_THRESHOLD = 60;

    public record Arbitration(Action action, String correctionBrief) {}

    /**
     * @param report          QualityCouncil 汇总报告
     * @param rewriteBudget   本章剩余重写预算（次）
     * @param alreadyRewritten 本章是否已重写过
     */
    public Arbitration arbitrate(CouncilReport report, int rewriteBudget, boolean alreadyRewritten) {
        if (report == null) return new Arbitration(Action.BLOCK, "缺少质量检查报告");
        // 重写后仍有 HIGH → 质量熔断信号（BLOCK，上层据此暂停/标 NEEDS_MANUAL_REVIEW）
        if (report.hasHigh() && alreadyRewritten) {
            return new Arbitration(Action.BLOCK, report.combinedFeedback());
        }
        // 有 HIGH 或读者评分 <7，且预算允许且未重写 → 合并重写（喂入全部问题）
        int readerOverall = report.scores().getOrDefault("readerOverall", 10);
        // NCS-100: 风格分 <60 同样触发预算内重写
        int styleScore = report.scores().getOrDefault("style", 100);
        boolean needRewrite = report.hasHigh() || readerOverall < 7
                || styleScore < STYLE_REWRITE_THRESHOLD;
        if (needRewrite && rewriteBudget > 0 && !alreadyRewritten) {
            return new Arbitration(Action.REWRITE, report.combinedFeedback());
        }
        if (needRewrite) return new Arbitration(Action.BLOCK, report.combinedFeedback());
        return new Arbitration(Action.PASS, "");
    }
}
