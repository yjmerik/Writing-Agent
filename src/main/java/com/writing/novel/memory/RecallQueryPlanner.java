package com.writing.novel.memory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * NCS-110: 召回查询规划器——向量查询从「单一章节大纲」升级为多路：
 * <ol>
 *   <li>章节大纲（或标题回退）——原通道</li>
 *   <li>cast 实体名串（含别名扩展后的规范名）——查询语域与正文语域对齐</li>
 *   <li>待回收伏笔关键词——长程情节锚点</li>
 * </ol>
 * 各路独立检索后按 id 去重合并（保留最高分），由 ContextAssembler 消费。
 */
public final class RecallQueryPlanner {

    private RecallQueryPlanner() {}

    /**
     * @param synopsis    章节大纲（null 回退标题）
     * @param title       章节标题
     * @param cast        本章 cast（已含别名扩展的规范名）
     * @param foreshadows 待回收伏笔描述（取前若干条的关键词）
     * @return 去重后的查询串列表（至少 1 条）
     */
    public static List<String> queries(String synopsis, String title, List<String> cast,
                                       List<String> foreshadows) {
        Set<String> out = new LinkedHashSet<>();
        String primary = synopsis != null && !synopsis.isBlank() ? synopsis : title;
        if (primary != null && !primary.isBlank()) out.add(primary);
        if (cast != null && !cast.isEmpty()) {
            String castQuery = String.join(" ", cast.stream()
                    .filter(n -> n != null && !n.isBlank()).limit(8).toList());
            if (!castQuery.isBlank()) out.add(castQuery);
        }
        if (foreshadows != null) {
            String fs = String.join(" ", foreshadows.stream()
                    .filter(d -> d != null && !d.isBlank()).limit(3).toList());
            if (!fs.isBlank()) out.add(fs);
        }
        if (out.isEmpty()) out.add("");
        return new ArrayList<>(out);
    }
}
