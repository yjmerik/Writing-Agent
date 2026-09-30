package com.writing.novel.web;

import com.writing.novel.service.LongTaskService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * NCS-139（旅程三 F-1）：统一长任务轮询端点。
 * 前端 getLongTask 固定请求 /api/projects/{id}/long-tasks/{jobId}，此前该路径无控制器，
 * 各类异步任务（agent-revision-preview / reader-review / recheck-async）轮询全部 404。
 * 任务状态本体由 LongTaskService 统一持有，本控制器只做项目隔离查询；
 * 旧的 /revisions/long-tasks 与 /chapters/{idx}/long-tasks 前缀保持兼容。
 */
@RestController
@RequestMapping("/api/projects/{id}/long-tasks")
public class LongTaskController {

    private final LongTaskService longTasks;

    public LongTaskController(LongTaskService longTasks) {
        this.longTasks = longTasks;
    }

    @GetMapping("/{jobId}")
    public ResponseEntity<Object> status(@PathVariable String id, @PathVariable String jobId) {
        return longTasks.get(id, jobId)
                .<ResponseEntity<Object>>map(state -> ResponseEntity.status(HttpStatus.OK).body(state))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                        "error", "任务不存在或已过期: " + jobId,
                        "code", "LONG_TASK_NOT_FOUND")));
    }

    /** NCS-157: 手动重投 FAILED_RETRYABLE 任务（重新占用工作权，重试预算重新计算） */
    @PostMapping("/{jobId}/retry")
    public ResponseEntity<Object> retry(@PathVariable String id, @PathVariable String jobId) {
        try {
            longTasks.retry(id, jobId);
            return ResponseEntity.status(HttpStatus.OK)
                    .body(Map.of("status", "RUNNING", "jobId", jobId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", e.getMessage(), "code", "LONG_TASK_NOT_FOUND"));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", e.getMessage(), "code", "LONG_TASK_NOT_RETRYABLE"));
        }
    }
}
