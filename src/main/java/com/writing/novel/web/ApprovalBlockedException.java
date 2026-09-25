package com.writing.novel.web;

/**
 * NCS-081（听雪刀 P1-2）：批准满足性门禁失败——未解决 HIGH 级问题、作者要求未落实等。
 * 此类失败此前以裸 IllegalStateException 经 GlobalExceptionHandler 变成只有 {error} 的 409，
 * 前端无法与版本冲突区分，作者看不到"为什么批不了、差什么"。
 */
public class ApprovalBlockedException extends RuntimeException {

    public ApprovalBlockedException(String message) {
        super(message);
    }

    public ApprovalBlockedException(String message, Throwable cause) {
        super(message, cause);
    }
}
