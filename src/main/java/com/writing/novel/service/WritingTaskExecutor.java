package com.writing.novel.service;

import com.writing.novel.domain.NovelState;
import com.writing.novel.domain.WritingPreferences;
import com.writing.novel.domain.WritingTask;
import com.writing.novel.orchestrator.NovelOrchestrator;
import com.writing.novel.spi.NovelRepository;
import com.writing.novel.telemetry.CostBudgetMonitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * p13-mc-p0: 异步写作任务执行器——后台线程执行写作循环。
 *
 * 可靠性保证（spec mc-p0-01~05）：
 * - 单项目单活动作业：新任务启动前等待旧任务退出（最多 30s）
 * - 取消只设内存标志，DB 的 CANCELLED 在循环退出后才写（避免竞态）
 * - 启动时清扫孤儿 RUNNING 任务（进程崩溃后残留）
 * - 诚实终态：区分写满/熔断/取消/失败
 */
public class WritingTaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(WritingTaskExecutor.class);

    private final NovelOrchestrator orchestrator;
    private final NovelRepository repository;
    /** p10-w1-04: 成本预算熔断；null 时不启用（向后兼容） */
    private final CostBudgetMonitor costBudgetMonitor;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private volatile boolean shuttingDown;

    /** 内存中的取消标志（taskId → cancelled） */
    private final Map<String, Boolean> cancelFlags = new ConcurrentHashMap<>();

    /** p20-05: 项目工作权协调器——每项目至多一个 live worker 的唯一裁决点 */
    private final ProjectWorkCoordinator coordinator = new ProjectWorkCoordinator();

    /** NCS-074: 匿名同步工作登记（workspace 可观测/可取消的数据源；内存态，与占坑同生命周期） */
    public record SyncWorkInfo(String projectId, String workId, String kind, java.time.Instant startedAt) {}

    private final Map<String, SyncWorkInfo> syncWorks = new ConcurrentHashMap<>();

    /** NCS-001: 写入门禁（epoch 租约）；null 时不启用 */
    private final com.writing.novel.service.WriteLeaseService writeLease;

    public WritingTaskExecutor(NovelOrchestrator orchestrator, NovelRepository repository) {
        this(orchestrator, repository, null);
    }

    /** p10-w1-04: 带成本预算监控的构造器（生产装配用） */
    public WritingTaskExecutor(NovelOrchestrator orchestrator, NovelRepository repository,
                               CostBudgetMonitor costBudgetMonitor) {
        this(orchestrator, repository, costBudgetMonitor, null);
    }

    /** NCS-001: 带写入门禁的生产构造——任务启动捕获 epoch，取消/停止递增 */
    public WritingTaskExecutor(NovelOrchestrator orchestrator, NovelRepository repository,
                               CostBudgetMonitor costBudgetMonitor,
                               com.writing.novel.service.WriteLeaseService writeLease) {
        this.orchestrator = orchestrator;
        this.repository = repository;
        this.costBudgetMonitor = costBudgetMonitor;
        this.writeLease = writeLease;
        sweepOrphanedTasks();
    }

    /** NCS-001: 任务启动时捕获当前写入门禁 epoch */
    private void captureLeaseEpoch(WritingTask task) {
        if (writeLease != null) {
            task.setLeaseEpoch(writeLease.currentEpoch(task.getProjectId()));
        }
    }

    /**
     * mc-p0-02: 清扫所有项目中的孤儿 RUNNING 任务。
     */
    private void sweepOrphanedTasks() {
        try {
            int swept = 0;
            for (var project : repository.findAllProjects()) {
                var active = repository.findActiveWritingTask(project.getId());
                if (active.isPresent() && active.get().getStatus() == WritingTask.Status.RUNNING) {
                    var task = active.get();
                    task.setStatus(task.getType() == WritingTask.Type.AUTO_RUN
                            ? WritingTask.Status.PAUSED : WritingTask.Status.FAILED);
                    task.setErrorMessage(task.getType() == WritingTask.Type.AUTO_RUN
                            ? "服务曾中断，已保留进度与指令，检查内容后可继续" : "进程未干净退出");
                    repository.saveWritingTask(task);
                    swept++;
                    log.warn("孤儿任务清扫: taskId={}, projectId={} → {}", task.getId(), project.getId(), task.getStatus());
                }
            }
            if (swept > 0) {
                log.info("孤儿任务清扫完成: {} 个 RUNNING 任务已收敛", swept);
            }
        } catch (Exception e) {
            log.warn("孤儿任务清扫失败: {}", e.getMessage());
        }
    }

    /**
     * 启动异步写作任务。
     * mc-p0-05: 单项目单活动作业——等待旧任务退出后再启动新任务。
     */
    public synchronized WritingTask startWriting(String projectId, int maxChapters) {
        return startWriting(projectId, maxChapters, null);
    }

    /** p18-03: 带作者指令的连续写作启动——指令随任务传递，逐章沿用 */
    public synchronized WritingTask startWriting(String projectId, int maxChapters, String instruction) {
        // p20-05: 项目已有 live worker 时拒绝（409），直到当前 owner 到达终态——
        // 不再取消旧任务/等待 30s/强制接管（stale worker 重叠的根源）
        WritingTask task = new WritingTask(projectId, maxChapters, WritingTask.Type.WRITE_ALL);
        var token = coordinator.tryAcquire(projectId, task.getId());
        if (token.isEmpty()) {
            throw new WorkConflictException("项目已有进行中的写作任务，请等待其完成或先取消");
        }
        try {
            task.setInstruction(instruction);
            captureLeaseEpoch(task);
            repository.saveWritingTask(task);
        } catch (RuntimeException e) {
            coordinator.release(token.get());
            throw e;
        }
        cancelFlags.put(task.getId(), false);

        executor.submit(() -> executeWriting(task, maxChapters));

        log.info("异步写作任务已启动: projectId={}, taskId={}, maxChapters={}",
                projectId, task.getId(), maxChapters);
        return task;
    }

    public synchronized WritingTask startConceiving(String projectId) {
        WritingTask task = new WritingTask(projectId, 0, WritingTask.Type.CONCEIVE);
        var token = coordinator.tryAcquire(projectId, task.getId());
        if (token.isEmpty()) {
            // p15-05: 只有已落库的同项目 CONCEIVE owner 可幂等返回；同步人工工作必须明确冲突。
            String activeTaskId = coordinator.currentOwner(projectId).orElse(null);
            WritingTask activeTask = activeTaskId == null ? null : getTask(activeTaskId);
            if (activeTask != null
                    && projectId.equals(activeTask.getProjectId())
                    && activeTask.getType() == WritingTask.Type.CONCEIVE) {
                log.info("项目 {} 已有进行中的构思任务 {}，直接返回原任务", projectId, activeTaskId);
                return activeTask;
            }
            throw new IllegalStateException("项目已有进行中的工作，请等待其完成");
        }
        try {
            captureLeaseEpoch(task);
            repository.saveWritingTask(task);
        } catch (RuntimeException e) {
            coordinator.release(token.get());
            throw e;
        }
        cancelFlags.put(task.getId(), false);
        executor.submit(() -> executeConceive(task));
        return task;
    }

    /**
     * p15-05: 单章写作任务——同步执行窗口内登记 RUNNING WRITE_CHAPTER 任务并占用活动作业槽位。
     * p20-05: 经协调器原子获取工作权——项目已有活动任务时拒绝（IllegalStateException → 409），
     * 不得覆盖原 owner。
     */
    public synchronized WritingTask startWriteChapter(String projectId) {
        return startWriteChapter(projectId, () -> {});
    }

    /**
     * 单章写作的原子启动边界：先获取项目工作权，再重验服务端动作门禁，
     * 门禁通过后才持久化任务。重验失败会释放工作权且不产生任务。
     */
    public synchronized WritingTask startWriteChapter(String projectId, Runnable actionGate) {
        WritingTask task = new WritingTask(projectId, 1, WritingTask.Type.WRITE_CHAPTER);
        var token = coordinator.tryAcquire(projectId, task.getId());
        if (token.isEmpty()) {
            throw new WorkConflictException("已有进行中的写作任务，请先暂停或取消再写单章");
        }
        try {
            actionGate.run();
            captureLeaseEpoch(task);
            repository.saveWritingTask(task);
        } catch (RuntimeException e) {
            coordinator.release(token.get());
            throw e;
        }
        cancelFlags.put(task.getId(), false);
        return task;
    }

    /**
     * p15-05: 单章任务收尾——终态落库后条件清理活动映射（不得移除被新任务接管的映射）；
     * 清理在 finally 中执行，终态落库抛异常也必须释放自己的映射与取消标志。
     */
    public void finishWriteChapter(String projectId, WritingTask task,
                                   WritingTask.Status status, String errorMessage) {
        try {
            task.setStatus(status);
            if (errorMessage != null) {
                task.setErrorMessage(errorMessage.length() > 200
                        ? errorMessage.substring(0, 200) : errorMessage);
            }
            if (status == WritingTask.Status.COMPLETED) {
                task.setChaptersWritten(1);
                task.setCurrentChapter(repository.findProject(projectId)
                        .map(p -> p.getCurrentChapterIndex()).orElse(0));
            }
            repository.saveWritingTask(task);
        } finally {
            coordinator.release(projectId, task.getId());
            cancelFlags.remove(task.getId());
        }
    }

    private void executeConceive(WritingTask task) {
        try {
            if (isCancelled(task.getId())) {
                task.setStatus(WritingTask.Status.CANCELLED);
            } else {
                orchestrator.step(task.getProjectId());
                var state = repository.findProject(task.getProjectId())
                        .map(p -> p.getState()).orElse(NovelState.CONCEIVING);
                if (isCancelled(task.getId())) {
                    task.setStatus(WritingTask.Status.CANCELLED);
                } else if (state == NovelState.CONCEIVING) {
                    task.setStatus(WritingTask.Status.FAILED);
                    task.setErrorMessage("构思未完成，仍停留在 CONCEIVING");
                } else {
                    task.setStatus(WritingTask.Status.COMPLETED);
                }
            }
            repository.saveWritingTask(task);
        } catch (Exception e) {
            log.error("构思任务失败: {}", task.getId(), e);
            task.setStatus(WritingTask.Status.FAILED);
            task.setErrorMessage(e.getMessage() != null
                    ? e.getMessage().substring(0, Math.min(200, e.getMessage().length())) : "未知错误");
            repository.saveWritingTask(task);
        } catch (Throwable t) {
            // NCS-166: Error 穿透曾致线程静默死亡——任务永远 RUNNING、工作权泄漏、
            // 用户无限转圈（走查 incident A）。线程出口兜底：落 FAILED 终态 + 事件，
            // 再按 JVM 语义重抛 Error。
            log.error("构思任务线程异常死亡（Throwable 兜底）: {}", task.getId(), t);
            try {
                task.setStatus(WritingTask.Status.FAILED);
                task.setErrorMessage("任务线程异常终止: "
                        + (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName()));
                repository.saveWritingTask(task);
            } catch (Exception ignored) {
                // 终态落库失败也不能吞掉原始 Error
            }
            com.writing.novel.telemetry.EventLog.emit("TASK_THREAD_DIED", java.util.Map.of(
                    "taskId", task.getId(),
                    "projectId", task.getProjectId(),
                    "type", task.getType().name(),
                    "errorClass", t.getClass().getSimpleName()));
            if (t instanceof Error err) throw err;
            throw new IllegalStateException(t);
        } finally {
            // p15-05/p20-05: 条件释放——旧 worker 退出时不得移除已被新任务接管的映射
            coordinator.release(task.getProjectId(), task.getId());
            cancelFlags.remove(task.getId());
        }
    }

    private void executeWriting(WritingTask task, int maxChapters) {
        try {
            log.info("写作任务开始: {}", task.getId());
            final String taskId = task.getId();
            final String projectId = task.getProjectId();
            // p10-w1-04: 成本预算——每章前经 cancelChecker 检查是否超预算（及时熔断，不改 runWritingLoop 签名）
            final double budget = repository.findWritingPreferences(projectId)
                    .map(WritingPreferences::getCostBudgetYuan).orElse(0.0);
            final BooleanSupplier costOver = (costBudgetMonitor != null && budget > 0)
                    ? () -> costBudgetMonitor.isOverBudget(projectId, budget)
                    : () -> false;
            int written = orchestrator.runWritingLoop(projectId, maxChapters,
                    () -> isCancelled(taskId) || costOver.getAsBoolean(), task.getInstruction());

            // mc-p0-03: 诚实终态——区分写满、熔断、取消、预算超限
            boolean overBudget = costOver.getAsBoolean();
            if (isCancelled(taskId)) {
                // mc-p0-01: 循环退出后才写 CANCELLED 到 DB（不与 COMPLETED 竞态）
                task.setStatus(WritingTask.Status.CANCELLED);
            } else if (overBudget && written < maxChapters) {
                // p10-w1-04: 成本预算超限 → PAUSED（非失败，用户加预算可续写）
                double spent = costBudgetMonitor != null ? costBudgetMonitor.total(projectId) : 0;
                task.setStatus(WritingTask.Status.PAUSED);
                task.setErrorMessage("成本预算超限: 累计 " + Math.round(spent * 100) / 100.0
                        + " 元 >= 预算 " + budget + " 元（加预算后可续写）");
                log.warn("写作任务因成本预算超限暂停: taskId={}, 累计={}元, 预算={}元",
                        taskId, Math.round(spent * 100) / 100.0, budget);
            } else if (written < maxChapters) {
                var project = repository.findProject(projectId);
                var outline = repository.findOutlineByProject(projectId);
                // p18-01: INTERACTIVE 模式 checkpoint 暂停是正常审核点，不是失败——
                // 原判定落入 FAILED「写作未完成: 写了1/50章」误导用户
                if (project.isPresent() && project.get().getState()
                        == com.writing.novel.domain.NovelState.PAUSED_AT_CHECKPOINT) {
                    task.setStatus(WritingTask.Status.PAUSED);
                    task.setErrorMessage("已写 " + written + " 章后到达人工审核点（INTERACTIVE 模式），"
                            + "审批 checkpoint 后可继续写作");
                    log.info("写作任务暂停于审核点: taskId={}, 已写 {} 章", taskId, written);
                } else if (project.isPresent()
                        // p23-02: 待人工复核暂停同样是正常暂停（p20-01 写作冻结），非失败
                        && repository.findChapterByIndex(projectId, project.get().getCurrentChapterIndex())
                                .map(c -> c.getStatus() == com.writing.novel.domain.Chapter.Status.NEEDS_MANUAL_REVIEW)
                                .orElse(false)) {
                    task.setStatus(WritingTask.Status.PAUSED);
                    task.setErrorMessage("第 " + project.get().getCurrentChapterIndex()
                            + " 章待人工复核，处理后再续写");
                    log.info("写作任务暂停于人工复核: taskId={}, 已写 {} 章", taskId, written);
                } else if (!repository.findActiveReviewBarriers(projectId).isEmpty()) {
                    // p33-01（体验 #18）：屏障可能在任一章节（不只当前章）——按屏障判 PAUSED，
                    // 原单点状态检查会让"标记后索引推进"的场景误报 FAILED
                    int first = repository.findActiveReviewBarriers(projectId).stream()
                            .mapToInt(com.writing.novel.domain.ReviewBarrier::getChapterIndex).min().orElse(0);
                    task.setStatus(WritingTask.Status.PAUSED);
                    task.setErrorMessage("第 " + first + " 章待人工复核，处理后再续写");
                    log.info("写作任务暂停于人审屏障: taskId={}, 已写 {} 章", taskId, written);
                } else {
                    boolean outlineExhausted = outline.isPresent() && project.isPresent() &&
                            !outline.get().getVolumes().stream()
                                    .flatMap(v -> v.getSynopses().stream())
                                    .anyMatch(s -> s.getChapterIndex() > project.get().getCurrentChapterIndex());
                    if (outlineExhausted) {
                        task.setStatus(WritingTask.Status.COMPLETED);
                    } else {
                        task.setStatus(WritingTask.Status.FAILED);
                        task.setErrorMessage("写作未完成: 写了" + written + "/" + maxChapters + "章");
                    }
                }
            } else {
                task.setStatus(WritingTask.Status.COMPLETED);
            }
            task.setChaptersWritten(written);
            task.setTotalWords(repository.getTotalWordCount(projectId));
            task.setCurrentChapter(repository.findProject(projectId)
                    .map(p -> p.getCurrentChapterIndex()).orElse(0));
            repository.saveWritingTask(task);
            log.info("写作任务终态: {} = {}, 写了 {} 章", task.getId(), task.getStatus(), written);
        } catch (Exception e) {
            log.error("写作任务失败: {}", task.getId(), e);
            task.setStatus(WritingTask.Status.FAILED);
            task.setErrorMessage(e.getMessage() != null ?
                    e.getMessage().substring(0, Math.min(200, e.getMessage().length())) : "未知错误");
            repository.saveWritingTask(task);
        } catch (Throwable t) {
            // NCS-166: 同构思路径——Error 穿透必须落 FAILED 终态 + 事件，再按 JVM 语义重抛
            log.error("写作任务线程异常死亡（Throwable 兜底）: {}", task.getId(), t);
            try {
                task.setStatus(WritingTask.Status.FAILED);
                task.setErrorMessage("任务线程异常终止: "
                        + (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName()));
                repository.saveWritingTask(task);
            } catch (Exception ignored) {
                // 终态落库失败也不能吞掉原始 Error
            }
            com.writing.novel.telemetry.EventLog.emit("TASK_THREAD_DIED", java.util.Map.of(
                    "taskId", task.getId(),
                    "projectId", task.getProjectId(),
                    "type", task.getType().name(),
                    "errorClass", t.getClass().getSimpleName()));
            if (t instanceof Error err) throw err;
            throw new IllegalStateException(t);
        } finally {
            // mc-p0-05/p20-05: 条件释放工作权——不得移除已被新任务接管的映射
            coordinator.release(task.getProjectId(), task.getId());
            cancelFlags.remove(task.getId());
        }
    }

    /**
     * 取消写作任务。
     * mc-p0-01: 只设内存标志，不立即写 DB——循环退出后才写 CANCELLED。
     * NCS-001: 同时递增持久写入门禁 epoch——晚到的正文/投影写入按旧 epoch 一律拒绝。
     */
    public boolean cancel(String taskId) {
        cancelFlags.put(taskId, true);
        if (writeLease != null) {
            repository.findWritingTask(taskId).ifPresent(t ->
                    writeLease.bump(t.getProjectId(), "cancel:" + taskId));
        }
        // NCS-072: PAUSED 任务没有活跃 worker 在检查取消标志——直接终态转移并释放
        // 项目工作权，否则占坑死锁（作者无法拒绝重写/启动新任务且界面无出路）。
        // 条件释放只影响仍持有坑位的该任务；若 worker 线程残存活着的写路径由
        // 上面的写租约 epoch 提升（NCS-001）拦截。
        repository.findWritingTask(taskId).ifPresent(t -> {
            if (t.getStatus() == WritingTask.Status.PAUSED) {
                t.setStatus(WritingTask.Status.CANCELLED);
                repository.saveWritingTask(t);
                endWork(t.getProjectId(), t.getId());
                log.info("暂停任务已终止: taskId={}（PAUSED→CANCELLED，工作权已释放）", taskId);
            }
        });
        log.info("取消信号已发送: taskId={}（循环将在当前章完成后退出）", taskId);
        return true;
    }

    public boolean isCancelled(String taskId) {
        return cancelFlags.getOrDefault(taskId, false);
    }

    public WritingTask getTask(String taskId) {
        return repository.findWritingTask(taskId).orElse(null);
    }

    public boolean hasActiveTask(String projectId) {
        return coordinator.hasOwner(projectId);
    }

    /** Whole-book collaboration shares ownership with every existing writer/editor. */
    public synchronized WritingTask startCollaborative(String projectId, String instruction) {
        String owner = coordinator.currentOwner(projectId).orElse(null);
        if (owner != null) {
            WritingTask active = getTask(owner);
            if (active != null && active.getType() == WritingTask.Type.AUTO_RUN
                    && active.getStatus() == WritingTask.Status.RUNNING) return active;
            throw new WorkConflictException("项目正在处理内容，请等待完成后继续");
        }
        WritingTask task = new WritingTask(projectId, repository.findWritingPreferences(projectId)
                .map(WritingPreferences::getTargetChapters).orElse(24), WritingTask.Type.AUTO_RUN);
        beginWork(projectId, task.getId());
        try {
            String blocked = collaborationBlocker(projectId);
            if (blocked != null) throw new IllegalStateException(blocked);
            WritingTask previous = latestCollaborativeTask(projectId);
            task.setInstruction(instruction != null ? instruction.trim()
                    : previous == null ? null : previous.getInstruction());
            captureLeaseEpoch(task);
            repository.saveWritingTask(task);
            executor.submit(() -> executeCollaborative(task));
            return task;
        } catch (RuntimeException e) {
            endWork(projectId, task.getId());
            throw e;
        }
    }

    public WritingTask latestCollaborativeTask(String projectId) {
        return repository.findWritingTasksByProject(projectId).stream()
                .filter(t -> t.getType() == WritingTask.Type.AUTO_RUN).findFirst().orElse(null);
    }

    public String collaborationBlocker(String projectId) {
        var project = repository.findProject(projectId)
                .orElseThrow(() -> new IllegalArgumentException("项目不存在"));
        if (project.getState() == NovelState.FINAL) return "全书已完成，可继续审阅和编辑";
        if (project.getState() == NovelState.NEEDS_REVISION) return "终审需要修订，请修改内容后重新评审";
        if (!repository.findPendingCheckpoints(projectId).isEmpty()) return "请先处理待审核内容，再继续创作";
        // p33-01（体验 #18）：检查项目全部 ACTIVE 人审屏障——不再只查当前章状态字段，
        // 防止标记后索引推进绕过停点继续写作
        var barriers = repository.findActiveReviewBarriers(projectId);
        if (!barriers.isEmpty()) {
            int first = barriers.stream().mapToInt(com.writing.novel.domain.ReviewBarrier::getChapterIndex).min().orElse(0);
            return "第 " + first + " 章需要人工复核（共 " + barriers.size() + " 项待处理），处理后再继续";
        }
        if (overBudget(projectId)) return "已达到成本预算，请调整预算后继续";
        return null;
    }

    public synchronized void stopCollaborative(String projectId) {
        repository.findProject(projectId).orElseThrow(() -> new IllegalArgumentException("项目不存在"));
        String owner = coordinator.currentOwner(projectId).orElse(null);
        if (owner == null) return;
        WritingTask task = getTask(owner);
        if (task == null) return; // A synchronous edit finishes normally; never release its owner early.
        task.setStopRequested(true);
        repository.saveWritingTask(task);
        // NCS-001: cancel 内部递增持久写入门禁 epoch——旧任务的晚到写入不得再落库
        cancel(owner);
    }

    private boolean overBudget(String projectId) {
        double budget = repository.findWritingPreferences(projectId)
                .map(WritingPreferences::getCostBudgetYuan).orElse(0.0);
        return costBudgetMonitor != null && costBudgetMonitor.isOverBudget(projectId, budget);
    }

    private void executeCollaborative(WritingTask task) {
        String id = task.getProjectId();
        int written = 0;
        try {
            // Keep one writing loop so cross-chapter failure and quality counters retain meaning.
            while (!shuttingDown && !isCancelled(task.getId()) && !overBudget(id)) {
                var project = repository.findProject(id).orElseThrow();
                NovelState state = project.getState();
                if (state == NovelState.FINAL || collaborationBlocker(id) != null) break;
                if (state == NovelState.PAUSED_AT_CHECKPOINT) {
                    // Legacy rejected checkpoints can leave a paused phase with no pending item.
                    // No approval is implicit: all actual pending items were checked above.
                    project.setState(repository.findOutlineByProject(id).isPresent()
                            ? NovelState.PLANNING : NovelState.CONCEIVING);
                    repository.saveProject(project);
                    continue;
                }
                if (state == NovelState.WRITING || state == NovelState.VOLUME_DONE) {
                    written = orchestrator.runWritingLoop(id, Integer.MAX_VALUE,
                            () -> shuttingDown || isCancelled(task.getId()) || overBudget(id), task.getInstruction());
                    break;
                }
                orchestrator.step(id);
                if (repository.findProject(id).orElseThrow().getState() == state) {
                    throw new IllegalStateException("创作步骤未推进，请检查内容或错误后重试");
                }
            }
            var state = repository.findProject(id).orElseThrow().getState();
            if (state == NovelState.FINAL) {
                task.setStatus(WritingTask.Status.COMPLETED);
            } else {
                task.setStatus(WritingTask.Status.PAUSED);
                String reason = collaborationBlocker(id);
                task.setErrorMessage(shuttingDown ? "服务曾中断，已保留进度与指令，检查内容后可继续"
                        : isCancelled(task.getId()) ? "已交给作者，可编辑内容后继续"
                        : reason != null ? reason : "创作已停下，请检查章节质量或运行记录后继续");
            }
        } catch (Exception e) {
            if (shuttingDown) {
                task.setStatus(WritingTask.Status.PAUSED);
                task.setErrorMessage("服务曾中断，已保留进度与指令，检查内容后可继续");
                log.info("服务关闭，协作任务等待作者恢复: {}", task.getId());
            } else {
                task.setStatus(WritingTask.Status.FAILED);
                task.setErrorMessage(e.getMessage() == null ? "创作失败，请检查后重试" : e.getMessage());
                log.error("协作任务失败: {}", task.getId(), e);
            }
        } finally {
            task.setChaptersWritten(written);
            finishCollaborative(task);
        }
    }

    private synchronized void finishCollaborative(WritingTask task) {
        try {
            task.setStopRequested(repository.findWritingTask(task.getId())
                    .map(WritingTask::isStopRequested).orElse(false));
            task.setCurrentChapter(repository.findProject(task.getProjectId())
                    .map(p -> p.getCurrentChapterIndex()).orElse(0));
            task.setTotalWords(repository.getTotalWordCount(task.getProjectId()));
            repository.saveWritingTask(task);
        } finally {
            endWork(task.getProjectId(), task.getId());
            cancelFlags.remove(task.getId());
        }
    }

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        shuttingDown = true;
        executor.shutdownNow();
    }

    /** p20-05: 同步工作注册——占坑失败（已有 owner）时抛 409 语义异常。
     *  NCS-075: 冲突按持有者分类——RUNNING 任务=收尾中（稍候重试）、匿名=同步处理中
     *  （等待或取消）、其余保持既有「暂停或取消」指引；细分码供前端区分提示。
     *  NCS-074: 占坑成功即登记同步工作（kind=workId 去 UUID 前缀）供观测。 */
    public void beginWork(String projectId, String workId) {
        if (coordinator.tryAcquire(projectId, workId).isEmpty()) {
            String owner = coordinator.currentOwner(projectId).orElse(null);
            WritingTask ownerTask = owner == null ? null : getTask(owner);
            if (ownerTask != null && ownerTask.getStatus() == WritingTask.Status.RUNNING) {
                throw new WorkConflictException(
                        "上一章的 Agent 仍在收尾质量检查（通常数十秒），请稍候重试", "WORKER_FINALIZING");
            }
            if (ownerTask == null) {
                throw new WorkConflictException(
                        "同步内容处理进行中，请等待其完成或取消该操作", "SYNC_WORK_BUSY");
            }
            throw new WorkConflictException("已有进行中的写作任务，请先暂停或取消");
        }
        syncWorks.put(projectId, new SyncWorkInfo(projectId, workId, kindOf(workId), java.time.Instant.now()));
    }

    public void endWork(String projectId, String workId) {
        coordinator.release(projectId, workId);
        // NCS-074: 条件清除——非持有者的迟到释放不得清掉当前登记
        syncWorks.computeIfPresent(projectId, (k, v) -> v.workId().equals(workId) ? null : v);
    }

    /** NCS-074: 当前匿名同步工作（仅当其仍持有项目工作权时可见；任务型占坑不在此展示） */
    public Optional<SyncWorkInfo> activeSyncWork(String projectId) {
        SyncWorkInfo info = syncWorks.get(projectId);
        if (info == null || !coordinator.isOwner(projectId, info.workId())) {
            return Optional.empty();
        }
        // 经 beginWork 占坑的任务 id（如协作任务）属于任务型占坑——走任务视图与终止路径
        if (getTask(info.workId()) != null) {
            return Optional.empty();
        }
        return Optional.of(info);
    }

    /**
     * NCS-074: 取消匿名同步占坑——立即释放项目工作权，作者不再被挂起操作锁死。
     * 诚实边界：不中断进行中的调用；原操作若最终完成仍按稿件身份落库。
     * 任务型占坑返回 false（走 NCS-072/073 的任务终止路径），不在此误释放。
     */
    public boolean cancelSyncWork(String projectId) {
        String owner = coordinator.currentOwner(projectId).orElse(null);
        if (owner == null) return false;
        if (getTask(owner) != null) return false;
        endWork(projectId, owner);
        log.warn("同步工作已被作者取消并释放项目工作权: projectId={}, workId={}", projectId, owner);
        return true;
    }

    /** NCS-074: workId 形如 "<kind>-<uuid>"（uuid 5 段）——剥掉尾部 UUID 得到操作种类 */
    private static String kindOf(String workId) {
        String[] parts = workId.split("-", -1);
        if (parts.length > 5) {
            return String.join("-", java.util.Arrays.copyOfRange(parts, 0, parts.length - 5));
        }
        return workId;
    }

    public boolean cancelByProject(String projectId) {
        String taskId = coordinator.currentOwner(projectId).orElse(null);
        if (taskId == null) return false;
        return cancel(taskId);
    }
}
