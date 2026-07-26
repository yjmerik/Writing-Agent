package com.writing.novel.domain;

/**
 * 叙事线索状态。
 */
public enum ThreadStatus {
    ACTIVE,     // 活跃——正在推进
    SUSPENDED,  // 悬挂——暂时未跟进但未结束
    RESOLVED    // 已回收——线索已结束
}
