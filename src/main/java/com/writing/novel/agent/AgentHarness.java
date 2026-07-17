package com.writing.novel.agent;

import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Agent 执行器：封装"调用→校验→重试"的通用 harness。
 * 避免 Agent 输出静默失败（0 条目、空章节等）。
 */
public final class AgentHarness {

    private AgentHarness() {}

    /**
     * 带校验的重试执行。
     *
     * @param maxAttempts  最大尝试次数（含首次）
     * @param executor     执行器：返回 Agent 的输出
     * @param validator    校验器：返回 true 表示输出合格
     * @return 合格的输出（若 maxAttempts 次都不合格，返回最后一次的结果）
     */
    public static <T> T executeWithRetry(int maxAttempts,
                                          Function<Integer, T> executor,
                                          Predicate<T> validator) {
        T result = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                result = executor.apply(attempt);
                if (validator.test(result)) {
                    return result;
                }
            } catch (Exception e) {
                if (attempt >= maxAttempts) {
                    throw e; // 最后一次失败则抛出
                }
                // 非最后一次，继续重试
            }
        }
        return result; // 所有尝试都不合格，返回最后一次（可能为 null）
    }

    /** 常用校验器：非 null */
    public static <T> Predicate<T> nonNull() {
        return r -> r != null;
    }

    /** 常用校验器：字符串非空（非 null 且长度 > minLen） */
    public static Predicate<String> minLength(int minLen) {
        return s -> s != null && s.length() >= minLen;
    }
}
