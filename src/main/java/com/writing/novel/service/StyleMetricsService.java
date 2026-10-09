package com.writing.novel.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * NCS-104: 风格量化指标——每章确定性计算（零 LLM 成本），供章节质量报告、
 * golden.json styleMetrics 基线维度与风格漂移趋势消费。
 *
 * <p>指标口径（与 scripts/compare-baseline.py 消费的字段一一对应）：
 * <ul>
 *   <li>avgSentenceLength / sentenceLengthStdDev：句长分布（按 。！？！?； 切句）</li>
 *   <li>dialogueDensity：引号内字符占比（「」“” ‘’）</li>
 *   <li>clicheHits：ClicheDetector 固定词表在本章的总出现次数</li>
 *   <li>repeatedNgramRate：去标点正文 4-gram 重复率（出现≥2 次的 distinct 4-gram 占比）</li>
 * </ul>
 */
public final class StyleMetricsService {

    private StyleMetricsService() {}

    private static final int NGRAM_SIZE = 4;
    /** 超长章节只取前 6000 字计算 n-gram（保持确定性并控制开销） */
    private static final int NGRAM_SCAN_LIMIT = 6000;

    public static Map<String, Object> compute(String content) {
        Map<String, Object> m = new HashMap<>();
        if (content == null || content.isBlank()) {
            m.put("avgSentenceLength", 0.0);
            m.put("sentenceLengthStdDev", 0.0);
            m.put("dialogueDensity", 0.0);
            m.put("clicheHits", 0);
            m.put("repeatedNgramRate", 0.0);
            return m;
        }
        m.put("avgSentenceLength", round(avgSentenceLength(content)));
        m.put("sentenceLengthStdDev", round(sentenceLengthStdDev(content)));
        m.put("dialogueDensity", round(dialogueDensity(content)));
        m.put("clicheHits", ClicheDetector.countHits(content));
        m.put("repeatedNgramRate", round(repeatedNgramRateOf(content)));
        return m;
    }

    /**
     * NCS-187: 叙事纹理指标——按段落结构的确定性度量（零 LLM 成本）。
     *
     * <p>口径：
     * <ul>
     *   <li>totalParagraphs / avgParagraphLength：非空行段落（trim 后）计数与平均长度</li>
     *   <li>dialogueParagraphRatio：含成对引号（「」或“”）的段落占比</li>
     *   <li>narrationRunMaxChars：最长连续非对白段落块的总字数（叙述被切碎成标签短句时该值极低）</li>
     *   <li>sceneBreaks：场景分隔标记行数（仅由 ＊*—–·※ 与空白构成、≥2 字符的行）</li>
     *   <li>dialoguePingPong：连续 ≥4 段对白（中间无非对白段）的次数——铁律 12 的检测口径</li>
     * </ul>
     */
    public static Map<String, Object> computeTexture(String content) {
        Map<String, Object> m = new HashMap<>();
        List<String> paras = paragraphs(content);
        int total = paras.size();
        int dialogue = 0;
        for (String p : paras) {
            if (isDialogueParagraph(p)) dialogue++;
        }
        int narrationRunMax = 0;
        int run = 0;
        int pingPong = 0;
        int dialogueStreak = 0;
        for (String p : paras) {
            if (isSeparatorParagraph(p)) {
                // 场景分隔同时中断叙述块与对白连续段
                run = 0;
                dialogueStreak = 0;
                continue;
            }
            if (isDialogueParagraph(p)) {
                dialogueStreak++;
                if (dialogueStreak == 4) pingPong++;
            } else {
                dialogueStreak = 0;
                run += p.length();
                narrationRunMax = Math.max(narrationRunMax, run);
            }
        }
        int totalChars = paras.stream().mapToInt(String::length).sum();
        m.put("totalParagraphs", total);
        m.put("dialogueParagraphRatio", round(total == 0 ? 0 : (double) dialogue / total));
        m.put("narrationRunMaxChars", narrationRunMax);
        m.put("sceneBreaks", paras.stream().mapToInt(p -> isSeparatorParagraph(p) ? 1 : 0).sum());
        m.put("dialoguePingPong", pingPong);
        m.put("avgParagraphLength", round(total == 0 ? 0 : (double) totalChars / total));
        return m;
    }

