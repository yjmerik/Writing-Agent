package com.writing.novel.telemetry;

/**
 * 事件上下文 — ThreadLocal 传递当前 projectId / 章节号，
 * 使深层组件（如 LLM 网关埋点）产出的事件能关联到业务实体。
 * 由 NovelOrchestrator 在入口处 set，finally 中 clear。
 * NCS-172: 另含 session 串联字段（sessionId/operation），供 SessionLog 关联调用与操作。
 */
public final class EventContext {

    private static final ThreadLocal<String> PROJECT_ID = new ThreadLocal<>();
    private static final ThreadLocal<Integer> CHAPTER = new ThreadLocal<>();
    /** NCS-172: session 串联——任务 taskId / 临时工作 workId；operation 为动作名 */
    private static final ThreadLocal<String> SESSION_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> OPERATION = new ThreadLocal<>();

    private EventContext() {}

    public static void setProjectId(String projectId) { PROJECT_ID.set(projectId); }

    public static void setChapter(int chapter) { CHAPTER.set(chapter); }

    public static String projectId() { return PROJECT_ID.get(); }

    public static Integer chapter() { return CHAPTER.get(); }

    /** NCS-172: 设置当前 session（taskId/workId）与操作名；嵌套场景由调用方自行恢复 */
    public static void setSession(String sessionId, String operation) {
        SESSION_ID.set(sessionId);
        OPERATION.set(operation);
    }

    public static String sessionId() { return SESSION_ID.get(); }

    public static String operation() { return OPERATION.get(); }

    public static void clear() {
        PROJECT_ID.remove();
        CHAPTER.remove();
        SESSION_ID.remove();
        OPERATION.remove();
    }
}
