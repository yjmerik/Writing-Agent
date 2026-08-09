package com.writing.novel.service;

import com.writing.novel.domain.WritingTask;
import com.writing.novel.orchestrator.NovelOrchestrator;
import com.writing.novel.spi.NovelRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
    private final ExecutorService executor = Executors.newCachedThreadPool();

    /** 内存中的取消标志（taskId → cancelled） */
    private final Map<String, Boolean> cancelFlags = new ConcurrentHashMap<>();

    /** 正在运行的 taskId（projectId → taskId），用于单活动作业保证 */
    private final Map<String, String> activeTasks = new ConcurrentHashMap<>();

    public WritingTaskExecutor(NovelOrchestrator orchestrator, NovelRepository repository) {
        this.orchestrator = orchestrator;
        this.repository = repository;
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
        // mc-p0-05: 如果该项目有正在运行的任务，设置取消标志并等待其退出
        String activeTaskId = activeTasks.get(projectId);
        if (activeTaskId != null) {
            log.info("项目 {} 已有运行中任务 {}，发送取消信号并等待退出...", projectId, activeTaskId);
            cancelFlags.put(activeTaskId, true);
            // 等待旧任务退出（最多 30 秒——循环会在当前章完成后检查取消标志）
            for (int i = 0; i < 60; i++) {
                if (activeTasks.get(projectId) == null) break;
                try { Thread.sleep(500); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); break;
                }
            }
            // 如果仍未退出，强制清理
            if (activeTasks.get(projectId) != null) {
                log.warn("项目 {} 旧任务 {} 未在 30s 内退出，强制清理", projectId, activeTaskId);
                activeTasks.remove(projectId);
            }
        }

        WritingTask task = new WritingTask(projectId, maxChapters);
        repository.saveWritingTask(task);
        cancelFlags.put(task.getId(), false);
        activeTasks.put(projectId, task.getId());

        executor.submit(() -> executeWriting(task, maxChapters));

        log.info("异步写作任务已启动: projectId={}, taskId={}, maxChapters={}",
                projectId, task.getId(), maxChapters);
        return task;
    }

    private void executeWriting(WritingTask task, int maxChapters) {
        try {
            log.info("写作任务开始: {}", task.getId());
            final String taskId = task.getId();
            final String projectId = task.getProjectId();
            int written = orchestrator.runWritingLoop(projectId, maxChapters,
                    () -> isCancelled(taskId));

            // mc-p0-03: 诚实终态——区分写满、熔断、取消
            if (isCancelled(taskId)) {
                // mc-p0-01: 循环退出后才写 CANCELLED 到 DB（不与 COMPLETED 竞态）
                task.setStatus(WritingTask.Status.CANCELLED);
            } else if (written < maxChapters) {
                var project = repository.findProject(projectId);
                var outline = repository.findOutlineByProject(projectId);
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
            // mc-p0-05: 清除活动任务标记
            activeTasks.remove(task.getProjectId());
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
}
