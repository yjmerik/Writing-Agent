package com.writing.novel.memory;

import com.writing.novel.domain.Character;
import com.writing.novel.domain.Entity;
import com.writing.novel.domain.StoryFact;
import com.writing.novel.spi.StoryCanon;
import com.writing.novel.spi.NovelRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * p10-w2-04: 检索收窄门面——替代消费者直接 findAll* 全量加载（根因 A）。
 * <p>
 * 百万字下各 checker/tracker 若全量加载角色/事实/边，单次 LLM 输入 token 爆炸且检测失明。
 * 本门面按 cast/synopsis/chapterIndex 返回相关切片（cap），让 ContextAssembler/ContinuityChecker/
 * ConsistencyGuard 等消费者经此收窄输入。factRepo==null 时事实切片为空（向后兼容）。
 */
@Component
public class RelevanceGateway {

    private final NovelRepository repository;
    private final StoryCanon canon;

    public RelevanceGateway(NovelRepository repository, StoryCanon canon) {
        this.repository = repository;
        this.canon = canon;
    }

    /**
     * 本章相关角色：cast 精确/别名匹配 + 主角保底，cap（不回退全量）。
     * 修复 buildCharacterVoiceSection cast 匹配失败即全量回退的 GAP（百万字下 token 爆炸）。
     */
    public List<Character> relevantCharacters(String projectId, List<String> cast, int cap) {
        List<Character> all = repository.findCharactersByProject(projectId);
        if (all.isEmpty()) return List.of();
        List<Character> out = new ArrayList<>();
        // 1. cast 精确/包含匹配
        if (cast != null) {
            for (Character c : all) {
                if (out.size() >= cap) break;
                if (matchesCast(c, cast) && !out.contains(c)) out.add(c);
            }
        }
        // 2. 主角保底（主角不依赖 cast 命中，避免名字漂移失锚）
        // p15-fix: 仅精确"主角"可占首位；substring 匹配者追加队尾——锚点位漂移会改变 prompt 指纹，破坏 record→replay 确定性
        for (Character c : all) {
            if (out.size() >= cap) break;
            String role = c.getRole();
            if ("主角".equals(role) && !out.contains(c)) {
                out.add(0, c);
            }
        }
        for (Character c : all) {
            if (out.size() >= cap) break;
            String role = c.getRole();
            if (role != null && (role.contains("主角") || role.contains("男主") || role.contains("女主"))
                    && !"主角".equals(role) && !out.contains(c)) {
                out.add(c);
            }
        }
        return out;
    }

    /**
     * 本章相关事实：cast 关联的 ACTIVE 事实（subject/assertion 含 cast 名字），cap。
     * 无 cast 时按 importance 降序取 top-cap。
     */
    public List<StoryFact> relevantFacts(String projectId, List<String> cast, int cap) {
        return relevantFacts(projectId, cast, 0, cap);
    }

