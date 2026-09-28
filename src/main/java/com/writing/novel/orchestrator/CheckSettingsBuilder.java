package com.writing.novel.orchestrator;

import com.writing.novel.domain.Foreshadowing;
import com.writing.novel.domain.Outline;
import com.writing.novel.domain.ChapterSynopsis;
import com.writing.novel.domain.Volume;
import com.writing.novel.memory.RelevanceGateway;
import com.writing.novel.spi.NovelRepository;

import java.util.Arrays;
import java.util.List;

/**
 * NCS-097: 检查器输入 settings 的唯一构建器——首查（ChapterProduction.qualityGate）
 * 与重查（NovelOrchestrator.recheckChapter）共用，保证同名检查判据一致。
 *
 * <p>内容：本章 cast 收窄的人物卡 + 全部知识条目 + 待回收伏笔（cap 20）。
 * 不含故事真典——真典由 ContinuityCheckerAgent 以带章节号的写作同源口径注入
 * （消除历史双通道注入）。
 */
public final class CheckSettingsBuilder {

    private CheckSettingsBuilder() {}

    public static String build(NovelRepository repository, RelevanceGateway relevanceGateway,
                               String projectId, int chapterIndex, List<String> cast) {
        StringBuilder sb = new StringBuilder();
        var characters = relevanceGateway != null
                ? relevanceGateway.relevantCharacters(projectId, cast, 10)
                : repository.findCharactersByProject(projectId);
        for (var c : characters) {
            sb.append(c.getName()).append("（").append(c.getRole()).append("）");
            if (c.getPersonality() != null) sb.append(" 性格：").append(c.getPersonality());
            if (c.getAbility() != null) sb.append(" 能力：").append(c.getAbility());
            sb.append("\n");
        }
        for (var e : repository.findKnowledgeEntriesByProject(projectId)) {
            sb.append("【").append(e.getName()).append("】").append(e.getContent()).append("\n");
        }
        // p5-c1/p16-04: 未回收伏笔台账 cap 20——mustResolve 恒在，其余按埋设先后
        var foreshadowings = repository.findUnresolvedForeshadowings(projectId).stream()
                .filter(f -> f.getPlantedAtChapter() <= chapterIndex)
                .sorted(java.util.Comparator
                        .comparing(Foreshadowing::isMustResolve).reversed()
                        .thenComparing(Foreshadowing::getPlantedAtChapter))
                .limit(20)
                .toList();
        if (!foreshadowings.isEmpty()) {
            sb.append("\n=== 待回收伏笔 ===\n");
            for (var f : foreshadowings) {
                sb.append("- ").append(f.getDescription())
                  .append("（埋于第").append(f.getPlantedAtChapter()).append("章）\n");
            }
        }
        return sb.toString();
    }

    /** 从大纲解析本章 cast（与首查口径一致；无大纲返回空） */
    public static List<String> castOf(NovelRepository repository, String projectId, int chapterIndex) {
        Outline outline = repository.findOutlineByProject(projectId).orElse(null);
        if (outline == null) return List.of();
        for (Volume v : outline.getVolumes()) {
            for (ChapterSynopsis s : v.getSynopses()) {
                if (s.getChapterIndex() == chapterIndex && s.getCast() != null && !s.getCast().isBlank()) {
                    return Arrays.stream(s.getCast().split("[,，、\\s]+"))
                            .map(String::trim).filter(x -> !x.isEmpty()).toList();
                }
            }
        }
        return List.of();
    }
}
