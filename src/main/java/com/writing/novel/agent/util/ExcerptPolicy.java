package com.writing.novel.agent.util;

import java.util.ArrayList;
import java.util.List;

/**
 * NCS-117: 抽取输入统一策略——FactHarvester/EntityExtractor/ConsistencyGuard 共享
 * 同一分段口径（此前各看 4000/3000/5000 字且不告知 LLM，同章台账必然互相矛盾、
 * 后半章事实系统性丢失）。
 *
 * <ul>
 *   <li>分段：每段 4000 字，至多 3 段（覆盖 12000 字；更长的尾部显式声明未提供）</li>
 *   <li>范围声明：截断/分段时 prompt 明示所见范围，禁止对未提供内容下结论</li>
 *   <li>短章（≤4000 字）单段且无声明——行为与旧实现一致</li>
 * </ul>
 */
public final class ExcerptPolicy {

    private ExcerptPolicy() {}

    public static final int SEGMENT_CHARS = 4000;
    public static final int MAX_SEGMENTS = 3;

    /**
     * 统一覆盖窗口——单次调用所见正文（≤12000 字），替代各抽取器 3000/4000/5000 的
     * 各自为政；配合 {@link #scopeNote} 显式声明范围。
     */
    public static String coverage(String content) {
        if (content == null) return "";
        int limit = SEGMENT_CHARS * MAX_SEGMENTS;
        return content.length() <= limit ? content : content.substring(0, limit);
    }

    /** 范围声明 + 覆盖窗口 的组合注入文本（短章 ≤4000 字：无声明、原文，行为与旧实现一致） */
    public static String coveredWithNote(String content, String artifact) {
        return scopeNote(content, artifact) + coverage(content);
    }

    /** 分段：短章单段原样；长章按 4000 字切段（≤3 段） */
    public static List<String> segments(String content) {
        List<String> out = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            out.add("");
            return out;
        }
        if (content.length() <= SEGMENT_CHARS) {
            out.add(content);
            return out;
        }
        for (int i = 0; i < MAX_SEGMENTS && i * SEGMENT_CHARS < content.length(); i++) {
            int from = i * SEGMENT_CHARS;
            out.add(content.substring(from, Math.min(content.length(), from + SEGMENT_CHARS)));
        }
        return out;
    }

    /**
     * 所见范围声明——注入 prompt，杜绝「没读到」被当成「不存在」。
     * @param artifact 材料名（如「本章正文」）
     */
    public static String scopeNote(String content, String artifact) {
        if (content == null || content.length() <= SEGMENT_CHARS * MAX_SEGMENTS) {
            return content != null && content.length() > SEGMENT_CHARS
                    ? "【范围声明】" + artifact + "共 " + content.length() + " 字，以上为全部所见分段。\n"
                    : "";
        }
        return "【范围声明】" + artifact + "共 " + content.length() + " 字，本调用所见为前 "
                + (SEGMENT_CHARS * MAX_SEGMENTS) + " 字（分" + MAX_SEGMENTS + "段），其后内容未提供——"
                + "不得对其下任何结论或声明\"未出现\"。\n";
    }
}
