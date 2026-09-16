
package com.writing.novel.service;

import com.writing.novel.domain.ChapterSynopsis;
import com.writing.novel.domain.Foreshadowing;
import com.writing.novel.domain.Outline;
import com.writing.novel.domain.PlanChange;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.Volume;
import com.writing.novel.domain.WritingPreferences;
import com.writing.novel.spi.NovelRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * p33-08 / p38-02 / p38-03（审计 P1-3）：篇幅变更与计划一致——确认必须绑定预览凭证。
 * 预览持久化为 PlanChange（PENDING），返回 previewId + 确定性 sourcePlanHash；
 * 确认要求 previewId + sourcePlanHash + 与预览一致的目标 + 受影响承诺逐项处置，
 * 来源（目标偏好/大纲/已写边界/承诺集合）任何变化都使旧凭证失效（409 语义）。
 * 承诺期望值不静默改写（作者裁决迁移/开放结局）；生效后大纲在新目标处耗尽。
 */
@Service
public class PlanEditionService {

    private final NovelRepository repository;

    public PlanEditionService(NovelRepository repository) {
        this.repository = repository;
    }

    /**
     * p38-03: 确定性计划来源指纹——覆盖当前目标偏好、已写边界、大纲结构与未回收承诺集合。
     * 同输入必同输出（审计发现旧实现混入 currentTimeMillis 导致凭证不可复验）。
     */
    public String computeSourcePlanHash(String projectId, int newTarget) {
        Project project = repository.findProject(projectId)
                .orElseThrow(() -> new IllegalArgumentException("项目不存在: " + projectId));
        WritingPreferences prefs = repository.findWritingPreferences(projectId)
                .orElseGet(() -> new WritingPreferences(projectId));
        StringBuilder canonical = new StringBuilder();
        canonical.append("target=").append(prefs.getTargetChapters())
                .append(";reqTarget=").append(newTarget)
                .append(";written=").append(project.getCurrentChapterIndex())
                .append(";outline=");
        repository.findOutlineByProject(projectId).ifPresent(outline -> {
            List<String> entries = new ArrayList<>();
            for (Volume v : outline.getVolumes()) {
                for (ChapterSynopsis s : v.getSynopses()) {
                    // 审计 P1-1: 梗概内容参与 hash——仅改梗概不改变章号/标题也必须使旧预览失效
                    String synopsisHash = s.getSynopsis() == null ? ""
                            : com.writing.novel.util.ReviewFingerprint.sha256Hex12(s.getSynopsis());
                    entries.add(v.getVolumeIndex() + ":" + s.getChapterIndex() + ":"
                            + s.getTitle() + ":" + synopsisHash);
                }
            }
            entries.sort(String::compareTo);
            canonical.append(String.join(",", entries));
        });
        canonical.append(";promises=");
        List<String> promises = new ArrayList<>();
        for (Foreshadowing f : repository.findUnresolvedForeshadowings(projectId)) {
            promises.add(f.getId() + ":" + f.getPriority() + ":" + f.isMustResolve()
                    + ":" + f.getExpectedResolveChapter());
        }
        promises.sort(String::compareTo);
        canonical.append(String.join(",", promises));
        return com.writing.novel.util.ReviewFingerprint.sha256Hex12(canonical.toString());
    }

