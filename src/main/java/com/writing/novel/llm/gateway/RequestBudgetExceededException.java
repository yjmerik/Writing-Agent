package com.writing.novel.llm.gateway;

/**
 * NCS-002（方案 §10/R11）: 最终请求窗口核算失败——
 * 序列化输入 + 输出预留 + 安全余量超过目标模型有效上下文窗口。
 * 确定性失败：不静默截断、不触达 provider；调用方应缩小范围/拆分任务或换更大窗口路由。
 */
public class RequestBudgetExceededException extends IllegalStateException {
    public RequestBudgetExceededException(String message) {
        super(message);
    }
}
