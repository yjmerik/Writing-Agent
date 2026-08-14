package com.writing.novel.service;

import com.writing.novel.domain.KnowledgeEntry;
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
        if (entries == null || entries.isEmpty()) {
            return new GateResult(false, List.of("世界观条目为空"),
                    "世界观条目为空，需重新生成包含力量体系、势力、规则的完整设定。");
        }
        List<String> issues = new ArrayList<>();
        // 力量体系是核心（修仙/玄幻尤其）
        boolean hasPowerSystem = entries.stream()
                .anyMatch(e -> "POWER_SYSTEM".equalsIgnoreCase(e.getType()));
        if (!hasPowerSystem) issues.add("缺少力量体系（POWER_SYSTEM）设定");
        // 同类条目重名
        Set<String> seen = new HashSet<>();
        for (KnowledgeEntry e : entries) {
            if (e.getName() == null) continue;
            String key = e.getType() + "|" + e.getName();
            if (!seen.add(key)) issues.add("重复的设定条目：" + e.getName() + "（" + e.getType() + "）");
        }
        // 完整性：条目数过少
        if (entries.size() < 3) issues.add("世界观条目过少（" + entries.size() + "，建议≥3 类设定）");
        if (issues.isEmpty()) return GateResult.ok();
        String feedback = "世界观质量问题：" + String.join("；", issues)
                + "。请重新生成更完整、不重复、含力量体系的世界观设定。";
        return new GateResult(false, issues, feedback);
    }
}
