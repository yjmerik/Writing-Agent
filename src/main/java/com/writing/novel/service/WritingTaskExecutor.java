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

    /** 内存中的取消标志（taskId → cancelled） */
    private final Map<String, Boolean> cancelFlags = new ConcurrentHashMap<>();

    /** p20-05: 项目工作权协调器——每项目至多一个 live worker 的唯一裁决点 */
    private final ProjectWorkCoordinator coordinator = new ProjectWorkCoordinator();

    public WritingTaskExecutor(NovelOrchestrator orchestrator, NovelRepository repository) {
        this(orchestrator, repository, null);
    }

    /** p10-w1-04: 带成本预算监控的构造器（生产装配用） */
    public WritingTaskExecutor(NovelOrchestrator orchestrator, NovelRepository repository,
                               CostBudgetMonitor costBudgetMonitor) {
        this.orchestrator = orchestrator;
        this.repository = repository;
        this.costBudgetMonitor = costBudgetMonitor;
        sweepOrphanedTasks();
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
                    task.setStatus(WritingTask.Status.FAILED);
                    task.setErrorMessage("进程未干净退出");
                    repository.saveWritingTask(task);
                    swept++;
                    log.warn("孤儿任务清扫: taskId={}, projectId={} → FAILED", task.getId(), project.getId());
                }
            }
            if (swept > 0) {
                log.info("孤儿任务清扫完成: {} 个 RUNNING 任务收敛为 FAILED", swept);
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
            throw new IllegalStateException("项目已有进行中的写作任务，请等待其完成或先取消");
        }
        try {
            task.setInstruction(instruction);
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
        // p15-05: 原任务仍在运行时，重复启动直接返回原任务（不取消、不替换、不等待）
        String activeTaskId = coordinator.currentOwner(projectId).orElse(null);
        if (activeTaskId != null) {
            log.info("项目 {} 已有进行中的构思任务 {}，直接返回原任务", projectId, activeTaskId);
            return getTask(activeTaskId);
        }
        WritingTask task = new WritingTask(projectId, 0, WritingTask.Type.CONCEIVE);
        if (coordinator.tryAcquire(projectId, task.getId()).isEmpty()) {
            return getTask(coordinator.currentOwner(projectId).orElse(task.getId()));
        }
        repository.saveWritingTask(task);
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
        WritingTask task = new WritingTask(projectId, 1, WritingTask.Type.WRITE_CHAPTER);
        var token = coordinator.tryAcquire(projectId, task.getId());
        if (token.isEmpty()) {
            throw new IllegalStateException("已有进行中的写作任务，请先暂停或取消再写单章");
        }
        try {
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
        } finally {
            // mc-p0-05/p20-05: 条件释放工作权——不得移除已被新任务接管的映射
            coordinator.release(task.getProjectId(), task.getId());
            cancelFlags.remove(task.getId());
        }
    }

    /**
     * 取消写作任务。
     * mc-p0-01: 只设内存标志，不立即写 DB——循环退出后才写 CANCELLED。
     */
    public boolean cancel(String taskId) {
        cancelFlags.put(taskId, true);
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

    /** p20-05: 同步工作注册——占坑失败（已有 owner）时抛 409 语义异常 */
    public void beginWork(String projectId, String workId) {
        if (coordinator.tryAcquire(projectId, workId).isEmpty()) {
            throw new IllegalStateException("已有进行中的写作任务，请先暂停或取消");
        }
    }

    public void endWork(String projectId, String workId) {
        coordinator.release(projectId, workId);
    }

    public boolean cancelByProject(String projectId) {
        String taskId = coordinator.currentOwner(projectId).orElse(null);
        if (taskId == null) return false;
        return cancel(taskId);
    }
}
