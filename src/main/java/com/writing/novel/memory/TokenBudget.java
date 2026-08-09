package com.writing.novel.memory;

/**
 * Token 预算估算工具。
 * 中文约 1.5 字/token，英文约 1 字/token，混合取 ~1.2 字/token 的粗略估算。
 */
public final class TokenBudget {

    private TokenBudget() {}

    /** mc-p2-03: 未配置 contextWindow 时的回退预算（token） */
    public static final int DEFAULT_BUDGET = 16000;

    /** mc-p2-03: 上下文组装预算 = 模型窗口的 90%（留出生成输出与安全余量） */
    public static final double WINDOW_USAGE_RATIO = 0.9;

    /** 粗略估算文本的 token 数 */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        // 中文字符占比高时约 1.5 字/token，英文约 4 字符/token
        // 粗略取 text.length() / 1.5（偏保守，宁可高估）
        return (int) Math.ceil(text.length() / 1.5);
    }

    /**
     * mc-p2-03: 按模型上下文窗口推导组装预算。
     * 上下文窗口未配置（&lt;=0）时回退 DEFAULT_BUDGET。
     */
    public static int budgetForModel(int contextWindow) {
        if (contextWindow <= 0) return DEFAULT_BUDGET;
        return (int) Math.floor(contextWindow * WINDOW_USAGE_RATIO);
    }

    /** 如果文本超出 token 预算，从尾部截断 */
    public static String truncateToTokens(String text, int maxTokens) {
        if (text == null) return "";
        int maxChars = (int) (maxTokens * 1.5);
        if (text.length() <= maxChars) return text;
        return text.substring(0, maxChars) + "...（截断）";
    }
}