    /** 非空行段落（trim 后）；分隔标记行保留在段落列表中以支撑 sceneBreaks 统计 */
    static List<String> paragraphs(String content) {
        List<String> out = new ArrayList<>();
        if (content == null || content.isBlank()) return out;
        for (String line : content.split("\n")) {
            String t = line.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** 含成对引号的段落视为对白段（与 dialogueDensity 的引号字符集一致） */
    static boolean isDialogueParagraph(String paragraph) {
        return (paragraph.contains("「") && paragraph.contains("」"))
                || (paragraph.contains("“") && paragraph.contains("”"))
                || (paragraph.indexOf('"') >= 0 && paragraph.indexOf('"', paragraph.indexOf('"') + 1) >= 0);
    }

    /** 场景分隔标记行：仅由 ＊*—–·※ 与空白构成且 ≥2 字符 */
    static boolean isSeparatorParagraph(String paragraph) {
        if (paragraph.length() < 2) return false;
        for (char c : paragraph.toCharArray()) {
            if (!(c == '＊' || c == '*' || c == '—' || c == '–' || c == '·' || c == '※' || Character.isWhitespace(c))) {
                return false;
            }
        }
        return true;
    }

    /** 句子切分：按中英文句终/分号标点 */
    static List<String> sentences(String content) {
        List<String> out = new ArrayList<>();
        for (String s : content.split("[。！？!?；]")) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    static double avgSentenceLength(String content) {
        var ss = sentences(content);
        if (ss.isEmpty()) return 0;
        return ss.stream().mapToInt(String::length).average().orElse(0);
    }

    static double sentenceLengthStdDev(String content) {
        var ss = sentences(content);
        if (ss.size() < 2) return 0;
        double avg = ss.stream().mapToInt(String::length).average().orElse(0);
        double var = ss.stream().mapToDouble(s -> (s.length() - avg) * (s.length() - avg)).sum() / ss.size();
        return Math.sqrt(var);
    }

    /** 引号内字符占比（「」与中英文双引号对；只统计成对引号内部） */
    static double dialogueDensity(String content) {
        int inner = 0;
        boolean inQuote = false;
        char open = 0;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (!inQuote && (c == '「' || c == '“')) {
                inQuote = true;
                open = c;
            } else if (inQuote && ((open == '「' && c == '」') || (open == '“' && c == '”'))) {
                inQuote = false;
            } else if (inQuote) {
                inner++;
            }
        }
        return content.isEmpty() ? 0 : (double) inner / content.length();
    }

    /** 去标点/空白后的字符流 */
    static String normalized(String content) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < content.length() && sb.length() < NGRAM_SCAN_LIMIT; i++) {
            char c = content.charAt(i);
            if (Character.isLetterOrDigit(c)) sb.append(c);
        }
        return sb.toString();
    }

    /** 单章口径：出现 ≥2 次的 distinct 4-gram 占比（写作自重复信号） */
    static double repeatedNgramRateOf(String content) {
        return repeatedNgramRateInternal(content);
    }

    /** 跨章口径：在 ≥2 个不同章节出现的 distinct 4-gram 占比（跨章口径信号） */
    static double crossChapterRepetition(List<String> chapterContents) {
        if (chapterContents == null || chapterContents.size() < 2) return 0;
        List<java.util.Set<String>> perChapter = new ArrayList<>();
        for (String c : chapterContents) {
            perChapter.add(grams(c));
        }
        java.util.Map<String, Integer> chapterSeen = new HashMap<>();
        for (java.util.Set<String> grams : perChapter) {
            for (String g : grams) chapterSeen.merge(g, 1, Integer::sum);
        }
        long total = chapterSeen.size();
        if (total == 0) return 0;
        long shared = chapterSeen.values().stream().filter(v -> v >= 2).count();
        return (double) shared / total;
    }

    private static java.util.Set<String> grams(String content) {
        String norm = normalized(content);
        java.util.Set<String> set = new java.util.HashSet<>();
        for (int i = 0; i + NGRAM_SIZE <= norm.length(); i++) {
            set.add(norm.substring(i, i + NGRAM_SIZE));
        }
        return set;
    }

    private static double repeatedNgramRateInternal(String content) {
        String norm = normalized(content);
        if (norm.length() < NGRAM_SIZE) return 0;
        java.util.Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i + NGRAM_SIZE <= norm.length(); i++) {
            counts.merge(norm.substring(i, i + NGRAM_SIZE), 1, Integer::sum);
        }
        long total = counts.size();
        long repeated = counts.values().stream().filter(v -> v >= 2).count();
        return (double) repeated / total;
    }

    /** 单章口径出口（报告侧调用名） */
    static double repeatedNgramRateOfReport(String content) {
        return repeatedNgramRateInternal(content);
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
