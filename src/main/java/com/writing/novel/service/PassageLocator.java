package com.writing.novel.service;

public final class PassageLocator {

    private PassageLocator() {}

    public static int resolve(String content, int paragraphIndex, String selectedText) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("章节正文为空");
        }
        String[] lines = content.split("\n", -1);
        String needle = selectedText == null ? "" : selectedText.trim();
        if (!needle.isEmpty()) {
            int byText = indexByText(lines, needle);
            if (byText >= 0) return byText;
        }
        if (paragraphIndex >= 0 && paragraphIndex < lines.length) {
            String target = lines[paragraphIndex];
            if (needle.isEmpty() || containsPrefix(target, needle)) {
                return paragraphIndex;
            }
        }
        throw new IllegalArgumentException("段落定位失败：选中文本与段落不匹配，请刷新页面重试");
    }

    private static int indexByText(String[] lines, String needle) {
        String prefix = needle.substring(0, Math.min(20, needle.length()));
        for (int i = 0; i < lines.length; i++) {
            if (containsPrefix(lines[i], needle) || containsPrefix(needle, lines[i].trim())) {
                return i;
            }
            if (!prefix.isEmpty() && lines[i].contains(prefix)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean containsPrefix(String haystack, String needle) {
        if (haystack == null || needle == null || needle.isBlank()) return false;
        String h = haystack.trim();
        String n = needle.trim();
        if (h.isEmpty() || n.isEmpty()) return false;
        return h.contains(n.substring(0, Math.min(10, n.length())))
                || n.contains(h.substring(0, Math.min(10, h.length())));
    }
}
