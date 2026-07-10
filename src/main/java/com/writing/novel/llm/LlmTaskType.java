package com.writing.novel.llm;

/**
 * LLM 任务类型。决定路由到哪个模型配置。
 * 不同任务可配不同模型（创意用强模型/正文用快模型/校对用小模型）。
 */
public enum LlmTaskType {
    WORLDBUILDING,
    CHARACTER_DESIGN,
    PLOTTING,
    CHAPTER_WRITING,
    CONTINUITY_CHECK,
    STYLE_GUARDIAN,
    MEMORY_SUMMARY,
    GENERAL
}
