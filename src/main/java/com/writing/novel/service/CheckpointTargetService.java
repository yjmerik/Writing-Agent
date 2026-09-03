package com.writing.novel.service;

import com.writing.novel.domain.Checkpoint;
import com.writing.novel.spi.NovelRepository;
import org.springframework.stereotype.Service;

/** Validates that a checkpoint action targets a pending checkpoint in its owning project. */
@Service
public class CheckpointTargetService {

    private final NovelRepository repository;

    public CheckpointTargetService(NovelRepository repository) {
        this.repository = repository;
    }

    public Checkpoint requirePending(String projectId, String checkpointId) {
        Checkpoint checkpoint = repository.findCheckpoint(checkpointId)
                .orElseThrow(() -> new IllegalArgumentException("Checkpoint 不存在: " + checkpointId));
        if (!projectId.equals(checkpoint.getProjectId())) {
            throw new IllegalStateException("Checkpoint 不属于当前项目");
        }
        if (checkpoint.getStatus() != Checkpoint.Status.PENDING) {
            throw new IllegalStateException("Checkpoint 已处理");
        }
        return checkpoint;
    }
}
