package com.writing.novel.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多维度多样性追踪器——追踪多个维度的重复，生成轮换约束。
 * 扩展 ClimaxDiversityTracker，增加开场方式和冲突模式维度。
 */
public class DiversityTracker {

    private static final int HISTORY_SIZE = 5;

    // 每个维度的历史（chapterIndex → 类型列表）
    private final Map<String, LinkedHashMap<Integer, List<String>>> dimensions = new LinkedHashMap<>();

    public DiversityTracker() {
        // 预定义维度
        dimensions.put("opening", new LinkedHashMap<>());    // 开场方式
        dimensions.put("conflict", new LinkedHashMap<>());   // 冲突模式
        dimensions.put("climax", new LinkedHashMap<>());     // 爽点类型
    }

    /** 记录某章在某维度使用的类型 */
    public void record(String dimension, int chapterIndex, List<String> types) {
        var history = dimensions.computeIfAbsent(dimension, k -> new LinkedHashMap<>());
        history.put(chapterIndex, new ArrayList<>(types));
        // 保留最近 N 章
        while (history.size() > HISTORY_SIZE) {
            history.remove(history.keySet().iterator().next());
        }
    }

    /**
     * 生成某维度的轮换约束文本。
     * 最近3章已使用的类型 → 本章禁止使用。
     */
    public String generateConstraint(String dimension, String dimensionName) {
        var history = dimensions.get(dimension);
        if (history == null || history.isEmpty()) return "";

        // 取最近3章
        var recentChapters = new ArrayList<>(history.keySet());
        int from = Math.max(0, recentChapters.size() - 3);
        var recentlyUsed = new java.util.HashSet<String>();
        for (int i = from; i < recentChapters.size(); i++) {
            recentlyUsed.addAll(history.get(recentChapters.get(i)));
        }

        if (recentlyUsed.isEmpty()) return "";

        return "□ " + dimensionName + "轮换：最近3章已用【"
                + String.join("、", recentlyUsed)
                + "】，本章禁止使用以上类型，必须选择不同形式\n";
    }

    /** 生成所有维度的轮换约束 */
    public String generateAllConstraints() {
        StringBuilder sb = new StringBuilder();
        sb.append(generateConstraint("opening", "开场方式"));
        sb.append(generateConstraint("conflict", "冲突模式"));
        sb.append(generateConstraint("climax", "爽点类型"));
        return sb.toString();
    }
}
