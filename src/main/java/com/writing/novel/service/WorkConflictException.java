package com.writing.novel.service;

/**
 * p32-12（UI 验证发现）：项目工作所有权冲突——Agent 任务或另一编辑正持有该项目。
 * 与「章节版本已变化」的陈旧冲突区分开：REST 层返回 code=WORK_OWNERSHIP_CONFLICT，
 * 前端据此提示"等待完成或先接手"，而不是诱导用户按版本冲突重试。
 * NCS-075：可选细分码（WORKER_FINALIZING / SYNC_WORK_BUSY）——按持有者类型给出
 * 可理解的等待语义，前端据此提示稍候重试或取消操作，不再误报"确认新稿"。
 */
public class WorkConflictException extends IllegalStateException {

    /** 细分冲突码；null 表示通用工作权冲突（REST 层回退 WORK_OWNERSHIP_CONFLICT） */
    private final String code;

    public WorkConflictException(String message) {
        this(message, null);
    }

    public WorkConflictException(String message, String code) {
        super(message);
        this.code = code;
    }

    /** 细分冲突码（WORKER_FINALIZING / SYNC_WORK_BUSY），通用冲突为 null */
    public String code() {
        return code;
    }
}
