package com.writing.novel.service;

import com.writing.novel.spi.NovelRepository;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/** p40-02: 运行详情——实际步骤、耗时、失败位置。 */
@Service
public class RunDetailsService {

    private final NovelRepository repository;

    public RunDetailsService(NovelRepository repository) {
        this.repository = repository;
    }

    public Map<String, Object> runDetails(String projectId) {
        var task = repository.findActiveWritingTask(projectId)
                .orElseGet(() -> repository.findWritingTasksByProject(projectId).stream()
                        .reduce((first, second) -> second).orElse(null));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("projectId", projectId);
        if (task == null) {
            result.put("status", "NO_TASK");
            return result;
        }
        result.put("taskId", task.getId());
        result.put("status", task.getStatus().name());
        result.put("currentChapter", task.getCurrentChapter());
        result.put("totalChapters", task.getTotalChapters());
        result.put("chaptersWritten", task.getChaptersWritten());
        if (task.getErrorMessage() != null && !task.getErrorMessage().isBlank()) {
            result.put("failureReason", task.getErrorMessage());
        }
        result.put("createdAt", task.getCreatedAt() != null ? task.getCreatedAt().toString() : null);
        result.put("updatedAt", task.getUpdatedAt() != null ? task.getUpdatedAt().toString() : null);
        var barriers = repository.findActiveReviewBarriers(projectId);
        result.put("pendingReviewCount", barriers.size());
        return result;
    }
}
