package com.writing.novel.service;

import com.writing.novel.agent.EntityExtractorAgent;
import com.writing.novel.domain.*;
import com.writing.novel.spi.NovelRepository;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 实体图服务——图数据库的读写入口。
 *
 * 写流程：每章写完后 → extractFromChapter() → LLM 提取实体+关系 → 持久化
 * 读流程：每章写前 → recallConsistencySection() → 图遍历召回 → 格式化一致性文本
 */
public class EntityGraphService {

    private static final int RECENT_CHAPTER_WINDOW = 5;
    private static final int MAX_ENTITIES_PER_TYPE = 5;
    private static final int MAX_TOTAL_ENTITIES = 25;

    private final NovelRepository repository;
    private final EntityExtractorAgent extractor;

    public EntityGraphService(NovelRepository repository, EntityExtractorAgent extractor) {
        this.repository = repository;
        this.extractor = extractor;
    }

    /**
     * 写后：调用 LLM 提取本章实体和关系，持久化到图数据库。
     */
    public EntityExtractorAgent.ExtractionResult extractFromChapter(String projectId, Chapter chapter) {
        return extractor.extract(projectId, chapter);
    }

    public EntityExtractorAgent.ExtractionResult extractFromChapter(
            String projectId, Chapter chapter, ChapterRevisionService.DerivedWriteBoundary boundary) {
        return extractor.extract(projectId, chapter, boundary);
    }

    /**
     * 写前：从图数据库召回相关实体，格式化为一致性提醒文本。
     *
     * 召回策略：
     * 1. 最近 N 章出现过的实体（按 last_seen_chapter）
     * 2. 本章场景地点/势力的 1 跳邻居（通过边表）
     * 3. 上述两集合的联合，按 recency > 关系权重 > 类型优先级排序
     */
    public String recallConsistencySection(String projectId, int currentChapter) {
        List<Entity> entities = new ArrayList<>();

        // 1. 最近 N 章出现过的实体
        int fromChapter = Math.max(1, currentChapter - RECENT_CHAPTER_WINDOW);
        entities.addAll(repository.findEntitiesInChapterRange(projectId, fromChapter, currentChapter));

        // 2. 尝试扩展: 从最近实体的 1 跳邻居补充（图遍历）
        Set<String> entityIds = entities.stream().map(Entity::getId).collect(Collectors.toSet());
        Set<String> neighborIds = new HashSet<>();

        for (Entity e : entities) {
            // 出边
            for (EntityEdge edge : repository.findEdgesBySource(e.getId())) {
                if (!entityIds.contains(edge.getTargetId()) && !neighborIds.contains(edge.getTargetId())) {
                    neighborIds.add(edge.getTargetId());
                }
            }
            // 入边
            for (EntityEdge edge : repository.findEdgesByTarget(e.getId())) {
                if (!entityIds.contains(edge.getSourceId()) && !neighborIds.contains(edge.getSourceId())) {
                    neighborIds.add(edge.getSourceId());
                }
            }
        }

        // 加载邻居实体
        for (String nid : neighborIds) {
            repository.findEntity(nid).ifPresent(entities::add);
        }

        if (entities.isEmpty()) return "";

        // 3. 按类型分组 + 限流
        Map<EntityType, List<Entity>> byType = entities.stream()
                .collect(Collectors.groupingBy(Entity::getType));

        StringBuilder sb = new StringBuilder();
        sb.append("【关键实体一致性（以下名称前文已确立，本章必须严格使用）】\n");

        // 类型优先级顺序
        EntityType[] priorityOrder = {
                EntityType.CHARACTER, EntityType.ITEM, EntityType.LOCATION,
                EntityType.TECHNIQUE, EntityType.FACTION, EntityType.EVENT, EntityType.CONCEPT
        };

        int totalCount = 0;
        for (EntityType type : priorityOrder) {
            List<Entity> typed = byType.getOrDefault(type, List.of());
            if (typed.isEmpty()) continue;

            // 按 last_seen_chapter 降序排序
            typed.sort((a, b) -> Integer.compare(b.getLastSeenChapter(), a.getLastSeenChapter()));

            List<Entity> limited = typed.subList(0, Math.min(typed.size(), MAX_ENTITIES_PER_TYPE));
            String typeLabel = switch (type) {
                case CHARACTER -> "人物";
                case ITEM -> "物品";
                case LOCATION -> "地点";
                case TECHNIQUE -> "功法";
                case FACTION -> "势力";
                case EVENT -> "事件";
                case CONCEPT -> "概念";
            };

            sb.append("■ ").append(typeLabel).append("：");
            sb.append(limited.stream().map(Entity::getName).collect(Collectors.joining("、")));
            sb.append("\n");
            totalCount += limited.size();

            if (totalCount >= MAX_TOTAL_ENTITIES) break;
        }

        // 4. 别名警告（防止 LLM 使用错误名字）
        List<Entity> withAliases = entities.stream()
                .filter(e -> e.getAliases() != null && !e.getAliases().isEmpty())
                .toList();
        if (!withAliases.isEmpty()) {
            sb.append("⚠️ 别名警告：");
            List<String> warnings = withAliases.stream()
                    .flatMap(e -> e.getAliases().stream()
                            .map(a -> "「" + e.getName() + "」不能写成「" + a + "」"))
                    .limit(5)
                    .toList();
            sb.append(String.join("；", warnings));
            sb.append("\n");
        }

        // 5. 当前有效关系边注入（只输出 CHARACTER 之间的关系）
        appendCurrentRelationships(sb, entities);

        return sb.toString();
    }

