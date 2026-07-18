package com.writing.novel.llm.client;

/**
 * 内容审查异常（GLM contentFilter / DeepSeek content_filter）。
 * 这是可恢复的——可以通过温和 prompt 重试。
 */
public class ContentFilterException extends RuntimeException {
    public ContentFilterException(String message) {
        super(message);
    }
}
