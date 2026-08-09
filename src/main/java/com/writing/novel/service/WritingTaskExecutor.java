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

/**
 * p12-K2: 异步写作任务执行器——后台线程执行写作循环。
 *
 * 解决 OrchestratorController.writeAll 的 HTTP 超时问题：
 * write-all 不再阻塞，启动后台任务后立即返回 taskId。
 * 前端轮询 GET /writing-tasks/{taskId} 查进度。
 */
public class WritingTaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(WritingTaskExecutor.class);

    private final NovelOrchestrator orchestrator;
    private final NovelRepository repository;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    /** 内存中的取消标志（taskId → cancelled） */
    private final Map<String, Boolean> cancelFlags = new ConcurrentHashMap<>();

    public WritingTaskExecutor(NovelOrchestrator orchestrator, NovelRepository repository) {
        this.orchestrator = orchestrator;
        this.repository = repository;
        // mc-p0-02: 启动时清扫孤儿 RUNNING 任务（进程崩溃后残留）
        sweepOrphanedTasks();
    }

    /**
     * mc-p0-02: 清扫所有项目中的孤儿 RUNNING 任务——进程崩溃后 DB 残留的 RUNNING 行。
     * 收敛为 FAILED(errorMessage=进程未干净退出)，不自动续写。
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
     * @return 创建的 WritingTask（状态为 RUNNING）
     */
    public WritingTask startWriting(String projectId, int maxChapters) {
        // 如果该项目已有运行中的任务，先取消
        repository.findActiveWritingTask(projectId).ifPresent(existing -> {
            if (existing.getStatus() == WritingTask.Status.RUNNING) {
                cancelFlags.put(existing.getId(), true);
                existing.setStatus(WritingTask.Status.CANCELLED);
                repository.saveWritingTask(existing);
            }
        });

        WritingTask task = new WritingTask(projectId, maxChapters);
        repository.saveWritingTask(task);
        cancelFlags.put(task.getId(), false);

        executor.submit(() -> executeWriting(task, maxChapters));

        log.info("异步写作任务已启动: projectId={}, taskId={}, maxChapters={}",
                projectId, task.getId(), maxChapters);
        return task;
    }

    private void executeWriting(WritingTask task, int maxChapters) {
        try {
            log.info("写作任务开始: {}", task.getId());
            final String taskId = task.getId();
            int written = orchestrator.runWritingLoop(task.getProjectId(), maxChapters,
                    () -> isCancelled(taskId));

            // mc-p0-03: 诚实终态——区分写满、熔断、取消
            if (isCancelled(task.getId())) {
                task.setStatus(WritingTask.Status.CANCELLED);
            } else if (written < maxChapters) {
                // 未写满——可能是大纲耗尽或熔断，检查是否大纲已完结
                var project = repository.findProject(task.getProjectId());
                var outline = repository.findOutlineByProject(task.getProjectId());
                boolean outlineExhausted = outline.isPresent() && project.isPresent() &&
                        !outline.get().getVolumes().stream()
                                .flatMap(v -> v.getSynopses().stream())
                                .anyMatch(s -> s.getChapterIndex() > project.get().getCurrentChapterIndex());
                if (outlineExhausted) {
                    // 大纲已写完——正常完成
                    task.setStatus(WritingTask.Status.COMPLETED);
                } else {
                    // 未写满且大纲未写完——异常终止
                    task.setStatus(WritingTask.Status.FAILED);
                    task.setErrorMessage("写作未完成: 写了" + written + "/" + maxChapters + "章");
                }
            } else {
                // 写满 maxChapters——正常完成
                task.setStatus(WritingTask.Status.COMPLETED);
            }
            task.setChaptersWritten(written);
            task.setTotalWords(repository.getTotalWordCount(task.getProjectId()));
            task.setCurrentChapter(repository.findProject(task.getProjectId())
                    .map(p -> p.getCurrentChapterIndex()).orElse(0));
            repository.saveWritingTask(task);
            log.info("写作任务完成: {}, 写了 {} 章", task.getId(), written);
        } catch (Exception e) {
            log.error("写作任务失败: {}", task.getId(), e);
            task.setStatus(WritingTask.Status.FAILED);
            task.setErrorMessage(e.getMessage() != null ?
                    e.getMessage().substring(0, Math.min(200, e.getMessage().length())) : "未知错误");
            repository.saveWritingTask(task);
        } finally {
            cancelFlags.remove(task.getId());
        }
    }

    /** 取消写作任务 */
    public boolean cancel(String taskId) {
        cancelFlags.put(taskId, true);
        repository.findWritingTask(taskId).ifPresent(t -> {
            t.setStatus(WritingTask.Status.CANCELLED);
            repository.saveWritingTask(t);
        });
        log.info("写作任务取消请求: {}", taskId);
        return true;
    }

    /** 检查任务是否被取消 */
    public boolean isCancelled(String taskId) {
        return cancelFlags.getOrDefault(taskId, false);
    }

    /** 获取任务状态 */
    public WritingTask getTask(String taskId) {
        return repository.findWritingTask(taskId).orElse(null);
    }
}
