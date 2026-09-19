package com.writing.novel.domain;

import java.util.List;

/**
 * NCS-014（方案 §12/R04/F01）：影响分析条目——修订章后每个受影响/无关章节的分类与理由。
 * MUST_REPAIR：锚定于修订章的事实被该章正文引用（依赖可能失效，必须修复）；
 * RECHECK：仅实体名出现（需复核，未必需要改文）；
 * UNAFFECTED_WITH_REASON：扫描未发现依赖（理由保留，"未找到边"不证明"不受影响"）。
 */
public record ImpactItem(int chapterIndex,
                         Classification classification,
                         List<String> reasons,
                         List<String> factIds,
                         List<String> subjects) {

    public enum Classification { MUST_REPAIR, RECHECK, UNAFFECTED_WITH_REASON }
}