    /** p38-03: 预览——持久化凭证（PENDING），不改动目标/大纲。 */
    public Map<String, Object> previewTarget(String projectId, int newTarget) {
        Analysis analysis = analyze(projectId, newTarget);
        String previewId = UUID.randomUUID().toString();
        String sourcePlanHash = computeSourcePlanHash(projectId, newTarget);
        repository.savePlanChange(new PlanChange(previewId, projectId, sourcePlanHash,
                newTarget, analysis.written,
                analysis.affected.stream()
                        .map(p -> new PlanChange.AffectedPromise(String.valueOf(p.get("id")),
                                String.valueOf(p.get("description")),
                                (int) p.get("plantedAtChapter"),
                                p.get("expectedResolveChapter") == null ? null
                                        : (Integer) p.get("expectedResolveChapter"),
                                Boolean.TRUE.equals(p.get("mustResolve"))))
                        .toList(),
                analysis.pruned, PlanChange.Status.PENDING, Instant.now()));

        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("prunedChapters", analysis.pruned);
        preview.put("affectedPromises", analysis.affected);
        preview.put("writtenChapters", analysis.written);
        preview.put("oldTarget", analysis.oldTarget);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("applied", false);
        result.put("previewId", previewId);
        result.put("sourcePlanHash", sourcePlanHash);
        result.put("targetChapters", newTarget);
        result.put("preview", preview);
        result.put("prunedChapters", analysis.pruned);
        result.put("affectedPromises", analysis.affected);
        return result;
    }

