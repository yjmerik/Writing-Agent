package com.writing.novel.service;

import com.writing.novel.domain.KnowledgeEntry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ec-01: 时代锚点推导——从世界观条目与构思文本中扫描朝代/年号关键词，
 * 推导作品的时代设定，供 ChapterWriter/ContinuityChecker 做时代一致性护栏。
 *
 * 设计为静态工具（无状态），便于在 ContextAssembler/ContinuityChecker 中直接调用。
 * 无信号返回空串（调用方据此决定是否注入约束）。
 */
public final class EraAnchorService {

    private EraAnchorService() {}

    /** 朝代 → 关联关键词（朝代名 + 代表性年号/国号）。覆盖主流古代朝代。 */
    private static final Map<String, String[]> DYNASTY_KEYWORDS = new LinkedHashMap<>();
    static {
        DYNASTY_KEYWORDS.put("明", new String[]{"大明", "明朝", "明代", "大明宝钞", "锦衣卫", "东厂", "西厂",
                "洪武", "永乐", "宣德", "正统", "景泰", "成化", "弘治", "正德", "嘉靖", "隆庆", "万历", "天启", "崇祯"});
        DYNASTY_KEYWORDS.put("清", new String[]{"大清", "清朝", "清代", "满清", "八旗", "军机处",
                "顺治", "康熙", "雍正", "乾隆", "嘉庆", "道光", "咸丰", "同治", "光绪", "宣统"});
        DYNASTY_KEYWORDS.put("唐", new String[]{"大唐", "唐朝", "唐代", "长安", "三省六部",
                "武德", "贞观", "永徽", "开元", "天宝"});
        DYNASTY_KEYWORDS.put("宋", new String[]{"大宋", "宋朝", "宋代", "北宋", "南宋", "汴梁", "临安", "交子",
                "建隆", "开宝", "景德", "庆历", "熙宁", "元丰", "靖康", "建炎", "绍兴", "淳熙", "嘉定"});
        DYNASTY_KEYWORDS.put("元", new String[]{"大元", "元朝", "元代", "蒙古", "大都", "至元", "皇庆", "至正"});
        DYNASTY_KEYWORDS.put("汉", new String[]{"大汉", "汉朝", "汉代", "未央宫", "建元", "元光", "元朔", "太初", "建安"});
        DYNASTY_KEYWORDS.put("秦", new String[]{"大秦", "秦朝", "秦代", "始皇帝", "咸阳", "颛顼历"});
        DYNASTY_KEYWORDS.put("隋", new String[]{"大隋", "隋朝", "隋代", "开皇", "大业"});
        DYNASTY_KEYWORDS.put("三国", new String[]{"三国", "曹魏", "蜀汉", "东吴", "建安"});
        DYNASTY_KEYWORDS.put("春秋战国", new String[]{"春秋", "战国", "诸侯", "卿大夫"});
    }

    // 关键词边界用 lookbehind/lookahead 避免子串误匹配（如"大明"不被"明白"触发需上下文）
    private static final Pattern ERA_EXPLICIT = Pattern.compile("架空|虚构世界|异世界|修仙界");

    /**
     * 推导时代锚点。
     * @param entries 世界观条目（HISTORY/RULE 等）
     * @param concept 故事构思文本
     * @return 时代锚点描述；无信号返回 ""；多朝代冲突返回 "⚠️ ..." 提示
     */
    public static String deriveEraAnchor(List<KnowledgeEntry> entries, String concept) {
        StringBuilder corpus = new StringBuilder();
        if (concept != null) corpus.append(concept).append(' ');
        if (entries != null) {
            for (var e : entries) {
                if (e.getContent() != null) corpus.append(e.getContent()).append(' ');
                if (e.getName() != null) corpus.append(e.getName()).append(' ');
            }
        }
        String text = corpus.toString();

        // 架空/修仙世界 → 明确非历史时代
        if (ERA_EXPLICIT.matcher(text).find()) return "";

        Map<String, int[]> counts = new LinkedHashMap<>();
        for (var dyn : DYNASTY_KEYWORDS.entrySet()) {
            int hits = 0;
            for (String kw : dyn.getValue()) {
                Matcher m = Pattern.compile(Pattern.quote(kw)).matcher(text);
                while (m.find()) hits++;
            }
            if (hits > 0) counts.put(dyn.getKey(), new int[]{hits});
        }

        if (counts.isEmpty()) return "";
        if (counts.size() == 1) {
            var e = counts.entrySet().iterator().next();
            return e.getKey() + "代";
        }
        // 多朝代命中：取最高频为主锚点，标注冲突
        var sorted = counts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]))
                .toList();
        var top = sorted.get(0);
        StringBuilder sb = new StringBuilder("⚠️ 时代冲突：");
        for (var e : sorted) sb.append(e.getKey()).append("代×").append(e.getValue()[0]).append(' ');
        sb.append("（请统一为最高频的 ").append(top.getKey()).append("代，或改用虚构年号）");
        return sb.toString();
    }
}
