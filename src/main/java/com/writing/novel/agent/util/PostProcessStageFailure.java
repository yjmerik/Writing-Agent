package com.writing.novel.agent.util;

/**
 * NCS-118: 后处理阶段失败——「未执行」必须可见（NCS-007 原则从 2 类 checker 扩展到
 * 全部检测器）。检测器终态失败抛出本异常：编排层捕获后记 UNKNOWN 信封/事件，
 * 后处理 ledger 不标 COMPLETED（重启续跑），绝不静默返回空结果伪装成功。
 */
public class PostProcessStageFailure extends RuntimeException {

    private final String stage;

    public PostProcessStageFailure(String stage, String message) {
        super("[" + stage + "] " + message);
        this.stage = stage;
    }

    public PostProcessStageFailure(String stage, String message, Throwable cause) {
        super("[" + stage + "] " + message, cause);
        this.stage = stage;
    }

    public String stage() { return stage; }
}
