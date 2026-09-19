package com.writing.novel.service;

import com.writing.novel.domain.WritingTask;
import com.writing.novel.persistence.sqlite.WriteLeaseRepository;
import com.writing.novel.telemetry.EventLog;

import java.util.Map;

/**
 * NCS-001（方案 §14/R08）: 项目级持久写入门禁。
 *
 * <p>epoch 在任务启动时被捕获（writing_tasks.lease_epoch）；停止协作/取消任务时递增。
 * Agent 侧写入（正文生成、纠偏重写、后处理派生数据）提交前校验：
 * 活动 RUNNING 任务捕获的 epoch 必须等于当前持久 epoch，否则拒绝并留 LATE_WRITE_REJECTED 事件。
 *
 * <p>作者直接保存是新的写入权威，不经此门禁（由既有版本 CAS/工权协调保护）；
 * 无活动任务时（作者同步流程）不设防。
 */
public class WriteLeaseService {

    private final WriteLeaseRepository repository;

    public WriteLeaseService(WriteLeaseRepository repository) {
        this.repository = repository;
    }

    public int currentEpoch(String projectId) {
        return repository.currentEpoch(projectId);
    }

    /** 递增持久 epoch 并留事件；返回新 epoch */
    public int bump(String projectId, String reason) {
        int next = repository.bump(projectId, reason);
        EventLog.emit("WRITE_LEASE_EPOCH_BUMPED", Map.of(
                "projectId", projectId, "epoch", next, "reason", reason));
        return next;
    }

    /**
     * Agent 写入门禁：activeTask 为空（作者同步流程/无任务）时放行；
     * 任务捕获的 epoch 与当前不一致时拒绝（晚到写入），留 LATE_WRITE_REJECTED 事件。
     */
    public void guardAgentWrite(String projectId, WritingTask activeTask, String context) {
        if (activeTask == null) return;
        int current = currentEpoch(projectId);
        if (activeTask.getLeaseEpoch() != current) {
            EventLog.emit("LATE_WRITE_REJECTED", Map.of(
                    "projectId", projectId,
                    "context", String.valueOf(context),
                    "taskEpoch", activeTask.getLeaseEpoch(),
                    "currentEpoch", current,
                    "taskId", activeTask.getId()));
            throw new WriteLeaseStaleException("写入租约已失效（epoch "
                    + activeTask.getLeaseEpoch() + " → " + current + "），晚到的 Agent 写入被拒绝: " + context);
        }
    }
}