    public List<StoryFact> relevantFacts(String projectId, List<String> cast, int chapterIndex, int cap) {
        if (canon == null) return List.of();
        List<StoryFact> all = canon.recall(projectId);
        if (all.isEmpty()) return List.of();
        // NCS-098: 裁决前的极性冲突对不注入——双方同时注入会向写作/检查上下文输入自相矛盾约束；
        // 冲突对留待 RepairArbiter 三分类裁决或作者人工补丁处置
        all = withoutConflictPairs(all);
        // NCS-109: 时间有效性过滤——生效区间外的事实不注入（chapterIndex=0 视为上下文未知，保守放行）
        if (chapterIndex > 0) {
            final int ch = chapterIndex;
            all = all.stream().filter(f ->
                    f.getValidFromChapter() <= ch
                            && (f.getValidToChapter() == null || f.getValidToChapter() >= ch))
                    .toList();
        }
        List<String> names = cast == null ? List.of() : expandCastWithAliases(projectId, cast);
        List<StoryFact> must = new ArrayList<>();
        List<StoryFact> fill = new ArrayList<>();
        boolean hasCast = !names.isEmpty();
        for (StoryFact f : all) {
            if (isMustInclude(f, names, chapterIndex)) {
                if (!containsFact(must, f)) must.add(f);
            } else if (!hasCast || matchesCast(f, names)) {
                fill.add(f);
            }
        }
        fill.sort((a, b) -> Integer.compare(score(b, chapterIndex), score(a, chapterIndex)));
        // NCS-109: must 截断保序——cast 命中 > 人工补丁 > importance > recency
        // （旧序为 recall 的 importance 降序，must 膨胀时 cast 关键状态被静默丢弃）
        List<String> finalNames = names;
        must.sort(Comparator
                .comparing((StoryFact f) -> matchesCast(f, finalNames) ? 0 : 1)
                .thenComparing(f -> isHumanPatch(f) ? 0 : 1)
                .thenComparing(f -> -f.getImportance())
                .thenComparing(f -> -score(f, chapterIndex)));
        List<StoryFact> out = new ArrayList<>(must);
        int mustCap = Math.min(must.size(), Math.max(cap, 24));
        if (out.size() > mustCap) out = new ArrayList<>(out.subList(0, mustCap));
        for (StoryFact f : fill) {
            if (out.size() >= cap) break;
            if (!containsFact(out, f)) out.add(f);
        }
        // NCS-114: 探索配额——importance>=4 且长窗（60 章）未验证的 ACTIVE 事实保底注入 1 条，
        // 打破 VALIDATED-recency 马太效应（从未被复述的重要事实不再永久沉底）
        final List<StoryFact> pool = all;
        final List<StoryFact> injected = out;
        boolean hasExploration = injected.stream().anyMatch(f ->
                f.getImportance() >= 4 && chapterIndex - f.getLastValidatedChapter() >= 60);
        if (!hasExploration && chapterIndex > 0) {
            StoryFact candidate = pool.stream()
                    .filter(f -> !containsFact(injected, f))
                    .filter(f -> f.getImportance() >= 4 && chapterIndex - f.getLastValidatedChapter() >= 60)
                    .max(java.util.Comparator.comparingInt(StoryFact::getImportance))
                    .orElse(null);
            if (candidate != null) {
                if (out.size() >= cap && !out.isEmpty()) out.remove(out.size() - 1);
                out.add(candidate);
            }
        }
        return out;
    }

    private static boolean isHumanPatch(StoryFact f) {
        String assertion = f.getAssertion() == null ? "" : f.getAssertion();
        return assertion.startsWith(StoryCanon.HUMAN_PATCH_PREFIX);
    }

    /**
     * NCS-109: cast 别名扩展——大纲写「林公子」时补上规范名「林凡」，
     * 使真典/角色匹配不再因别名 miss（原纯 contains 双向匹配的单向盲区）。
     */
    List<String> expandCastWithAliases(String projectId, List<String> cast) {
        if (cast == null || cast.isEmpty()) return cast == null ? List.of() : cast;
        List<Entity> entities = repository.findEntitiesByProject(projectId);
        if (entities.isEmpty()) return cast;
        List<String> expanded = new ArrayList<>(cast);
        for (String name : cast) {
            if (name == null || name.isBlank()) continue;
            for (Entity e : entities) {
                boolean aliasHit = (e.getAliases() != null && e.getAliases().contains(name));
                if (aliasHit && !expanded.contains(e.getName())) {
                    expanded.add(e.getName());
                    break;
                }
            }
        }
        return expanded;
    }

    private boolean isMustInclude(StoryFact f, List<String> cast, int chapterIndex) {
        String assertion = f.getAssertion() == null ? "" : f.getAssertion();
        if (assertion.startsWith("【HUMAN_PATCH】")) return true;
        // p32-06: 待核铁律不恒注入——确认级才以铁律身份约束上下文
        if (f.getFactType() == StoryFact.FactType.WORLD_RULE && f.getImportance() >= 4
                && f.getEvidenceGrade() == StoryFact.EvidenceGrade.CONFIRMED) return true;
        if (matchesCast(f, cast) && (f.getFactType() == StoryFact.FactType.CHARACTER_STATE
                || f.getFactType() == StoryFact.FactType.RELATIONSHIP_STATE)) return true;
        if (f.getFactType() == StoryFact.FactType.PLOT_COMMITMENT
                && (f.getImportance() >= 4 || (chapterIndex > 0 && chapterIndex - f.getLastValidatedChapter() <= 30))) {
            return true;
        }
        return false;
    }

