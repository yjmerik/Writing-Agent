package com.writing.novel.llm.client;

/** 流式输出的逐 token 回调 */
public interface StreamHandler {

    /** 收到一个 token（增量文本） */
    void onToken(String token);

    /** 流式完成 */
    void onComplete();

    /** 发生错误 */
    void onError(Throwable error);
}
