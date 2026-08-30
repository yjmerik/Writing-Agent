package com.writing.novel.service;

import com.writing.novel.domain.Character;
import com.writing.novel.domain.KnowledgeEntry;
import com.writing.novel.domain.Outline;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * p11-q1-01: 构思期质量门禁——大纲/世界观/人物生成后实时校验（用户目标1：过程中实时审查）。
 * <p>
 * 软门禁：不过关 → 带反馈重生成（有限次），仍不过则放行（q1-03 标 quality_risk + 人机暂停）。
 * 本类先做低成本规则档（去重/结构/完整性），语义档（LLM 自洽性）可在 verify 时按需启用。
 */
@Component
public class ConceiveGate {

    /** 门禁结果：pass + 问题清单 + 给生成器的反馈 */
    public record GateResult(boolean pass, List<String> issues, String feedback) {
        public static GateResult ok() { return new GateResult(true, List.of(), ""); }
    }

    /**
     * 校验世界观产出：力量体系存在/势力不重复/条目完整性。
     */
    public GateResult checkWorldbuilding(List<KnowledgeEntry> entries, String tags) {
        return checkWorldbuilding(entries, tags, null);
    }

    /**
     * p17-04: 带题材的世界观校验——POWER_SYSTEM 仅对力量体系核心题材
     * （CULTIVATION/FANTASY/SCIFI）强制；MYSTERY/ROMANCE/LITERARY/HISTORICAL/THRILLER
     * 豁免（纯悬疑/言情/历史正剧无超自然力量体系，强制要求会迫使 LLM 编造无关设定），
     * p21-01: WUXIA 亦豁免——传统武侠（非玄幻）武学有源有代价即可，超自然修炼流属 CULTIVATION。
     * 完整性要求（≥3 类设定、不重名）不变。genre 为 null 时按 tags 自动检测。
     */
    public GateResult checkWorldbuilding(List<KnowledgeEntry> entries, String tags,
                                         com.writing.novel.domain.GenreProfile genre) {
        if (entries == null || entries.isEmpty()) {
            return new GateResult(false, List.of("世界观条目为空"),
                    "世界观条目为空，需重新生成包含核心设定、势力、规则的完整世界观。");
        }
        com.writing.novel.domain.GenreProfile effective = genre != null ? genre
                : new GenreDetector().detect(tags, null);
        boolean powerSystemRequired = switch (effective) {
            case CULTIVATION, FANTASY, SCIFI -> true;
            default -> false;
        };
        List<String> issues = new ArrayList<>();
        // 力量体系是核心（修仙/玄幻/科幻）；非力量体系题材改为核心设定维度检查（下方完整性）
        if (powerSystemRequired) {
            boolean hasPowerSystem = entries.stream()
                    .anyMatch(e -> "POWER_SYSTEM".equalsIgnoreCase(e.getType()));
            if (!hasPowerSystem) issues.add("缺少力量体系（POWER_SYSTEM）设定");
        }
        // 同类条目重名
        Set<String> seen = new HashSet<>();
        for (KnowledgeEntry e : entries) {
            if (e.getName() == null) continue;
            String key = e.getType() + "|" + e.getName();
            if (!seen.add(key)) issues.add("重复的设定条目：" + e.getName() + "（" + e.getType() + "）");
        }
        // 完整性：条目数过少（豁免题材仍要求核心设定维度非空）
        if (entries.size() < 3) issues.add("世界观条目过少（" + entries.size() + "，建议≥3 类设定）");
        if (issues.isEmpty()) return GateResult.ok();
        String feedback = "世界观质量问题：" + String.join("；", issues)
                + "。请重新生成更完整、不重复的世界观设定"
                + (powerSystemRequired ? "（含力量体系）" : "") + "。";
        return new GateResult(false, issues, feedback);
    }

    /**
     * p11-q1-02: 校验人物产出：主角存在/人物不重名/主角有能力设定。
     */
    public GateResult checkCharacters(java.util.List<Character> characters) {
        if (characters == null || characters.isEmpty()) {
            return new GateResult(false, List.of("人物卡为空"), "人物卡为空，需重新设计主角/对手/配角。");
        }
        List<String> issues = new ArrayList<>();
        boolean hasProtagonist = characters.stream()
                .anyMatch(c -> c.getRole() != null && c.getRole().contains("主角"));
        if (!hasProtagonist) issues.add("缺少主角");
        Set<String> names = new HashSet<>();
        for (Character c : characters) {
            if (c.getName() == null) continue;
            if (!names.add(c.getName())) issues.add("重复人物：" + c.getName());
        }
        characters.stream()
                .filter(c -> c.getRole() != null && c.getRole().contains("主角"))
                .findFirst()
                .ifPresent(mc -> {
                    if (mc.getAbility() == null || mc.getAbility().isBlank()) issues.add("主角缺少能力设定");
                });
        if (issues.isEmpty()) return GateResult.ok();
        return new GateResult(false, issues, "人物质量问题：" + String.join("；", issues) + "。");
    }

    /**
     * p11-q1-02: 校验大纲产出：总纲（premise）存在/分卷规划存在。
     */
    public GateResult checkOutline(Outline outline) {
        if (outline == null) {
            return new GateResult(false, List.of("大纲为空"), "大纲为空，需重新规划分卷与章节。");
        }
        List<String> issues = new ArrayList<>();
        if (outline.getPremise() == null || outline.getPremise().isBlank()) issues.add("缺少总纲（premise）");
        if (outline.getVolumes() == null || outline.getVolumes().isEmpty()) issues.add("缺少分卷规划");
        if (issues.isEmpty()) return GateResult.ok();
        return new GateResult(false, issues, "大纲质量问题：" + String.join("；", issues) + "。");
    }
}
