package com.writing.novel.orchestrator;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.writing.novel.domain.Foreshadowing;
import com.writing.novel.domain.Outline;
import com.writing.novel.spi.NovelRepository;

/**
 * NCS-151（旅程三 O-5，用户决策：卷边界应有前台呈现）：
 * 卷末章的 AFTER_CHAPTER 检查点携带卷小结（卷号/卷名/章范围/累计字数/伏笔埋设与回收数）。
 * 纯确定性计算（无 LLM），独立可测。
 */
final class VolumeBoundarySupport {

    private VolumeBoundarySupport() {
    }

    /** 若 chapterIndex 是某卷最后一章，向 payload 追加 volumeSummary 节点 */
    static void appendSummaryIfVolumeEnd(NovelRepository repository, Outline outline,
                                         int chapterIndex, ObjectNode payload) {
        for (var v : outline.getVolumes()) {
            if (v.getSynopses().isEmpty()) continue;
            int last = v.getSynopses().stream()
                    .mapToInt(com.writing.novel.domain.ChapterSynopsis::getChapterIndex).max().orElse(0);
            int first = v.getSynopses().stream()
                    .mapToInt(com.writing.novel.domain.ChapterSynopsis::getChapterIndex).min().orElse(0);
            if (chapterIndex != last) continue;
            var node = payload.putObject("volumeSummary");
            node.put("volumeIndex", v.getVolumeIndex());
            node.put("volumeTitle", v.getTitle() == null ? "" : v.getTitle());
            node.put("firstChapter", first);
            node.put("lastChapter", last);
            long words = 0;
            for (int i = first; i <= last; i++) {
                words += repository.findChapterByIndex(outline.getProjectId(), i)
                        .map(c -> c.getWordCount() > 0 ? c.getWordCount()
                                : (c.getContent() == null ? 0 : c.getContent().length()))
                        .orElse(0);
            }
            node.put("totalWords", words);
            var foreshadowings = repository.findForeshadowingsByProject(outline.getProjectId());
            long planted = foreshadowings.stream()
                    .filter(f -> f.getPlantedAtChapter() >= first && f.getPlantedAtChapter() <= last).count();
            long resolved = foreshadowings.stream()
                    .filter(f -> f.isResolved() && f.getResolvedAtChapter() != null
                            && f.getResolvedAtChapter() >= first && f.getResolvedAtChapter() <= last).count();
            node.put("foreshadowPlanted", planted);
            node.put("foreshadowResolved", resolved);
            return;
        }
    }
}
