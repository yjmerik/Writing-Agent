package com.writing.novel.service;

import com.writing.novel.persistence.sqlite.LongTaskJobRepository;
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
                           String phase, Integer percent,
                           Instant startedAt, Instant finishedAt) {

        /** 兼容旧构造（无进度） */
        public JobState(String jobId, String projectId, String kind, String label,
                        String status, Object result, String error,
                        Instant startedAt, Instant finishedAt) {
            this(jobId, projectId, kind, label, status, result, error, null, null,
                    startedAt, finishedAt);
        }
    }

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
        volatile String phase;          // NCS-133: 阶段级进度
        volatile Integer percent;

        Job(String jobId, String projectId, String kind, String label) {
            this.jobId = jobId;
            this.projectId = projectId;
            this.kind = kind;
            this.label = label;
        }

        JobState snapshot() {
            return new JobState(jobId, projectId, kind, label, status, result, error,
                    phase, percent, startedAt, finishedAt);
        }
    }

    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final WritingTaskExecutor writingTaskExecutor;
    /** NCS-133: 持久化（可选；null 时行为与旧内存态一致） */
    private final LongTaskJobRepository jobRepository;
    private final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();
    private final ExecutorService worker = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "long-task-" + UUID.randomUUID());
        t.setDaemon(true);
        return t;
    });

    public LongTaskService(WritingTaskExecutor writingTaskExecutor) {
        this(writingTaskExecutor, null);
    }

    /** NCS-133: 带持久化的生产装配——重启恢复 + 进度落库 */
    @org.springframework.beans.factory.annotation.Autowired
    public LongTaskService(WritingTaskExecutor writingTaskExecutor,
                           LongTaskJobRepository jobRepository) {
        this.writingTaskExecutor = writingTaskExecutor;
        this.jobRepository = jobRepository;
        if (jobRepository != null) {
            int cancelled = jobRepository.cancelOrphans();
            int purged = jobRepository.purgeExpired(72);
            if (cancelled + purged > 0) {
                log.info("长任务启动清扫: {} 个 RUNNING 孤儿标记取消, {} 条过期清理", cancelled, purged);
            }
        }
    }

    /** NCS-133: 工作线程内上报进度（阶段 + 可选百分比） */
    public void reportProgress(String jobId, String phase, Integer percent) {
        Job job = jobs.get(jobId);
        if (job != null) {
            job.phase = phase;
            job.percent = percent;
        }
        if (jobRepository != null && job != null) {
            try {
                jobRepository.save(job.jobId, job.projectId, job.kind, job.label, job.status,
                        null, job.error, phase, percent, job.startedAt, null);
            } catch (Exception ignored) {
                // 进度持久化失败不影响任务本身
            }
        }
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
        if (jobRepository != null) {
            try {
                jobRepository.save(jobId, projectId, kind, label, "RUNNING", null, null,
                        null, null, job.startedAt, null);
            } catch (Exception e) {
                log.warn("长任务登记落库失败(继续执行): {}", e.getMessage());
            }
        }
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
                if (jobRepository != null) {
                    try {
                        jobRepository.save(jobId, projectId, kind, label, job.status,
                                serializeResult(job.result), job.error, job.phase, job.percent,
                                job.startedAt, job.finishedAt);
                    } catch (Exception e) {
                        log.warn("长任务终态落库失败: {}", e.getMessage());
                    }
                }
            }
        });
        return jobId;
    }

    private String serializeResult(Object result) {
        if (result == null) return null;
        try {
            return MAPPER.writeValueAsString(result);
        } catch (Exception e) {
            return String.valueOf(result);
        }
    }

    /** 查询任务状态；项目不匹配或不存在均返回空（前端提示重试）。 */
    public Optional<JobState> get(String projectId, String jobId) {
        Job live = jobs.get(jobId);
        if (live != null && live.projectId.equals(projectId)) {
            return Optional.of(live.snapshot());
        }
        // NCS-133: 内存未命中（重启/过期淘汰）→ 持久层回读终态
        if (jobRepository != null) {
            return jobRepository.find(projectId, jobId).map(row -> new JobState(
                    (String) row.get("jobId"), projectId, (String) row.get("kind"),
                    (String) row.get("label"), (String) row.get("status"),
                    row.get("resultJson"), (String) row.get("error"),
                    (String) row.get("phase"), (Integer) row.get("percent"),
                    parseInstant((String) row.get("startedAt")),
                    parseInstant((String) row.get("finishedAt"))));
        }
        return Optional.empty();
    }

    private static Instant parseInstant(String s) {
        try { return s == null ? null : Instant.parse(s); } catch (Exception e) { return null; }
    }
}