    private boolean matchesCast(StoryFact f, List<String> cast) {
        if (cast == null || cast.isEmpty()) return false;
        return cast.stream().anyMatch(name -> name != null && !name.isBlank()
                && ((f.getSubject() != null && f.getSubject().contains(name))
                || (f.getAssertion() != null && f.getAssertion().contains(name))));
    }

    private int score(StoryFact f, int chapterIndex) {
        int recency = chapterIndex > 0 ? Math.max(0, 40 - Math.abs(chapterIndex - f.getLastValidatedChapter())) : 0;
        return f.getImportance() * 2 + recency;
    }

    private boolean containsFact(List<StoryFact> list, StoryFact f) {
        return list.stream().anyMatch(x -> x.getId().equals(f.getId()));
    }

    /**
     * NCS-098: 过滤同 subject+type+predicate 且极性相反的 ACTIVE 冲突对（双方整体排除）。
     * 与 SqliteStoryCanon.harvest 的冲突判定（同谓词相反极性）同一口径。
     */
    public static List<StoryFact> withoutConflictPairs(List<StoryFact> facts) {
        java.util.Map<String, java.util.List<StoryFact>> groups = new java.util.HashMap<>();
        for (StoryFact f : facts) {
            if (f.getPredicate() == null) continue;
            groups.computeIfAbsent(f.getSubject() + "|" + f.getFactType() + "|" + f.getPredicate(),
                    k -> new ArrayList<>()).add(f);
        }
        java.util.Set<String> conflicted = new java.util.HashSet<>();
        for (var e : groups.entrySet()) {
            var group = e.getValue();
            if (group.size() < 2) continue;
            boolean hasConflict = group.stream().anyMatch(a -> a.getPolarity() != null
                    && group.stream().anyMatch(b -> b != a && b.getPolarity() != null
                    && !a.getPolarity().equals(b.getPolarity())));
            if (hasConflict) {
                group.forEach(f -> conflicted.add(f.getId()));
            }
        }
        if (conflicted.isEmpty()) return facts;
        return facts.stream().filter(f -> !conflicted.contains(f.getId())).toList();
    }

    /**
     * p11-q0-01: 本章相关实体：按 keywords（角色名/关键名词）匹配实体名或别名，cap。
     * 无 keywords 时按 lastSeenChapter 倒序取最近 cap 个（recency 收窄）。
     * 供 EntityExtractor 收窄进 LLM 的参考清单（token 受控）。
     * 注意：Java 层去重仍需全量实体，本方法仅收窄"进 LLM 的参考视图"。
     */
    public List<Entity> relevantEntities(String projectId, List<String> keywords, int cap) {
        List<Entity> all = repository.findEntitiesByProject(projectId);
        if (all.isEmpty()) return List.of();
        if (keywords == null || keywords.isEmpty()) {
            return all.stream()
                    .sorted(Comparator.comparingInt(Entity::getLastSeenChapter).reversed())
                    .limit(cap)
                    .toList();
        }
        List<Entity> out = new ArrayList<>();
        for (Entity e : all) {
            if (out.size() >= cap) break;
            if (matchesKeywords(e.getName(), keywords) || aliasMatchesKeywords(e, keywords)) {
                out.add(e);
            }
        }
        return out;
    }

    private boolean matchesKeywords(String name, List<String> keywords) {
        if (name == null) return false;
        return keywords.stream().anyMatch(k -> k != null && !k.isBlank()
                && (name.contains(k) || k.contains(name)));
    }

    private boolean aliasMatchesKeywords(Entity e, List<String> keywords) {
        if (e.getAliases() == null) return false;
        return e.getAliases().stream().anyMatch(a -> matchesKeywords(a, keywords));
    }

    private boolean matchesCast(Character c, List<String> cast) {
        String name = c.getName();
        if (name == null) return false;
        return cast.stream().anyMatch(n -> n != null && (n.contains(name) || name.contains(n)));
    }
}
