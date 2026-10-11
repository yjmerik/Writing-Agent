package com.writing.novel.util;

import java.util.regex.Pattern;

/**
 * NCS-194: 场景分隔标记清洗——写作模型按场景规划插入的分隔标记（如 <<<场景>>>）
 * 是写作期的结构脚手架，入库与导出前必须剥除（正文不应含任何标记语法）。
 */
public final class SceneMarkerSanitizer {

    private SceneMarkerSanitizer() {}

    /** 容错形态：<场景> / <<<场景>>> / << 场景 >>（含两侧空白），统一替换为段落分隔 */
    private static final Pattern MARKER = Pattern.compile("\\s*<{1,4}\\s*场景\\s*>{1,4}\\s*");

    private static final Pattern BLANK_LINES = Pattern.compile("\n{3,}");

    public static String sanitize(String content) {
        if (content == null || content.isBlank()) return content == null ? "" : content;
        String cleaned = MARKER.matcher(content).replaceAll("\n\n");
        return BLANK_LINES.matcher(cleaned).replaceAll("\n\n").strip();
    }
}
