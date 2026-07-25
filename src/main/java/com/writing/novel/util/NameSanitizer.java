package com.writing.novel.util;

import java.util.regex.Pattern;

/**
 * 通用名称清理工具——清理 LLM 输出中的角色/实体名称标记和注释。
 *
 * 适用场景：
 * - CharacterDesignerAgent.parseCharacter() — 角色名提取
 * - EntityExtractorAgent.parseAndSave() — 实体名提取
 * - CharacterProfileUpgrader.parseProfile() — 升级角色名
 */
public final class NameSanitizer {

    private NameSanitizer() {}

    // 匹配"字XXX""号XXX""又称XXX""即XXX"等注释后缀
    private static final Pattern SUFFIX_PATTERN = Pattern.compile(
            "[（(]\\s*(字|号|又称|又名|即|真名|原名|全名).*?[)）]");
    // 匹配 markdown 标记
    private static final Pattern MARKDOWN_PATTERN = Pattern.compile("[*#_`~|]");
    // 匹配中文标点（名字不应含）
    private static final Pattern PUNCTUATION = Pattern.compile("[\uff0c\u3002\uff01\uff1b\uff1a\u3001\u201c\u201d\u2018\u2019\u300a\u300b\u3010\u3011\u300c\u300d\u300e\u300f]");
    // 匹配下划线连接（如 "沈_寒"）
    private static final Pattern UNDERSCORE = Pattern.compile("_+");

    /**
     * 清理名称：
     * 1. 去除 markdown 标记
     * 2. 去除（字XX）（号XX）等注释
     * 3. 去除下划线
     * 4. 截取到第一个标点前
     * 5. 限 2-8 字
     */
    public static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String name = raw.trim();

        // 去 markdown
        name = MARKDOWN_PATTERN.matcher(name).replaceAll("");
        // 去（字XX）（号XX）等注释
        name = SUFFIX_PATTERN.matcher(name).replaceAll("");
        // 去下划线（沈_寒 → 沈寒）
        name = UNDERSCORE.matcher(name).replaceAll("");
        // 去引号
        name = name.replaceAll("[\"'\u201c\u201d\u2018\u2019\u300c\u300d\u300e\u300f]", "");
        // trim 再次
        name = name.trim();
        // 截取到第一个中文标点前
        int punctIdx = -1;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if ("\uff0c\u3002\uff01\uff1f\uff1b\uff1a\u3001".indexOf(c) >= 0 || c == '(' || c == '\uff08' || c == '[' || c == '\u3010') {
                punctIdx = i;
                break;
            }
        }
        if (punctIdx > 0) name = name.substring(0, punctIdx);
        // 去尾部残留括号
        name = name.replaceAll("[()\uff08\uff09\\[\\]\u3010\u3011]+$", "").trim();
        // 长度限制 2-8
        if (name.length() > 8) name = name.substring(0, 8);
        if (name.length() < 2) return raw.trim(); // 太短，回退原始值

        return name;
    }
}
