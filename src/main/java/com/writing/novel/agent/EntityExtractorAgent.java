package com.writing.novel.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.domain.*;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmTaskType;
import com.writing.novel.llm.prompt.PromptTemplateService;
import com.writing.novel.memory.RelevanceGateway;
import com.writing.novel.service.ChapterRevisionService;
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
     * NCS-067: 拆为两阶段调用——「紧凑实体」「关系（仅引用第一步规范名）」。
     * 单次全量输出（实体+关系+描述）需求约 9.4k 字符，必然撞输出预算截断；
     * 拆分后各自输出体量可控。任一阶段失败返回 error（上游保持账本可恢复），
     * 恢复重跑时实体按 name/aliases 去重、边按同类当前边去重，不产生重复数据。
     *
     * @return 提取结果统计
     */
    public ExtractionResult extract(String projectId, Chapter chapter) {
        return extract(projectId, chapter, null);
    }

    public ExtractionResult extract(String projectId, Chapter chapter,
                                    ChapterRevisionService.DerivedWriteBoundary boundary) {
        // 已有实体列表（Java 层去重需全量，保证不产生重复实体）
        List<Entity> existingEntities = repository.findEntitiesByProject(projectId);
        // p11-q0-01: 进 LLM 的参考清单经 RelevanceGateway 收窄（recency），token 受控
        List<Entity> llmReference = relevanceGateway != null
                ? relevanceGateway.relevantEntities(projectId, null, 40)
                : existingEntities;
        String existingJson = formatExistingEntities(llmReference);
        // p9-5-02: 附加真典已确立的角色/物品事实（避免重复提取已确立内容）
        if (relevanceGateway != null) {
            String canon = relevanceGateway.relevantFacts(projectId, null, 20).stream()
                    .filter(f -> f.getFactType() == StoryFact.FactType.OBJECT_STATE
                            || f.getFactType() == StoryFact.FactType.CHARACTER_STATE)
                    .map(f -> f.getSubject() + ":" + f.getAssertion())
                    .collect(Collectors.joining("; "));
            if (!canon.isBlank()) existingJson = existingJson + "\n已确立事实: " + canon;
        }

        // p9-5: 截断正文到 3000 字，避免超 token 导致 LLM 返回空
        String chapterContent = chapter.getContent();
        if (chapterContent != null && chapterContent.length() > 3000) {
            chapterContent = chapterContent.substring(0, 3000);
        }

        // ---- 阶段一：紧凑实体（无 description，输出体量受控）----
        String entityPrompt = promptService.render("entity-extraction", Map.of(
                "existingEntities", existingJson,
                "chapterContent", chapterContent != null ? chapterContent : ""));
        String entityUser = "请提取第" + chapter.getChapterIndex() + "章中的实体。";
        var entityReq = LlmRequest.builder()
                .taskType(LlmTaskType.EXTRACTION)
                .systemPrompt(entityPrompt)
                .userMessage(entityUser)
                .temperature(0.1)
                .maxTokens(8000)
                .build();
        final String entityRetryUser = entityUser
                + "\n上一次输出不是合法 JSON（可能被截断）。请只输出 JSON 本体，实体至多40个，不要输出任何解释。";
        var entityWrite = (java.util.function.Supplier<EntityStage>) () -> {
            EntityStage stage = parseAndSaveEntities(gateway.complete(entityReq).getContent(),
                    projectId, chapter.getChapterIndex(), existingEntities);
            if (stage.error() != null) {
                // NCS-068: 可判定无效输出（JSON 解析失败）单次带因重试；失败终态不变
                emitStructuredRetry("entity-extraction.entities", stage.error());
                var retryReq = LlmRequest.builder()
                        .taskType(LlmTaskType.EXTRACTION)
                        .systemPrompt(entityPrompt)
                        .userMessage(entityRetryUser)
                        .temperature(0.1)
                        .maxTokens(8000)
                        .build();
                stage = parseAndSaveEntities(gateway.complete(retryReq).getContent(),
                        projectId, chapter.getChapterIndex(), existingEntities);
            }
            return stage;
        };
        EntityStage entityStage = boundary == null ? entityWrite.get()
                : boundary.apply(entityWrite, new EntityStage(List.of(), 0, 0, "stale chapter version"));
        if (entityStage.error() != null) {
            return new ExtractionResult(entityStage.newEntities(), entityStage.updatedEntities(), 0, entityStage.error());
        }
        if (entityStage.chapterEntityNames().isEmpty()) {
            // 本章无实体则无从建边；有效空结果直接成功
            return new ExtractionResult(0, entityStage.updatedEntities(), 0, null);
        }

        // ---- 阶段二：关系（只引用阶段一的规范名清单）----
        String entityNames = String.join("、", entityStage.chapterEntityNames());
        String edgePrompt = promptService.render("entity-edges", Map.of(
                "entityNames", entityNames,
                "chapterContent", chapterContent != null ? chapterContent : ""));
        String edgeUser = "请提取第" + chapter.getChapterIndex() + "章中实体之间的关系。";
        var edgeReq = LlmRequest.builder()
                .taskType(LlmTaskType.EXTRACTION)
                .systemPrompt(edgePrompt)
                .userMessage(edgeUser)
                .temperature(0.1)
                .maxTokens(8000)
                .build();
        final String edgeRetryUser = edgeUser
                + "\n上一次输出不是合法 JSON（可能被截断）。请只输出 JSON 本体，只保留正文明确支持的关系，不要输出任何解释。";
        var edgeWrite = (java.util.function.Supplier<EdgeStage>) () -> {
            EdgeStage stage = parseAndSaveEdges(gateway.complete(edgeReq).getContent(),
                    projectId, chapter.getChapterIndex());
            if (stage.error() != null) {
                emitStructuredRetry("entity-extraction.edges", stage.error());
                var retryReq = LlmRequest.builder()
                        .taskType(LlmTaskType.EXTRACTION)
                        .systemPrompt(edgePrompt)
                        .userMessage(edgeRetryUser)
                        .temperature(0.1)
                        .maxTokens(8000)
                        .build();
                stage = parseAndSaveEdges(gateway.complete(retryReq).getContent(),
                        projectId, chapter.getChapterIndex());
            }
            return stage;
        };
        EdgeStage edgeStage = boundary == null ? edgeWrite.get()
                : boundary.apply(edgeWrite, new EdgeStage(0, "stale chapter version"));
        return new ExtractionResult(entityStage.newEntities(), entityStage.updatedEntities(),
                edgeStage.newEdges(), edgeStage.error());
    }

    /** 阶段一结果：本章出现过的实体规范名（新+更新，供阶段二引用）与计数 */
    private record EntityStage(List<String> chapterEntityNames, int newEntities, int updatedEntities, String error) {}

    /** 阶段二结果 */
    private record EdgeStage(int newEdges, String error) {}

    /** NCS-068: 有界重试事件——attempt 恒为 1，重试失败走原有可恢复终态 */
    private static void emitStructuredRetry(String phase, String reason) {
        com.writing.novel.telemetry.EventLog.emit("STRUCTURED_OUTPUT_RETRY", java.util.Map.of(
                "taskType", LlmTaskType.EXTRACTION.name(),
                "phase", phase,
                "attempt", 1,
                "reason", com.writing.novel.telemetry.EventLog.truncate(reason, 200)));
    }

    /** 解析阶段一实体 JSON 并落库；无效响应不落库并返回 error */
    private EntityStage parseAndSaveEntities(String json, String projectId,
                                             int chapterIndex, List<Entity> existingEntities) {
        int newEntities = 0;
        int updatedEntities = 0;
        List<String> chapterNames = new ArrayList<>();
        try {
            JsonNode root = parseJson(json);
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
                        existingEntities.add(entity); // 供边匹配
                        newEntities++;
                    }
                    chapterNames.add(name);
                }
            }
        } catch (Exception e) {
            // 解析失败不落库，返回可定位错误（NCS-064：调用方据此不标记完成）
            return new EntityStage(List.of(), newEntities, updatedEntities,
                    "实体 JSON 解析失败: " + e.getMessage() + "\n原始响应:\n"
                            + json.substring(0, Math.min(500, json.length())));
        }
        return new EntityStage(chapterNames, newEntities, updatedEntities, null);
    }

    /** 解析阶段二关系 JSON 并落库；无效响应返回 error（实体已落库，恢复时去重） */
    private EdgeStage parseAndSaveEdges(String json, String projectId, int chapterIndex) {
        int newEdges = 0;
        try {
            JsonNode root = parseJson(json);
            // 重新加载实体列表（含阶段一新建的）
            final List<Entity> allEntities = repository.findEntitiesByProject(projectId);

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
            return new EdgeStage(newEdges, "关系 JSON 解析失败: " + e.getMessage() + "\n原始响应:\n"
                    + json.substring(0, Math.min(500, json.length())));
        }
        return new EdgeStage(newEdges, null);
    }

    /** NCS-106: 中心化解析（剥围栏/类型化失败）；截断的 JSON 在此抛出异常 */
    private static JsonNode parseJson(String json) throws Exception {
        return com.writing.novel.llm.LlmJson.parseOrThrow(json, "实体/关系 JSON");
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
