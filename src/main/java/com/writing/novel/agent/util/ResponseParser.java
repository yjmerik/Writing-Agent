package com.writing.novel.agent.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 响应解析工具。从自由文本中提取结构化条目。
 * 支持格式：【类型】名称：内容
 */
public class ResponseParser {

    private static final Pattern ENTRY_PATTERN =
            Pattern.compile("【([^】]+)】([^：:]+)[：:]\\s*(.+)");

    /** 解析【类型】名称：内容 格式的条目 */
    public static List<ParsedEntry> parseEntries(String text) {
        List<ParsedEntry> entries = new ArrayList<>();
        if (text == null) return entries;
        for (String line : text.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            Matcher m = ENTRY_PATTERN.matcher(line);
            if (m.find()) {
                entries.add(new ParsedEntry(
                        m.group(1).trim(),
                        m.group(2).trim(),
                        m.group(3).trim()));
            }
        }
        return entries;
    }

    public record ParsedEntry(String type, String name, String content) {}
}
