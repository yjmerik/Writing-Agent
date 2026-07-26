package com.writing.novel.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 生成日志——记录每次 Agent 生成结果的关键元素摘要。
 * 用于跨项目/跨卷的创意多样性追踪。
 */
public class GenerationLog {
    private final String id;
    private final String projectId;
    private final String agentType;     // WORLDBUILDER / CHARACTER / PLOTTER
    private String summary;             // 人类可读摘要
    private String keyElements;         // JSON 数组 ["经济:铜矿", "势力:清流vs浊流"]
    private int volumeIndex;
    private final Instant createdAt;

    public GenerationLog(String projectId, String agentType, String summary,
                         String keyElements, int volumeIndex) {
        this.id = UUID.randomUUID().toString();
        this.projectId = projectId;
        this.agentType = agentType;
        this.summary = summary;
        this.keyElements = keyElements;
        this.volumeIndex = volumeIndex;
        this.createdAt = Instant.now();
    }

    public String getId() { return id; }
    public String getProjectId() { return projectId; }
    public String getAgentType() { return agentType; }
    public String getSummary() { return summary; }
    public String getKeyElements() { return keyElements; }
    public int getVolumeIndex() { return volumeIndex; }
    public Instant getCreatedAt() { return createdAt; }
}
