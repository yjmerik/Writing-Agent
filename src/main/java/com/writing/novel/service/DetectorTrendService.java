package com.writing.novel.service;

import com.writing.novel.domain.ContinuityIssue;
import com.writing.novel.spi.NovelRepository;
import com.writing.novel.telemetry.EventLog;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NCS-123: 检测器产出分级回流——桥段重复/说明文密度/结构节奏类 MEDIUM 产出
 * 此前只落台账永不触发修复（issue 通胀根因）。本服务提供两级回流：
 * <ul>
 *   <li><b>卷级趋势门禁</b>：{@link #volumeGate}——一卷内同类 MEDIUM 超阈值发
 *       DETECTOR_TREND_EXCEEDED 事件 + 警告文本（进卷末评价必读），不逐章阻断</li>
 *   <li><b>聚合反馈注入</b>：{@link #feedbackSection}——同类问题按类型聚合一句话
 *       （去重），进下一卷 qualityFeedback，替代逐章噪音</li>
 * </ul>
 */
@Component
public class DetectorTrendService {

    /** 卷内同类 MEDIUM 超过此数 → 趋势门禁告警 */
    static final int TREND_THRESHOLD = 4;

    private final NovelRepository repository;

    public DetectorTrendService(NovelRepository repository) {
        this.repository = repository;
    }

    /** 参与回流的检测器家族（非 HIGH 级、趋势型问题） */
    private static final List<ContinuityIssue.Type> TREND_TYPES = List.of(
            ContinuityIssue.Type.STRUCTURE, ContinuityIssue.Type.PACING, ContinuityIssue.Type.DENSITY);

    /** 卷级趋势门禁：返回告警文本（空串=无趋势问题） */
    public String volumeGate(String projectId, int fromChapter, int toChapter) {
        Map<String, Integer> counts = countsByType(projectId, fromChapter, toChapter);
        StringBuilder warn = new StringBuilder();
        counts.forEach((type, n) -> {
            if (n > TREND_THRESHOLD) {
                EventLog.emit("DETECTOR_TREND_EXCEEDED", Map.of(
                        "projectId", projectId, "fromChapter", fromChapter, "toChapter", toChapter,
                        "type", type, "count", n, "threshold", TREND_THRESHOLD));
                if (warn.length() > 0) warn.append("；");
                warn.append(type).append(" 类问题 ").append(n)
                        .append(" 次（> ").append(TREND_THRESHOLD).append("，卷级趋势需在下一卷纠正）");
            }
        });
        return warn.toString();
    }

    /** 聚合反馈段：同类问题聚合一句话（类型 × 出现章数），供下一卷 qualityFeedback 注入 */
    public String feedbackSection(String projectId, int fromChapter, int toChapter) {
        Map<String, Integer> counts = countsByType(projectId, fromChapter, toChapter);
        if (counts.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("【上卷趋势反馈（同类问题聚合，下一卷须系统性纠正）】\n");
        counts.forEach((type, n) -> sb.append("- ").append(type).append("：")
                .append(n).append(" 章/terms 出现，避免同类问题再现\n"));
        return sb.toString().trim();
    }

    private Map<String, Integer> countsByType(String projectId, int fromChapter, int toChapter) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (var issue : repository.findUnresolvedIssues(projectId)) {
            if (!TREND_TYPES.contains(issue.getType())) continue;
            if (issue.getSeverity() != ContinuityIssue.Severity.MEDIUM
                    && issue.getSeverity() != ContinuityIssue.Severity.LOW) continue;
            int at = issue.getDetectedAtChapter();
            if (at < fromChapter || at > toChapter) continue;
            counts.merge(issue.getType().name(), 1, Integer::sum);
        }
        return counts;
    }
}
