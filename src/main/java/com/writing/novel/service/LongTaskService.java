package com.writing.novel.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * NCS-094（第二轮旅程 A-4 一期后续）：同步长事务（修订预览/重检）改异步任务——
 * HTTP 请求立即返回 jobId，工作在工作线程执行并占用项目工作权（与同步入口互斥），
 * 前端轮询进度；不再受浏览器/代理超时制约。任务内存态，重启即失（前端 404 时提示重试）。
 */
@Service
public class LongTaskService {

    private static final Logger log = LoggerFactory.getLogger(LongTaskService.class);

    public record JobState(String jobId, String projectId, String kind, String label,
                           String status, Object result, String error,
                           Instant startedAt, Instant finishedAt) {}

    private static final class Job {
        final String jobId;
        final String projectId;
        final String kind;
        final String label;
        final Instant startedAt = Instant.now();
        volatile String status = "RUNNING";
        volatile Object result;
        volatile String error;
        volatile Instant finishedAt;

        Job(String jobId, String projectId, String kind, String label) {
            this.jobId = jobId;
            this.projectId = projectId;
            this.kind = kind;
            this.label = label;
        }

        JobState snapshot() {
            return new JobState(jobId, projectId, kind, label, status, result, error,
                    startedAt, finishedAt);
        }
    }

    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final WritingTaskExecutor writingTaskExecutor;
    private final ExecutorService worker = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "long-task-" + UUID.randomUUID());
        t.setDaemon(true);
        return t;
    });

    public LongTaskService(WritingTaskExecutor writingTaskExecutor) {
        this.writingTaskExecutor = writingTaskExecutor;
    }

    /**
     * 提交长任务：立即返回 jobId；工作在线程池执行并占用项目工作权（复用
     * WritingTaskExecutor 的同步工作登记，协作栏「取消该操作」仍可释放）。
     */
    public String submit(String projectId, String kind, String label, Supplier<?> work) {
        String jobId = kind + "-" + UUID.randomUUID();
        Job job = new Job(jobId, projectId, kind, label);
        jobs.put(jobId, job);
        writingTaskExecutor.beginWork(projectId, jobId);
        worker.submit(() -> {
            try {
                Object result = work.get();
                job.result = result;
                job.status = "DONE";
            } catch (Exception e) {
                job.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                job.status = "FAILED";
                log.warn("长任务失败({}/{}): {}", kind, projectId, job.error);
            } finally {
                job.finishedAt = Instant.now();
                writingTaskExecutor.endWork(projectId, jobId);
            }
        });
        return jobId;
    }

    /** 查询任务状态；项目不匹配或不存在均返回空（前端提示重试）。 */
    public Optional<JobState> get(String projectId, String jobId) {
        return Optional.ofNullable(jobs.get(jobId))
                .filter(j -> j.projectId.equals(projectId))
                .map(Job::snapshot);
    }
}
