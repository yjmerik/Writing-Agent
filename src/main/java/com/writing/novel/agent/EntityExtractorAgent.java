package com.writing.novel.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.domain.*;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmTaskType;
import com.writing.novel.llm.prompt.PromptTemplateService;
import com.writing.novel.memory.RelevanceGateway;
import com.writing.novel.spi.NovelRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 实体提取 Agent（图数据库写入端）。
 * 每章写完后调用 LLM 提取章节中出现的实体和关系，
 * 与已有实体进行去重和合并，然后持久化到图数据库。
 */
public class EntityExtractorAgent {

    private final LlmGateway gateway;
    private final NovelRepository repository;
    private final PromptTemplateService promptService;
    private final RelevanceGateway relevanceGateway; // p11-q0-01: 检索收窄门面
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public EntityExtractorAgent(LlmGateway gateway, NovelRepository repository,
                                PromptTemplateService promptService) {
        this(gateway, repository, promptService, null);
    }

    /** p11-q0-01: 注入 RelevanceGateway，进 LLM 的参考清单按 recency 收窄（token 受控） */
    public EntityExtractorAgent(LlmGateway gateway, NovelRepository repository,
                                PromptTemplateService promptService, RelevanceGateway relevanceGateway) {
        this.gateway = gateway;
        this.repository = repository;
        this.promptService = promptService;
        this.relevanceGateway = relevanceGateway;
    }

    /**
     * 从章节正文中提取实体和关系。
     * 调用 LLM → 解析 JSON → 去重合并 → 持久化。
     *
     * @return 提取结果统计
     */
    public ExtractionResult extract(String projectId, Chapter chapter) {
        // 已有实体列表（Java 层去重需全量，保证不产生重复实体）
        List<Entity> existingEntities = repository.findEntitiesByProject(projectId);
        // p11-q0-01: 进 LLM 的参考清单经 RelevanceGateway 收窄（recency），token 受控
        List<Entity> llmReference = relevanceGateway != null
                ? relevanceGateway.relevantEntities(projectId, null, 40)
                : existingEntities;
        String existingJson = formatExistingEntities(llmReference);

        // p9-5: 截断正文到 3000 字，避免超 token 导致 LLM 返回空
        String chapterContent = chapter.getContent();
        if (chapterContent != null && chapterContent.length() > 3000) {
            chapterContent = chapterContent.substring(0, 3000);
        }

        String systemPrompt = promptService.render("entity-extraction", Map.of(
                "existingEntities", existingJson,
                "chapterContent", chapterContent != null ? chapterContent : ""));

        // p9-5: 不在 userMessage 中重复正文（已在 systemPrompt 模板中注入）
        String userMessage = "请提取第" + chapter.getChapterIndex() + "章中的实体和关系。";

        var req = LlmRequest.builder()
                .taskType(LlmTaskType.GENERAL)
                .systemPrompt(systemPrompt)
                .userMessage(userMessage)
                .temperature(0.3)
                .maxTokens(4000)
                .build();

        var resp = gateway.complete(req);
        return parseAndSave(resp.getContent(), projectId, chapter.getChapterIndex(), existingEntities);
    }