    /** 输出当前有效的人物间关系边 */
    private void appendCurrentRelationships(StringBuilder sb, List<Entity> entities) {
        var charEntities = entities.stream()
                .filter(e -> e.getType() == EntityType.CHARACTER)
                .toList();
        if (charEntities.size() < 2) return;

        Map<String, String> idToName = charEntities.stream()
                .collect(Collectors.toMap(Entity::getId, Entity::getName, (a, b) -> a));

        List<String> relLines = new ArrayList<>();
        Set<String> seen = new HashSet<>(); // 去重对称关系
        for (Entity e : charEntities) {
            for (EntityEdge edge : repository.findEdgesBySource(e.getId())) {
                if (!edge.isCurrent()) continue; // 只输出当前有效的
                if (!idToName.containsKey(edge.getTargetId())) continue; // 目标不在本章角色中
                String sourceName = idToName.get(edge.getSourceId());
                String targetName = idToName.get(edge.getTargetId());
                String pair = sourceName.compareTo(targetName) < 0
                        ? sourceName + "↔" + targetName
                        : targetName + "↔" + sourceName;
                if (seen.contains(pair + edge.getRelationship())) continue;
                seen.add(pair + edge.getRelationship());
                String relZh = relationshipToChinese(edge.getRelationship());
                String timeNote = edge.getValidFromChapter() > 0
                        ? "（第" + edge.getValidFromChapter() + "章起）"
                        : "";
                relLines.add(sourceName + "→" + targetName + "：" + relZh + timeNote);
            }
        }
        if (!relLines.isEmpty()) {
            sb.append("■ 当前关系动态（本章角色间的关系必须与此一致）：\n");
            for (String line : relLines) sb.append("  ").append(line).append("\n");
        }
    }

    private String relationshipToChinese(RelationshipType rel) {
        return switch (rel) {
            case OWNS -> "拥有";
            case LOCATED_IN -> "位于";
            case MEMBER_OF -> "隶属";
            case CREATES -> "创造";
            case USES -> "使用";
            case ALLY -> "盟友";
            case ENEMY -> "敌对";
            case LOVE -> "恋人";
            case RIVAL -> "竞争";
            case MENTOR -> "师徒";
            case BETRAYS -> "背叛";
            case SUSPECTS -> "怀疑";
            case PROTECTS -> "守护";
            case SERVES -> "效力";
            case CONTAINS -> "包含";
            case PRODUCES -> "生产";
            case KNOWS -> "认识";
            case RELATED_TO -> "关联";
        };
    }

    /**
     * 别名 → 规范名解析。
     * 如果 rawName 是某个实体的别名，返回实体规范名；否则返回 rawName 本身。
     */
    public String resolveCanonical(String projectId, String rawName) {
        if (rawName == null || rawName.isBlank()) return rawName;
        // 先精确匹配
        var entity = repository.findEntityByName(projectId, rawName);
        if (entity.isPresent()) return entity.get().getName();
        // NCS-109: 别名回解——「林公子」→规范名「林凡」（此前只查 name，别名入表后无法回解，
        // 真典/召回两侧 subject 分裂）
        for (Entity e : repository.findEntitiesByProject(projectId)) {
            if (e.getAliases() != null && e.getAliases().contains(rawName)) return e.getName();
        }
        return rawName;
    }

    /**
     * 获取最近出现过的实体的规范名列表（用于拼入约束检查）。
     */
    public List<String> getRecentEntityNames(String projectId, int currentChapter) {
        int fromChapter = Math.max(1, currentChapter - RECENT_CHAPTER_WINDOW);
        return repository.findEntitiesInChapterRange(projectId, fromChapter, currentChapter)
                .stream()
                .map(Entity::getName)
                .toList();
    }
}
