package com.writing.novel.telemetry;

/**
 * 事件上下文 — ThreadLocal 传递当前 projectId / 章节号，
 * 使深层组件（如 LLM 网关埋点）产出的事件能关联到业务实体。
 * 由 NovelOrchestrator 在入口处 set，finally 中 clear。
 */
public final class EventContext {

    private static final ThreadLocal<String> PROJECT_ID = new ThreadLocal<>();
    private static final ThreadLocal<Integer> CHAPTER = new ThreadLocal<>();

    private EventContext() {}

    public static void setProjectId(String projectId) { PROJECT_ID.set(projectId); }

    public static void setChapter(int chapter) { CHAPTER.set(chapter); }

    public static String projectId() { return PROJECT_ID.get(); }

    public static Integer chapter() { return CHAPTER.get(); }

    public static void clear() {
        PROJECT_ID.remove();
        CHAPTER.remove();
    }
}