    private ExtractionResult parseAndSave(String json, String projectId,
                                          int chapterIndex, List<Entity> existingEntities) {
        int newEntities = 0;
        int updatedEntities = 0;
        int newEdges = 0;

        try {
            // 解析 JSON（LLM 可能包了 ```json 标记）
            String clean = json.trim();
            if (clean.startsWith("```")) {
                clean = clean.replaceAll("^```(json)?\\s*", "").replaceAll("\\s*```$", "");
            }
            JsonNode root = MAPPER.readTree(clean);

            // 处理实体
            JsonNode entitiesNode = root.path("entities");
            if (entitiesNode.isArray()) {
                for (JsonNode en : entitiesNode) {
                    String name = com.writing.novel.util.NameSanitizer.sanitize(en.path("name").asText(""));
                    if (name.isBlank()) continue;
                    String typeStr = en.path("type").asText("CONCEPT");

                    EntityType type;
                    try { type = EntityType.valueOf(typeStr); }
                    catch (IllegalArgumentException e) { type = EntityType.CONCEPT; }

                    List<String> aliases = new ArrayList<>();
                    JsonNode aliasesNode = en.path("aliases");
                    if (aliasesNode.isArray()) {
                        for (JsonNode a : aliasesNode) {
                            String alias = a.asText("");
                            if (!alias.isBlank()) aliases.add(alias);
                        }
                    }

                    // 去重：按 name 精确匹配 + 别名匹配
                    Entity existing = findMatchingEntity(existingEntities, name, aliases);
                    if (existing != null) {
                        // 更新已有实体
                        existing.seenAt(chapterIndex);
                        existing.addAliases(aliases);
                        repository.saveEntity(existing);
                        updatedEntities++;
                    } else {
                        // 新建实体
                        Entity entity = new Entity(projectId, name, type);
                        entity.addAliases(aliases);
                        entity.seenAt(chapterIndex);
                        repository.saveEntity(entity);
                        existingEntities.add(entity); // 供后续边匹配
                        newEntities++;
                    }
                }
            }

            // 重新加载实体列表（含新创建的）
            final List<Entity> allEntities = repository.findEntitiesByProject(projectId);

            // 处理关系边
            JsonNode edgesNode = root.path("edges");
            if (edgesNode.isArray()) {
                for (JsonNode edgeNode : edgesNode) {
                    String sourceName = edgeNode.path("source").asText("");
                    String targetName = edgeNode.path("target").asText("");
                    String relStr = edgeNode.path("relationship").asText("RELATED_TO");
                    double weight = edgeNode.path("weight").asDouble(1.0);

                    if (sourceName.isBlank() || targetName.isBlank()) continue;

                    RelationshipType rel = RelationshipType.RELATED_TO;
                    try { rel = RelationshipType.valueOf(relStr); }
                    catch (IllegalArgumentException ignored) { }
                    final RelationshipType finalRel = rel;

                    // 找实体的 ID（按规范名匹配）
                    String sourceId = findEntityIdByName(allEntities, sourceName);
                    String targetId = findEntityIdByName(allEntities, targetName);

                    if (sourceId != null && targetId != null && !sourceId.equals(targetId)) {
                        // 检查已存在的边（含时间作用域 + 对称关系处理）
                        List<EntityEdge> existingFromSource = repository.findEdgesBySource(sourceId);
                        List<EntityEdge> existingFromTarget = repository.findEdgesBySource(targetId);

                        // 对称关系（ALLY/ENEMY/LOVE/RIVAL/KNOWS）双向检查
                        boolean sameCurrentExists;
                        if (finalRel.isSymmetric()) {
                            sameCurrentExists = existingFromSource.stream()
                                    .anyMatch(e -> e.getTargetId().equals(targetId)
                                            && e.getRelationship() == finalRel && e.isCurrent())
                                    || existingFromTarget.stream()
                                    .anyMatch(e -> e.getTargetId().equals(sourceId)
                                            && e.getRelationship() == finalRel && e.isCurrent());
                        } else {
                            sameCurrentExists = existingFromSource.stream()
                                    .anyMatch(e -> e.getTargetId().equals(targetId)
                                            && e.getRelationship() == finalRel && e.isCurrent());
                        }
                        if (sameCurrentExists) continue;

                        // 关系转变：旧的同类对称边失效（如 ALLY→ENEMY）
                        if (finalRel.isSymmetric()) {
                            existingFromSource.stream()
                                    .filter(e -> e.getTargetId().equals(targetId)
                                            && e.getRelationship() != finalRel
                                            && e.getRelationship().isSymmetric()
                                            && e.isCurrent())
                                    .forEach(e -> { e.expire(chapterIndex); repository.saveEntityEdge(e); });
                            existingFromTarget.stream()
                                    .filter(e -> e.getTargetId().equals(sourceId)
                                            && e.getRelationship() != finalRel
                                            && e.getRelationship().isSymmetric()
                                            && e.isCurrent())
                                    .forEach(e -> { e.expire(chapterIndex); repository.saveEntityEdge(e); });
                        }

                        // 创建新边（标记从本章开始生效）
                        EntityEdge edge = new EntityEdge(projectId, sourceId, targetId, finalRel);
                        edge.setWeight(weight);
                        edge.setValidFromChapter(chapterIndex);
                        repository.saveEntityEdge(edge);
                        newEdges++;
                    }
                }
            }
        } catch (Exception e) {
            // 解析失败不阻断流程，静默返回统计
            return new ExtractionResult(0, 0, 0,
                    "JSON 解析失败: " + e.getMessage() + "\n原始响应:\n" + json.substring(0, Math.min(500, json.length())));
        }

        return new ExtractionResult(newEntities, updatedEntities, newEdges, null);
    }

    /** 按规范名或别名匹配已有实体 */
    private Entity findMatchingEntity(List<Entity> entities, String name, List<String> aliases) {
        // 先精确匹配 name
        for (Entity e : entities) {
            if (e.getName().equals(name)) return e;
        }
        // 再别名匹配
        for (Entity e : entities) {
            if (e.getAliases() != null && e.getAliases().contains(name)) return e;
        }
        // 新实体别名与已有实体 name 匹配
        for (String alias : aliases) {
            for (Entity e : entities) {
                if (e.getName().equals(alias)) return e;
            }
        }
        return null;
    }

    private String findEntityIdByName(List<Entity> entities, String name) {
        for (Entity e : entities) {
            if (e.getName().equals(name)) return e.getId();
        }
        for (Entity e : entities) {
            if (e.getAliases() != null && e.getAliases().contains(name)) return e.getId();
        }
        return null;
    }

    /** 格式化已有实体为 JSON 清单（供 LLM 参考） */
    private String formatExistingEntities(List<Entity> entities) {
        if (entities.isEmpty()) return "（暂无）";
        return entities.stream()
                .map(e -> "  " + e.getName() + " (" + e.getType() + ")"
                        + (e.getAliases().isEmpty() ? "" : " 别名: " + String.join(", ", e.getAliases())))
                .collect(Collectors.joining("\n"));
    }

    /** 提取结果 */
    public record ExtractionResult(int newEntities, int updatedEntities, int newEdges, String error) {}
}