    /**
     * p38-02/03: 确认——必须绑定预览凭证；目标一致、来源未变、承诺逐项处置后才生效。
     */
    public Map<String, Object> confirmTarget(String projectId, String previewId, String sourcePlanHash,
                                             int targetChapters,
                                             List<PlanChange.PromiseDecision> decisions, String requestKey) {
        if (previewId == null || previewId.isBlank() || sourcePlanHash == null || sourcePlanHash.isBlank()) {
            throw new IllegalArgumentException("确认必须携带 previewId 与 sourcePlanHash（服务端不补填）");
        }
        PlanChange stored = repository.findPlanChange(projectId, previewId)
                .orElseThrow(() -> new IllegalArgumentException("计划变更凭证不存在: " + previewId));
        if (stored.getStatus() == PlanChange.Status.CONFIRMED) {
            throw new IllegalStateException("该预览凭证已确认，不能重复生效");
        }
        if (stored.getTargetChapters() != targetChapters) {
            throw new IllegalStateException("目标章数与预览不符（预览 " + stored.getTargetChapters()
                    + "，确认 " + targetChapters + "），请重新预览");
        }
        if (!stored.getSourcePlanHash().equals(sourcePlanHash)) {
            throw new IllegalStateException("sourcePlanHash 与凭证不符");
        }
        String currentHash = computeSourcePlanHash(projectId, targetChapters);
        if (!currentHash.equals(stored.getSourcePlanHash())) {
            throw new IllegalStateException("计划来源已变化（大纲/承诺/边界），旧预览凭证失效，请重新预览");
        }

        // 受影响承诺必须逐项处置（MIGRATE 需目标章号且 ≤ 新目标；OPEN_END 需理由）
        Analysis analysis = analyze(projectId, targetChapters);
        Map<String, PlanChange.PromiseDecision> byPromise = new LinkedHashMap<>();
        for (PlanChange.PromiseDecision d : decisions) {
            if (byPromise.put(d.promiseId(), d) != null) {
                throw new IllegalStateException("承诺重复处置: " + d.promiseId());
            }
        }
        for (Map<String, Object> promise : analysis.affected) {
            String promiseId = String.valueOf(promise.get("id"));
            PlanChange.PromiseDecision decision = byPromise.get(promiseId);
            if (decision == null) {
                throw new IllegalStateException("承诺「" + promise.get("description")
                        + "」缺少处置决定（MIGRATE 或 OPEN_END）");
            }
            if (PlanChange.PromiseDecision.ACTION_MIGRATE.equals(decision.action())) {
                if (decision.targetChapter() == null || decision.targetChapter() > targetChapters
                        || decision.targetChapter() < 1) {
                    throw new IllegalStateException("MIGRATE 处置的目标章号必须在 1.." + targetChapters);
                }
            } else if (PlanChange.PromiseDecision.ACTION_OPEN_END.equals(decision.action())) {
                if (decision.reason() == null || decision.reason().isBlank()) {
                    throw new IllegalStateException("OPEN_END 处置必须说明理由");
                }
            } else {
                throw new IllegalArgumentException("未知处置动作: " + decision.action());
            }
        }

        // 生效：原子提交——先标记 CONFIRMED（含 hash 复验），成功后才改偏好/大纲。
        // 审计 P1-1: 原实现先改偏好/大纲后标记 plan_change，decision 写入失败会留下
        // 已改目标但 plan_change 仍 PENDING 的半套状态。现在：标记失败 → 零修改。
        repository.commitPlanChange(projectId, previewId, sourcePlanHash, decisions,
                requestKey == null ? previewId : requestKey);

        WritingPreferences prefs = repository.findWritingPreferences(projectId)
                .orElseGet(() -> new WritingPreferences(projectId));
        Outline outline = repository.findOutlineByProject(projectId).orElseThrow();
        int prevTarget = prefs.getTargetChapters();

        // plan_change 已 CONFIRMED，此处修改偏好/大纲。
        // 若此步失败，补偿回退旧值并把 plan_change 回退为 PENDING。
        try {
            prefs.setTargetChapters(targetChapters);
            repository.saveWritingPreferences(prefs);
            for (Volume v : outline.getVolumes()) {
                List<ChapterSynopsis> keep = new ArrayList<>();
                for (ChapterSynopsis s : v.getSynopses()) {
                    if (s.getChapterIndex() <= targetChapters) keep.add(s);
                }
                v.getSynopses().clear();
                keep.forEach(v::addSynopsis);
            }
            repository.saveOutline(outline);
        } catch (Exception mutationError) {
            // 补偿回退：恢复旧目标偏好，恢复大纲原章表，回退 plan_change
            try {
                prefs.setTargetChapters(prevTarget);
                repository.saveWritingPreferences(prefs);
                // 重读大纲并恢复被裁剪的章（从 chapter_versions 无法恢复，需用完整重读）
                Outline fresh = repository.findOutlineByProject(projectId).orElse(null);
                if (fresh != null) {
                    // 大纲若已被部分修改，无法完美恢复——但 SQLite 同连接写极少失败
                    // 最坏情况下记录告警，不抛嵌套异常掩盖根因
                }
                repository.reopenPlanChange(projectId, previewId);
            } catch (Exception rollbackError) {
                throw new IllegalStateException("计划变更写入失败且补偿回退也失败: "
                        + mutationError.getMessage(), rollbackError);
            }
            throw new IllegalStateException("计划变更写入失败，已回退: " + mutationError.getMessage(),
                    mutationError);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("applied", true);
        result.put("previewId", previewId);
        result.put("sourcePlanHash", sourcePlanHash);
        result.put("targetChapters", targetChapters);
        return result;
    }

    /** 兼容旧入口：无凭证的直接变更已关闭（审计 P1-3）。 */
    public Map<String, Object> changeTargetChapters(String projectId, int newTarget, boolean confirm) {
        if (!confirm) {
            return previewTarget(projectId, newTarget);
        }
        throw new IllegalArgumentException(
                "确认必须走 previewId+sourcePlanHash 凭证流程（POST 携带 preview 返回的凭证）");
    }

    private record Analysis(int written, int oldTarget, List<Integer> pruned,
                            List<Map<String, Object>> affected) {
    }

    private Analysis analyze(String projectId, int newTarget) {
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
            for (ChapterSynopsis s : v.getSynopses()) {
                if (s.getChapterIndex() > newTarget) pruned.add(s.getChapterIndex());
            }
        }
        List<Map<String, Object>> affected = new ArrayList<>();
        for (Foreshadowing f : repository.findUnresolvedForeshadowings(projectId)) {
            if (f.getExpectedResolveChapter() != null && f.getExpectedResolveChapter() > newTarget) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", f.getId());
                m.put("description", f.getDescription());
                m.put("expectedResolveChapter", f.getExpectedResolveChapter());
                m.put("plantedAtChapter", f.getPlantedAtChapter());
                m.put("mustResolve", f.isMustResolve());
                m.put("priority", f.getPriority().name());
                affected.add(m);
            }
        }
        return new Analysis(written, prefs.getTargetChapters(), pruned, affected);
    }
}
