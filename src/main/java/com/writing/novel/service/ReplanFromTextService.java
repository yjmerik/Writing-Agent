package com.writing.novel.service;

import com.writing.novel.orchestrator.NovelOrchestrator;
import com.writing.novel.telemetry.EventLog;

import java.util.Map;

/**
 * NCS-021（方案 §06/J08）：按正文更新计划——回读确认后，以卷摘要+承诺状态+人物变化
 * （回读结论）为输入触发未写部分再规划；登记统一修订请求留痕；已写章不动。
 */
public class ReplanFromTextService {

    public record ReplanResult(int volumeIndex, boolean replanned) {}

    private final NovelOrchestrator orchestrator;
    private final RevisionRequestService requests;
    private final VolumeReadbackService readbacks;

    public ReplanFromTextService(NovelOrchestrator orchestrator,
                                 RevisionRequestService requests,
                                 VolumeReadbackService readbacks) {
        this.orchestrator = orchestrator;
        this.requests = requests;
        this.readbacks = readbacks;
    }

    public ReplanResult replanFromText(String projectId, int volumeIndex) {
        var readback = readbacks.latest(projectId, volumeIndex)
                .orElseThrow(() -> new IllegalArgumentException("该卷尚无回读报告"));
        if (!com.writing.novel.domain.VolumeReadback.ACKNOWLEDGED.equals(readback.getStatus())) {
            throw new IllegalStateException("回读未确认——先阅读回读并确认，再按正文更新计划");
        }
        orchestrator.replanRemaining(projectId);
        requests.recordAndComplete(projectId, "PLAN_ADJUST",
                "按正文更新计划：依据第 " + volumeIndex + " 卷回读结论（人物变化/承诺状态/建议）再生成未写部分",
                "[{\"volumeIndex\":" + volumeIndex + "}]", "{}", "[]",
                "已写章不动，未写部分已按实际正文重规划");
        EventLog.emit("REPLAN_FROM_TEXT", Map.of(
                "projectId", projectId, "volumeIndex", volumeIndex));
        return new ReplanResult(volumeIndex, true);
    }
}
