package com.writing.novel.service;

import com.writing.novel.domain.GenerationLog;
import com.writing.novel.spi.NovelRepository;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 创意多样性服务——记录生成历史 + 生成多样性指引文本。
 *
 * 不硬编码任何题材套路。只是让 LLM 知道"你之前做过 X"，
 * 让它自己选择不同方向。
 */
public class CreativeDiversityService {

    private final NovelRepository repository;

    public CreativeDiversityService(NovelRepository repository) {
        this.repository = repository;
    }

    /** 记录一次生成结果 */
    public void record(String projectId, String agentType, String summary,
                       String keyElements, int volumeIndex) {
        repository.saveGenerationLog(new GenerationLog(
                projectId, agentType, summary, keyElements, volumeIndex));
    }

    /**
     * 生成多样性指引文本——让 Agent 看到之前做过什么。
     * 返回空字符串如果没有历史记录。
     */
    public String getDiversityGuide(String projectId, String agentType) {
        var logs = repository.findRecentGenerationLogs(projectId, agentType, 3);
        if (logs.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("【创意多样性指引（以下是近期生成中使用过的方向，请探索不同的角度）】\n");
        for (var log : logs) {
            sb.append("● ").append(log.getSummary());
            if (log.getKeyElements() != null && !log.getKeyElements().equals("[]")) {
                sb.append("（关键元素：").append(log.getKeyElements()).append("）");
            }
            sb.append("\n");
        }
        sb.append("以上是已使用过的方向。本次生成必须在主题、手法、冲突形式上与上述内容有本质区别。\n");
        sb.append("请根据构思自由选择新的方向，但避免重复上述模式。\n");
        return sb.toString();
    }

    /** 跨 Agent 类型的多样性指引（看所有类型的最近生成） */
    public String getGlobalDiversityGuide(String projectId) {
        var allLogs = repository.findAllGenerationLogs(projectId);
        if (allLogs.size() < 2) return "";

        // 汇总所有关键元素
        var allElements = allLogs.stream()
                .map(GenerationLog::getKeyElements)
                .filter(e -> e != null && !e.isBlank() && !e.equals("[]"))
                .collect(Collectors.joining(", "));

        if (allElements.isBlank()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("【全局创意多样性（以下是本项目中已生成的核心元素，请确保新内容有独特性）】\n");
        sb.append("已使用元素：").append(allElements).append("\n");
        sb.append("请确保本次生成在主题选择、冲突形式、角色设定上与上述有明确区别。\n");
        return sb.toString();
    }
}
