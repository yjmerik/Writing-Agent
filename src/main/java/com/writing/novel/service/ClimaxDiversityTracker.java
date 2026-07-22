package com.writing.novel.service;

import com.writing.novel.domain.ClimaxType;

import java.util.*;

/**
 * 爽点多样性追踪器——记录最近章节使用的爽点类型，生成轮换约束。
 */
public class ClimaxDiversityTracker {

    private static final int HISTORY_SIZE = 5;

    // 最近各章使用的爽点类型（chapterIndex → types）
    private final Map<Integer, List<ClimaxType>> history = new LinkedHashMap<>();

    /** 记录本章使用的爽点类型 */
    public void record(int chapterIndex, List<ClimaxType> types) {
        history.put(chapterIndex, new ArrayList<>(types));
        // 只保留最近 HISTORY_SIZE 章
        while (history.size() > HISTORY_SIZE) {
            history.remove(history.keySet().iterator().next());
        }
    }

    /**
     * 生成轮换约束文本。
     * 排除最近 3 章已使用的类型，推荐未使用的类型。
     */
    public String generateRotationConstraint() {
        Set<ClimaxType> recentlyUsed = new HashSet<>();
        List<Integer> recentChapters = new ArrayList<>(history.keySet());
        // 取最近 3 章
        int from = Math.max(0, recentChapters.size() - 3);
        for (int i = from; i < recentChapters.size(); i++) {
            recentlyUsed.addAll(history.get(recentChapters.get(i)));
        }

        // 未使用的类型
        Set<ClimaxType> unused = new LinkedHashSet<>(Arrays.asList(ClimaxType.values()));
        unused.removeAll(recentlyUsed);

        if (recentlyUsed.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("□ 爽点类型轮换：最近3章已使用【");
        sb.append(String.join("、", recentlyUsed.stream().map(this::toChinese).toList()));
        sb.append("】，本章禁止使用以上类型\n");

        if (!unused.isEmpty()) {
            sb.append("  建议使用：");
            sb.append(String.join(" / ", unused.stream().map(this::toChinese).toList()));
            sb.append("\n");
        }

        return sb.toString();
    }

    private String toChinese(ClimaxType type) {
        return switch (type) {
            case INFO_GAP -> "信息差";
            case STRATEGIC -> "智斗";
            case EMOTIONAL_REVERSAL -> "情感反转";
            case RULE_EXPLOIT -> "规则利用";
            case ANTI_EXPECTATION -> "反预期";
        };
    }
}
