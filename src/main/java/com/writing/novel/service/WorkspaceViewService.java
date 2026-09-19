package com.writing.novel.service;

import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.WritingTask;
import com.writing.novel.orchestrator.ProjectActionPolicy;
import com.writing.novel.orchestrator.ProjectActionPolicy.ProjectAction;
import com.writing.novel.spi.NovelRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NCS-005（方案 §15）: WorkspaceView 统一读取模型。
 *
 * <p>后端以单一读取视图回答"当前是什么状态、哪些动作可用、为什么不可用"——
 * identity / contentStatus / run / review（含 unknown/stale 计数）/ availableActions。
 * 各页面不再从不同轮询端点拼装互相矛盾的"当前状态"。
 */
public class WorkspaceViewService {

    private final NovelRepository repository;
    private final ProjectActionPolicy actionPolicy = new ProjectActionPolicy();

    public WorkspaceViewService(NovelRepository repository) {
        this.repository = repository;
    }

    public Map<String, Object> view(String projectId) {
        Project project = repository.findProject(projectId)
                .orElseThrow(() -> new IllegalArgumentException("项目不存在: " + projectId));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("identity", identity(project));
        result.put("contentStatus", contentStatus(projectId));
        result.put("run", run(projectId));
        result.put("review", review(projectId));
        result.put("availableActions", availableActions(projectId, project));
        return result;
    }

    private Map<String, Object> identity(Project project) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("projectId", project.getId());
        m.put("title", project.getTitle());
        m.put("state", project.getState().name());
        m.put("mode", project.getMode().name());
        m.put("currentChapterIndex", project.getCurrentChapterIndex());
        m.put("revisionOfView", project.getUpdatedAt() != null
                ? project.getUpdatedAt().toString() : java.time.Instant.now().toString());
        return m;
    }

    private Map<String, Object> contentStatus(String projectId) {
        List<Chapter> chapters = repository.findChaptersByProject(projectId);
        Map<String, Object> m = new LinkedHashMap<>();
        chapters.stream()
                .filter(c -> c.getChapterIndex() > 0)
                .reduce((a, b) -> a.getChapterIndex() >= b.getChapterIndex() ? a : b)
                .ifPresent(latest -> {
                    Map<String, Object> cur = new LinkedHashMap<>();
                    cur.put("index", latest.getChapterIndex());
                    cur.put("title", latest.getTitle());
                    cur.put("version", latest.getVersion());
                    cur.put("status", latest.getStatus().name());
                    m.put("currentChapter", cur);
                });
        m.put("totalChapters", chapters.size());
        m.put("doneChapters", chapters.stream()
                .filter(c -> c.getStatus() == Chapter.Status.DONE).count());
        m.put("pendingCandidates", repository.listProposals(projectId).stream()
                .filter(p -> p.getStatus() == com.writing.novel.domain.RevisionProposal.Status.READY)
                .count());
        return m;
    }

    private Map<String, Object> run(String projectId) {
        WritingTask task = repository.findActiveWritingTask(projectId)
                .orElseGet(() -> repository.findWritingTasksByProject(projectId).stream()
                        .filter(t -> t.getStatus() == WritingTask.Status.RUNNING
                                || t.getStatus() == WritingTask.Status.PAUSED)
                        .findFirst()
                        .orElse(null));
        if (task == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("taskId", task.getId());
        m.put("type", task.getType().name());
        m.put("status", task.getStatus().name());
        m.put("stopRequested", task.isStopRequested());
        m.put("chaptersWritten", task.getChaptersWritten());
        m.put("currentChapter", task.getCurrentChapter());
        m.put("errorMessage", task.getErrorMessage() == null || task.getErrorMessage().isBlank()
                ? null : task.getErrorMessage());
        return m;
    }

    private Map<String, Object> review(String projectId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("activeBarriers", repository.findActiveReviewBarriers(projectId).size());
        m.put("pendingCheckpoints", repository.findPendingCheckpoints(projectId).size());
        // NCS-007 将接入检查信封的 UNKNOWN 计数；当前以既有数据为准（无信封=0）
        m.put("unknownChecks", 0);
        // 绑定旧稿版本且未解决的检查问题（正文已推进，检查结论过期）——
        // p32-05 的 stale 是读时计算：绑定版本 < 该章当前版本即过期
        java.util.Map<Integer, Integer> currentVersions = new java.util.HashMap<>();
        for (Chapter c : repository.findChaptersByProject(projectId)) {
            currentVersions.put(c.getChapterIndex(), c.getVersion());
        }
        m.put("staleIssues", repository.findUnresolvedIssues(projectId).stream()
                .filter(i -> i.getChapterVersion() != null
                        && currentVersions.getOrDefault(i.getDetectedAtChapter(), i.getChapterVersion())
                            > i.getChapterVersion())
                .count());
        return m;
    }

    private List<Map<String, Object>> availableActions(String projectId, Project project) {
        boolean hasOutline = repository.findOutlineByProject(projectId).isPresent();
        boolean hasPendingCheckpoint = !repository.findPendingCheckpoints(projectId).isEmpty();
        var availability = actionPolicy.evaluate(project, hasOutline, hasPendingCheckpoint);
        List<Map<String, Object>> actions = new ArrayList<>();
        for (ProjectAction action : ProjectAction.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("action", action.name());
            boolean allowed = availability.allowedActions().contains(action);
            m.put("allowed", allowed);
            m.put("reason", allowed ? "" : availability.blockedReasons().get(action));
            actions.add(m);
        }
        return actions;
    }
}
