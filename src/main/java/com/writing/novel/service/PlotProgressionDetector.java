package com.writing.novel.service;

import com.writing.novel.domain.ContinuityIssue;
import com.writing.novel.spi.NovelRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 剧情推进检测器——基于章节张力等级检测停滞。
 *
 * 连续 N 章 tension < threshold 时触发 PLOT 类型 ContinuityIssue。
 * 这是轻量级算法检测（不调用 LLM），用于写作循环中实时监控。
 */
public class PlotProgressionDetector {

    private static final int DEFAULT_STALL_WINDOW = 3;       // 默认连续多少章低张力视为停滞
    private static final int LOW_TENSION_THRESHOLD = 3;      // 张力低于此值视为"低"
    private static final int HIGH_TENSION_THRESHOLD = 8;     // 高于此值视为"高潮"

    private final NovelRepository repository;

    public PlotProgressionDetector(NovelRepository repository) {
        this.repository = repository;
    }

    /**
     * 检测最近章节是否有停滞迹象。
     * 返回 Optional<ContinuityIssue>，空表示正常。
     */
    public Optional<ContinuityIssue> detectStall(String projectId, int currentChapter) {
        var outline = repository.findOutlineByProject(projectId);
        if (outline.isEmpty()) return Optional.empty();

        // p7-4: 从 WritingPreferences 读取阈值（可调）
        int stallWindow = repository.findWritingPreferences(projectId)
                .map(p -> p.getPlotStallWindow())
                .orElse(DEFAULT_STALL_WINDOW);

        // 收集最近 stallWindow 章的张力
        List<Integer> recentTensions = new ArrayList<>();
        List<String> recentPurposes = new ArrayList<>();
        for (var v : outline.get().getVolumes()) {
            for (var s : v.getSynopses()) {
                if (s.getChapterIndex() > currentChapter - stallWindow
                        && s.getChapterIndex() <= currentChapter) {
                    recentTensions.add(s.getTensionLevel());
                    recentPurposes.add(s.getPlotPurpose());
                }
            }
        }

        if (recentTensions.size() < stallWindow) return Optional.empty();

        // 检测 1：连续低张力
        boolean allLow = recentTensions.stream().allMatch(t -> t < LOW_TENSION_THRESHOLD);
        if (allLow && currentChapter > stallWindow) {
            return Optional.of(new ContinuityIssue(
                    projectId, currentChapter,
                    ContinuityIssue.Type.PLOT,
                    ContinuityIssue.Severity.MEDIUM,
                    "连续" + stallWindow + "章张力过低（" + recentTensions + "），剧情可能停滞。" +
                    "建议下一章提升冲突强度或推进核心剧情线。"));
        }

        // 检测 2：连续无剧情目的
        boolean noPurpose = recentPurposes.stream()
                .allMatch(p -> p == null || p.isBlank());
        if (noPurpose && currentChapter > stallWindow) {
            return Optional.of(new ContinuityIssue(
                    projectId, currentChapter,
                    ContinuityIssue.Type.PLOT,
                    ContinuityIssue.Severity.LOW,
                    "连续" + stallWindow + "章未明确剧情目的，可能偏离主线。"));
        }

        // 检测 3：高潮疲劳——连续太多高张力章节（节奏单调）
        boolean allHigh = recentTensions.stream().allMatch(t -> t >= HIGH_TENSION_THRESHOLD);
        if (allHigh && currentChapter > 3) {
            return Optional.of(new ContinuityIssue(
                    projectId, currentChapter,
                    ContinuityIssue.Type.PACING,
                    ContinuityIssue.Severity.LOW,
                    "连续" + stallWindow + "章高张力（" + recentTensions + "），缺少节奏起伏。" +
                    "建议下一章加入过渡场景。"));
        }

        // p11-F3: 检测 4——节奏过快（连续 N 章中张力全部 ≥6，但不是全部 ≥8）
        // 区别于检测3：检测3要求全部≥8（高潮疲劳），这里全部≥6说明每章都在高强度推进
        int fastWindow = repository.findWritingPreferences(projectId)
                .map(p -> p.getFastPaceWindow())
                .orElse(4);
        if (currentChapter >= fastWindow) {
            List<Integer> recentForFast = new ArrayList<>();
            for (var v : outline.get().getVolumes()) {
                for (var s : v.getSynopses()) {
                    if (s.getChapterIndex() > currentChapter - fastWindow
                            && s.getChapterIndex() <= currentChapter) {
                        recentForFast.add(s.getTensionLevel());
                    }
                }
            }
            if (recentForFast.size() >= fastWindow) {
                boolean allModerateHigh = recentForFast.stream().allMatch(t -> t >= 6);
                boolean notAllExtreme = recentForFast.stream().anyMatch(t -> t < 8);
                if (allModerateHigh && notAllExtreme) {
                    return Optional.of(new ContinuityIssue(
                            projectId, currentChapter,
                            ContinuityIssue.Type.PACING,
                            ContinuityIssue.Severity.MEDIUM,
                            "连续" + fastWindow + "章保持中高张力（" + recentForFast + "），" +
                            "剧情推进过快，缺少喘息。建议下一章设为喘息章（tension≤3），" +
                            "深化角色关系或展示日常，不推进核心剧情。"));
                }
            }
        }

        return Optional.empty();
    }
}
