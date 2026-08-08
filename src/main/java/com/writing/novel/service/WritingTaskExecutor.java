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
            int written = orchestrator.runWritingLoop(task.getProjectId(), maxChapters);

            // 检查是否被取消
            if (isCancelled(task.getId())) {
                task.setStatus(WritingTask.Status.CANCELLED);
            } else {
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
