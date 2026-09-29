package com.writing.novel.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.domain.Character;
import com.writing.novel.domain.Entity;
import com.writing.novel.domain.EntityEdge;
import com.writing.novel.domain.EntityType;
import com.writing.novel.domain.RelationshipType;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmTaskType;
import com.writing.novel.llm.prompt.PromptTemplateService;
import com.writing.novel.spi.NovelRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * p11-H3: 关系演变追踪器——每章写完后用 LLM 检测角色关系变化。
 *
 * 解决问题：entity_edges 60 条全部静态（0 条动态版本），
 * 关系演变只发生在自然语言层（characters.relationships），
 * 结构化图谱从未更新。
 *
 * 本 Agent 每章检测"敌变友/背叛/结盟"等变化事件，
 * 通过 EntityGraphService 过期旧边 + 插入新边。
 */
public class RelationshipEvolutionTracker {

    private final LlmGateway gateway;
    private final NovelRepository repository;
    private final PromptTemplateService promptService;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public RelationshipEvolutionTracker(LlmGateway gateway, NovelRepository repository,
                                        PromptTemplateService promptService) {
        this.gateway = gateway;
        this.repository = repository;
        this.promptService = promptService;
    }

    /**
     * 检测本章的关系变化并更新图谱。
     * @return 检测到的关系变化数量
     */
    public int detectAndApply(String projectId, int chapterIndex, String chapterContent,
                              String cast) {
        if (chapterContent == null || chapterContent.length() < 200) return 0;

        // 构建当前关系网络快照
        String currentRelations = buildRelationshipSnapshot(projectId);
        if (currentRelations.isEmpty()) currentRelations = "（暂无已知关系）";

        String content = chapterContent.length() > 4000
                ? chapterContent.substring(0, 4000) : chapterContent;

        String systemPrompt = promptService.render("relationship-evolution", Map.of(
                "currentRelationships", currentRelations,
                "cast", cast != null ? cast : "（未指定）",
                "chapterContent", content));

        var req = LlmRequest.builder()
                .taskType(LlmTaskType.GENERAL)
                .systemPrompt(systemPrompt)
                .userMessage("请分析本章的关系变化。")
                .temperature(0.2)
                .maxTokens(3072)
                .build();

        try {
            var resp = gateway.complete(req);
            return applyEvolutions(resp.getContent(), projectId, chapterIndex);
        } catch (com.writing.novel.agent.util.PostProcessStageFailure e) {
            throw e;
        } catch (Exception e) {
            // NCS-119: 关系演化失败可见（旧静默 0——本章关系变化永久丢失且无人知晓）
            throw new com.writing.novel.agent.util.PostProcessStageFailure("relationship-evolution", "关系演化检测失败: " + e.getMessage(), e);
        }
    }

    private String buildRelationshipSnapshot(String projectId) {
        var edges = repository.findEdgesByProject(projectId);
        var characters = repository.findEntitiesByProject(projectId).stream()
                .filter(e -> e.getType() == EntityType.CHARACTER)
                .toList();
        var nameById = characters.stream()
                .collect(Collectors.toMap(Entity::getId, Entity::getName));

        StringBuilder sb = new StringBuilder();
        for (var edge : edges) {
            if (!edge.isCurrent()) continue;
            String sourceName = nameById.getOrDefault(edge.getSourceId(), "?");
            String targetName = nameById.getOrDefault(edge.getTargetId(), "?");
            sb.append(sourceName).append(" → ").append(targetName)
                    .append("：").append(edge.getRelationship())
                    .append("，自第").append(edge.getValidFromChapter()).append("章\n");
        }
        return sb.toString().trim();
    }

    private int applyEvolutions(String json, String projectId, int chapterIndex) {
        int count = 0;
        try {
            JsonNode root = com.writing.novel.llm.LlmJson.parseOrThrow(json, null);
            JsonNode evolutions = root.path("evolutions");
            if (!evolutions.isArray()) return 0;

            var characters = repository.findEntitiesByProject(projectId).stream()
                    .filter(e -> e.getType() == EntityType.CHARACTER)
                    .collect(Collectors.toList());
            var nameToEntity = characters.stream()
                    .collect(Collectors.toMap(Entity::getName, e -> e, (a, b) -> a));

            var existingEdges = repository.findEdgesByProject(projectId);

            for (JsonNode node : evolutions) {
                String nameA = node.path("characterA").asText("");
                String nameB = node.path("characterB").asText("");
                String newRelation = node.path("newRelation").asText("").toUpperCase();
                String trigger = node.path("trigger").asText("");
                if (nameA.isBlank() || nameB.isBlank() || newRelation.isBlank()) continue;

                Entity entityA = nameToEntity.get(nameA);
                Entity entityB = nameToEntity.get(nameB);
                if (entityA == null || entityB == null) continue;

                // 过期旧的 A→B 边（NCS-122: 对称过期 B→A——对齐 EntityExtractor 的
                // 对称关系处理策略，两处过期口径不一致是双轨漂移的根因之一）
                for (var edge : existingEdges) {
                    boolean forward = edge.getSourceId().equals(entityA.getId())
                            && edge.getTargetId().equals(entityB.getId());
                    boolean backward = edge.getSourceId().equals(entityB.getId())
                            && edge.getTargetId().equals(entityA.getId());
                    if ((forward || backward) && edge.isCurrent()) {
                        edge.expire(chapterIndex);
                        repository.saveEntityEdge(edge);
                    }
                }

                // 插入新的 A→B 边
                RelationshipType relType;
                try {
                    relType = RelationshipType.valueOf(newRelation);
                } catch (IllegalArgumentException ignored) {
                    relType = RelationshipType.RELATED_TO;
                }
                EntityEdge newEdge = new EntityEdge(projectId,
                        entityA.getId(), entityB.getId(), relType);
                newEdge.setValidFromChapter(chapterIndex);
                newEdge.setMetadata(trigger);
                repository.saveEntityEdge(newEdge);
                count++;
            }
        } catch (com.writing.novel.agent.util.PostProcessStageFailure e) {
            throw e;
        } catch (Exception e) {
            throw new com.writing.novel.agent.util.PostProcessStageFailure("relationship-evolution", "关系演化应用失败: " + e.getMessage(), e);
        }
        return count;
    }
}
