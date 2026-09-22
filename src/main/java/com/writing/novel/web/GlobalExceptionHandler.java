package com.writing.novel.web;

import com.writing.novel.spi.NovelRepository.ProposalSourceConflictException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 全局异常处理。把常见异常转为友好的 JSON 响应。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 非法参数（如项目不存在、状态不合法）→ 400 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    /** p32-12: 工作所有权占用 → 409 + code，前端据此提示等待/接手而非按版本冲突重试。
     *  NCS-075: 细分码（WORKER_FINALIZING/SYNC_WORK_BUSY）透传，通用冲突回退原码。 */
    @ExceptionHandler(com.writing.novel.service.WorkConflictException.class)
    public ResponseEntity<Map<String, String>> handleWorkConflict(
            com.writing.novel.service.WorkConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", e.getMessage(),
                "code", e.code() == null ? "WORK_OWNERSHIP_CONFLICT" : e.code()));
    }

    /** 候选生成后来源变化：正文提交已拒绝，客户端应重新生成预览。 */
    @ExceptionHandler(ProposalSourceConflictException.class)
    public ResponseEntity<Map<String, String>> handleProposalSourceConflict(ProposalSourceConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    /** 非法状态（如从错误状态推进）→ 409 */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    /** 兜底：其他未捕获异常 → 500 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGeneric(Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = "内部错误: " + e.getClass().getSimpleName();
        }
        return ResponseEntity.internalServerError().body(Map.of("error", msg));
    }
}
