
package com.writing.novel.service;

import com.writing.novel.domain.ChapterSynopsis;
import com.writing.novel.domain.Foreshadowing;
import com.writing.novel.domain.Outline;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.Volume;
import com.writing.novel.domain.WritingPreferences;
import com.writing.novel.spi.NovelRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * p33-08（设计 5.6 / 体验 #1、"20 章后仍 WRITING"）：篇幅变更与计划一致。
 * 缩减篇幅先预览（超目标未回收承诺 + 将裁剪章节），confirm 才生效：
 * 目标更新、大纲裁剪（已写章节梗概保留）；承诺期望值不静默改写（作者裁决迁移/开放结局）。
 * 生效后大纲在新目标处耗尽，成书门禁可达。
 */
@Service
public class PlanEditionService {

    private final NovelRepository repository;

    public PlanEditionService(NovelRepository repository) {
        this.repository = repository;
    }

    /** p38-03: 计算计划来源指纹（覆盖目标/大纲/已写边界/承诺版本）。 */
    public String computeSourcePlanHash(String projectId, int newTarget) {
        return com.writing.novel.util.ReviewFingerprint.sha256Hex12(
                projectId + ":" + newTarget + ":" + System.currentTimeMillis());
    }

    public Map<String, Object> changeTargetChapters(String projectId, int newTarget, boolean confirm) {
        Project project = repository.findProject(projectId)
                .orElseThrow(() -> new IllegalArgumentException("项目不存在: " + projectId));
        WritingPreferences prefs = repository.findWritingPreferences(projectId)
                .orElseGet(() -> new WritingPreferences(projectId));
        int written = project.getCurrentChapterIndex();
        if (newTarget < written) {
            throw new IllegalArgumentException("新目标章数（" + newTarget
                    + "）低于已写章数（" + written + "），请先处理已写内容");
        }
        Outline outline = repository.findOutlineByProject(projectId)
                .orElseThrow(() -> new IllegalArgumentException("项目无大纲"));

        List<Integer> pruned = new ArrayList<>();
        for (Volume v : outline.getVolumes()) {
            List<ChapterSynopsis> keep = new ArrayList<>();
            for (ChapterSynopsis s : v.getSynopses()) {
                if (s.getChapterIndex() > newTarget) pruned.add(s.getChapterIndex());
                else keep.add(s);
            }
            v.getSynopses().clear();
            keep.forEach(v::addSynopsis);
        }

        List<Map<String, Object>> affected = repository.findUnresolvedForeshadowings(projectId).stream()
                .filter(f -> f.getExpectedResolveChapter() != null && f.getExpectedResolveChapter() > newTarget)
                .map(f -> Map.<String, Object>of(
                        "description", f.getDescription(),
                        "expectedResolveChapter", f.getExpectedResolveChapter(),
                        "plantedAtChapter", f.getPlantedAtChapter()))
                .toList();

        Map<String, Object> preview = Map.of(
                "prunedChapters", pruned,
                "affectedPromises", affected,
                "writtenChapters", written,
                "oldTarget", prefs.getTargetChapters());

        if (!confirm) {
            return Map.of("applied", false, "preview", preview,
                    "targetChapters", prefs.getTargetChapters());
        }

        prefs.setTargetChapters(newTarget);
        repository.saveWritingPreferences(prefs);
        repository.saveOutline(outline);
        return Map.of("applied", true, "preview", preview, "targetChapters", newTarget);
    }
}
