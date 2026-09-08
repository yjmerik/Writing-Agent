package com.writing.novel.service;

/**
 * p32-12（UI 验证发现）：项目工作所有权冲突——Agent 任务或另一编辑正持有该项目。
 * 与「章节版本已变化」的陈旧冲突区分开：REST 层返回 code=WORK_OWNERSHIP_CONFLICT，
 * 前端据此提示"等待完成或先接手"，而不是诱导用户按版本冲突重试。
 */
public class WorkConflictException extends IllegalStateException {

    public WorkConflictException(String message) {
        super(message);
    }
}
