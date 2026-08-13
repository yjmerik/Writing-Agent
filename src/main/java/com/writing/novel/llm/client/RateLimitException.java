package com.writing.novel.llm.client;

/**
 * p10-w1-05: 限流/配额耗尽专用异常（HTTP 429 或余额类错误）。
 * 区别于普通 RuntimeException：DefaultLlmGateway 捕获后会切换到 fallback 模型重试，
 * 而非直接失败；上层写作循环也据此做更长退避，不计入"永久失败"计数。
 */
public class RateLimitException extends RuntimeException {
    public RateLimitException(String message) {
        super(message);
    }
}
