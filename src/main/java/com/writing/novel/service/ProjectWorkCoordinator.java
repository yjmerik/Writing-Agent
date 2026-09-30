package com.writing.novel.service;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * p20-05: 项目级工作权协调器——owner-token 语义，保证一个项目至多一个 live worker。
 *
 * <p>核心不变量：
 * <ul>
 *   <li>并发 acquire 同一项目只有一个成功者（compute 原子性）</li>
 *   <li>已有 live owner 时重复启动得到冲突，由调用方转为 409——
 *       不再有"30s 强制接管"（旧 WritingTaskExecutor 行为，stale worker 重叠的根源）</li>
 *   <li>release 是条件删除：stale owner 的迟到释放不能清除已接手的新 owner</li>
 *   <li>NCS-141：持有者绑定其工作线程凭证——仅当持有者线程已死亡
 *       （finally 泄漏路径，可证明不会再执行/释放）时新请求可原子接管；
 *       活线程持有者语义不变，不引入重叠执行</li>
 * </ul>
 *
 * <p>owner-token 在 worker 启动时获取、终态时释放；token 只证明"我是当前 owner"，
 * 不携带其他身份。业务层（WritingTaskExecutor）经此模块裁决，不自行维护活动映射。
 */
public class ProjectWorkCoordinator {

    private static final Logger LOG = Logger.getLogger(ProjectWorkCoordinator.class.getName());

    /** owner 凭证：projectId + workId 绑定；释放须经协调器校验仍是自己持有 */
    public record OwnerToken(String projectId, String workId) {}

    /** 持有者条目：workId + 注册时的工作线程（死线程是唯一可证明的 stale 信号） */
    private record WorkOwner(String workId, Thread thread, Instant acquiredAt) {}

    /** projectId → 当前 owner（实例字段——每个协调器独立管理自己的项目集） */
    private final Map<String, WorkOwner> owners = new ConcurrentHashMap<>();

    /**
     * 原子尝试获取项目工作权；已被活线程占用时返回 empty（调用方转为 409 冲突）。
     * 持有者线程已死亡时可原子接管（NCS-141）。
     */
    public Optional<OwnerToken> tryAcquire(String projectId, String workId) {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("projectId 不能为空");
        }
        if (workId == null || workId.isBlank()) {
            throw new IllegalArgumentException("workId 不能为空");
        }
        boolean[] tookOver = {false};
        String[] staleId = {null};
        WorkOwner mine = owners.compute(projectId, (pid, cur) -> {
            if (cur == null) {
                return new WorkOwner(workId, Thread.currentThread(), Instant.now());
            }
            if (cur.thread() != null && !cur.thread().isAlive()) {
                tookOver[0] = true;
                staleId[0] = cur.workId();
                return new WorkOwner(workId, Thread.currentThread(), Instant.now());
            }
            return cur;
        });
        if (!mine.workId().equals(workId)) {
            return Optional.empty();
        }
        if (tookOver[0]) {
            LOG.warning("项目工作权接管: projectId=" + projectId + " 旧持有者 workId="
                    + staleId[0] + " 线程已死亡, 新持有者=" + workId);
        }
        return Optional.of(new OwnerToken(projectId, workId));
    }

    /** 条件释放——若所有权已被他人接替则 no-op（stale 释放不伤新 owner） */
    public void release(OwnerToken token) {
        if (token == null) {
            return;
        }
        owners.computeIfPresent(token.projectId(), (pid, cur) ->
                cur.workId().equals(token.workId()) ? null : cur);
    }

    /** 条件释放（成对参数形式，供已有 taskId/workId 的调用方使用） */
    public boolean release(String projectId, String workId) {
        if (projectId == null || workId == null) {
            return false;
        }
        boolean[] removed = {false};
        owners.computeIfPresent(projectId, (pid, cur) -> {
            if (cur.workId().equals(workId)) {
                removed[0] = true;
                return null;
            }
            return cur;
        });
        return removed[0];
    }

    /** workId 是否仍是该项目的当前 owner */
    public boolean isOwner(String projectId, String workId) {
        if (workId == null) {
            return false;
        }
        WorkOwner cur = owners.get(projectId);
        return cur != null && cur.workId().equals(workId);
    }

    /** 当前 owner 的 workId（无 owner 时 empty） */
    public Optional<String> currentOwner(String projectId) {
        WorkOwner cur = owners.get(projectId);
        return cur == null ? Optional.empty() : Optional.of(cur.workId());
    }

    /** 项目是否有 live worker */
    public boolean hasOwner(String projectId) {
        return owners.containsKey(projectId);
    }
}
