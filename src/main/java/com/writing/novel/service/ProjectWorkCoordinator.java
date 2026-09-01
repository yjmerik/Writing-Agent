package com.writing.novel.service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * p20-05: 项目级工作权协调器——owner-token 语义，保证一个项目至多一个 live worker。
 *
 * <p>核心不变量：
 * <ul>
 *   <li>并发 acquire 同一项目只有一个成功者（putIfAbsent 原子性）</li>
 *   <li>已有 live owner 时重复启动得到冲突，由调用方转为 409——
 *       不再有"30s 强制接管"（旧 WritingTaskExecutor 行为，stale worker 重叠的根源）</li>
 *   <li>release 是条件删除：stale owner 的迟到释放不能清除已接手的新 owner</li>
 * </ul>
 *
 * <p>owner-token 在 worker 启动时获取、终态时释放；token 只证明"我是当前 owner"，
 * 不携带其他身份。业务层（WritingTaskExecutor）经此模块裁决，不自行维护活动映射。
 */
public class ProjectWorkCoordinator {

    /** owner 凭证：projectId + workId 绑定；释放须经协调器校验仍是自己持有 */
    public record OwnerToken(String projectId, String workId) {}

    /** projectId → 当前 owner workId（实例字段——每个协调器独立管理自己的项目集） */
    private final Map<String, String> owners = new ConcurrentHashMap<>();

    /**
     * 原子尝试获取项目工作权；已被占用时返回 empty（调用方转为 409 冲突）。
     */
    public Optional<OwnerToken> tryAcquire(String projectId, String workId) {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("projectId 不能为空");
        }
        if (workId == null || workId.isBlank()) {
            throw new IllegalArgumentException("workId 不能为空");
        }
        String prev = owners.putIfAbsent(projectId, workId);
        if (prev != null) {
            return Optional.empty();
        }
        return Optional.of(new OwnerToken(projectId, workId));
    }

    /** 条件释放——若所有权已被他人接替则 no-op（stale 释放不伤新 owner） */
    public void release(OwnerToken token) {
        if (token != null) {
            owners.remove(token.projectId(), token.workId());
        }
    }

    /** 条件释放（成对参数形式，供已有 taskId/workId 的调用方使用） */
    public boolean release(String projectId, String workId) {
        return projectId != null && workId != null && owners.remove(projectId, workId);
    }

    /** workId 是否仍是该项目的当前 owner */
    public boolean isOwner(String projectId, String workId) {
        return workId != null && workId.equals(owners.get(projectId));
    }

    /** 当前 owner 的 workId（无 owner 时 empty） */
    public Optional<String> currentOwner(String projectId) {
        return Optional.ofNullable(owners.get(projectId));
    }

    /** 项目是否有 live worker */
    public boolean hasOwner(String projectId) {
        return owners.containsKey(projectId);
    }
}
