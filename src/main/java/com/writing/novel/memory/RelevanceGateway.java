package com.writing.novel.memory;

import com.writing.novel.domain.Character;
import com.writing.novel.domain.Entity;
import com.writing.novel.domain.StoryFact;
import com.writing.novel.persistence.sqlite.StoryFactRepository;
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
    private final StoryFactRepository factRepo;

    public RelevanceGateway(NovelRepository repository, StoryFactRepository factRepo) {
        this.repository = repository;
        this.factRepo = factRepo;
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
        if (factRepo == null) return List.of();
        List<StoryFact> all = factRepo.findActive(projectId);
        if (all.isEmpty()) return List.of();
        List<String> names = cast == null ? List.of() : cast;
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
        List<StoryFact> out = new ArrayList<>(must);
        int mustCap = Math.min(must.size(), Math.max(cap, 24));
        if (out.size() > mustCap) out = new ArrayList<>(out.subList(0, mustCap));
        for (StoryFact f : fill) {
            if (out.size() >= cap) break;
            if (!containsFact(out, f)) out.add(f);
        }
        return out;
    }

    private boolean isMustInclude(StoryFact f, List<String> cast, int chapterIndex) {
        String assertion = f.getAssertion() == null ? "" : f.getAssertion();
        if (assertion.startsWith("【HUMAN_PATCH】")) return true;
        if (f.getFactType() == StoryFact.FactType.WORLD_RULE && f.getImportance() >= 4) return true;
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
