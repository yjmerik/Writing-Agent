package com.writing.novel.memory;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * p10-w1-01: 项目级记忆版本号——稳定段缓存失效的解耦桥梁。
 * <p>
 * 背景：ContextAssembler 的 stableSectionCache（premise/worldbuilding/knowledgeQuote）一旦计算
 * 就永久缓存，导致后续章节新增的 KnowledgeEntry、CharacterProfileUpgrader 升级、
 * updateMasterOutlineWithWrittenDigest 改动 premise 后，永远不反映到上下文（正确性 bug）。
 * <p>
 * 设计：写入方（KnowledgeBaseService/NoveOrchestrator）在改变稳定段依据的数据时调用 bump；
 * ContextAssembler.cached 在组装时比较缓存时的 revision 与当前 revision，不一致则重算。
 * MemoryRevision 无业务依赖，任何 bean 安全注入，避免 ContextAssembler↔KnowledgeBaseService 循环依赖。
 * 旧构造器（revision=null）退化为永不失效，向后兼容现有测试。
 */
@Component
public class MemoryRevision {

    private final Map<String, AtomicLong> revisions = new ConcurrentHashMap<>();

    /** 当前版本号（项目无记录时为 0） */
    public long current(String projectId) {
        AtomicLong v = revisions.get(projectId);
        return v == null ? 0L : v.get();
    }

    /** 版本号 +1，使该项目的所有稳定段缓存失效（下次组装时重算） */
    public void bump(String projectId) {
        revisions.computeIfAbsent(projectId, k -> new AtomicLong()).incrementAndGet();
    }

    /** 测试/重置用：清空全部版本号 */
    public void reset() {
        revisions.clear();
    }
}
