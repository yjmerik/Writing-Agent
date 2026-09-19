package com.writing.novel.service;

/** NCS-001: 写入租约已失效（epoch 被取消/接手递增）——晚到的 Agent 写入必须被拒。 */
public class WriteLeaseStaleException extends IllegalStateException {
    public WriteLeaseStaleException(String message) {
        super(message);
    }
}
