package com.writing.novel.domain;

/** 小说生命周期状态机 */
public enum NovelState {
    CONCEIVING,      // 构思：生成世界观/人物/大纲
    PLANNING,        // 规划：细化大纲
    WRITING,         // 创作中
    VOLUME_DONE,     // 单卷完结
    FINAL,           // 全书完结（成书门禁通过）
    NEEDS_REVISION,  // mc-p3-02: 成书门禁未通过——需人工修订后再 FINAL
    PAUSED_AT_CHECKPOINT  // 交互模式暂停于检查点
}
