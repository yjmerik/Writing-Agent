package com.writing.novel.service;

import com.writing.novel.domain.CharacterImpactAnalysis;
import com.writing.novel.spi.NovelRepository;
import com.writing.novel.telemetry.EventLog;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * NCS-046: 影响证据的依赖补查与修复闭包。
 *
 * <p>闭包 = 直接影响章（certain）∪ 隐含后果章（unknown 中按关系链扩展：
 * 出场人物重叠的章节）∪ 修复再扩展（预算内迭代，预算停止保留 unknown）。
 * 闭包驱动补查（每章一次 continuity recheck 入账）。
 */
@Service
public class ImpactClosureService {

    /** 闭包扩展预算（迭代上限）——预算停止时保留未知（不宣称无影响） */
    static final int EXPANSION_BUDGET = 3;

    private final NovelRepository repository;
    private final com.writing.novel.persistence.sqlite.CharacterImpactAnalysisRepository impactAnalyses;

    public ImpactClosureService(NovelRepository repository) {
        this(repository, null);
    }

    public ImpactClosureService(NovelRepository repository,
                                com.writing.novel.persistence.sqlite.CharacterImpactAnalysisRepository impactAnalyses) {
        this.repository = repository;
        this.impactAnalyses = impactAnalyses;
    }

    /** 闭包结果：待补查章清单（去重升序）+ 是否预算截断 */
    public record Closure(List<Integer> chapters, boolean budgetLimited, int iterations) {}

    /**
     * 从最新影响分析构建补查闭包。
     * @return 空=无影响分析可依据（调用方走全量检查）
     */
    public java.util.Optional<Closure> closureFor(String projectId, String characterId) {
        // 由仓储取最新分析（借 ContentController 注入的仓储语义——此处经 lambda 避免循环依赖）
        var latest = findLatest(projectId, characterId);
        if (latest.isEmpty()) return java.util.Optional.empty();
        var analysis = latest.get();
        var parsedLists = com.writing.novel.llm.LlmJson.parse(analysis.getListsJson());
        if (!parsedLists.ok() || parsedLists.node() == null) return java.util.Optional.empty();
        var lists = parsedLists.node();
        Set<Integer> closure = new LinkedHashSet<>();
        for (var n : lists.path("certainReferences")) closure.add(n.asInt());
        List<Integer> unknown = new ArrayList<>();
        for (var n : lists.path("unknownReferences")) unknown.add(n.asInt());

        boolean budgetLimited = false;
        int iterations = 0;
        // 隐含后果：unknown 章的正文含关系链人物（与目标人物同场人物）→ 入闭包
        Set<String> relatedNames = relatedNamesOf(projectId, characterId);
        for (Integer ch : unknown) {
            if (iterations >= EXPANSION_BUDGET && !closure.contains(ch)) {
                budgetLimited = true; // 预算停止保留未知
                break;
            }
            var content = repository.findChapterByIndex(projectId, ch)
                    .map(c -> c.getContent() == null ? "" : c.getContent()).orElse("");
            boolean implied = relatedNames.stream().anyMatch(content::contains);
            if (implied) {
                closure.add(ch);
            }
            iterations++;
        }
        var ordered = new ArrayList<>(closure);
        ordered.sort(Integer::compareTo);
        EventLog.emit("IMPACT_CLOSURE", java.util.Map.of(
                "characterId", characterId, "chapters", ordered.size(),
                "budgetLimited", budgetLimited));
        return java.util.Optional.of(new Closure(ordered, budgetLimited, iterations));
    }

    /** 目标人物的关系链（关系字段中出现的其他人物名——同场即隐含后果候选） */
    private Set<String> relatedNamesOf(String projectId, String characterId) {
        Set<String> names = new LinkedHashSet<>();
        repository.findCharactersByProject(projectId).stream()
                .filter(c -> characterId.equals(c.getId()))
                .findFirst()
                .ifPresent(c -> {
                    String rel = c.getRelationships() == null ? "" : c.getRelationships();
                    // 关系字段按「、/；/分号」切人名段
                    for (String seg : rel.split("[、；;，,]")) {
                        String name = seg.replaceAll("[（(].*", "").trim();
                        if (!name.isEmpty() && name.length() <= 12) names.add(name);
                    }
                });
        return names;
    }

    private java.util.Optional<CharacterImpactAnalysis> findLatest(String projectId, String characterId) {
        if (impactAnalyses == null) return java.util.Optional.empty();
        try {
            return impactAnalyses.findLatest(projectId, characterId);
        } catch (Exception e) {
            return java.util.Optional.empty();
        }
    }
}
