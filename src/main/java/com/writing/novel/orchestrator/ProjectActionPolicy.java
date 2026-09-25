package com.writing.novel.orchestrator;

import com.writing.novel.domain.NovelState;
import com.writing.novel.domain.Project;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Defines the commands that may start work for a project in its current state. */
public final class ProjectActionPolicy {

    public enum ProjectAction {
        ADVANCE,
        WRITE_CHAPTER,
        WRITE_ALL,
        FINAL_AUDIT,
        FINISH
    }

    public record ActionAvailability(Set<ProjectAction> allowedActions,
                                     Map<ProjectAction, String> blockedReasons) {
        public ActionAvailability {
            EnumSet<ProjectAction> allowedCopy = EnumSet.noneOf(ProjectAction.class);
            allowedCopy.addAll(allowedActions);
            allowedActions = Collections.unmodifiableSet(allowedCopy);
            blockedReasons = Collections.unmodifiableMap(new EnumMap<>(blockedReasons));
        }
    }

    public ActionAvailability evaluate(Project project, boolean hasOutline,
                                       boolean hasPendingCheckpoint) {
        EnumSet<ProjectAction> allowed = EnumSet.noneOf(ProjectAction.class);
        NovelState state = project.getState();

        switch (state) {
            case CONCEIVING, PLANNING, VOLUME_DONE -> allowed.add(ProjectAction.ADVANCE);
            case WRITING -> {
                if (hasOutline && !hasPendingCheckpoint) {
                    allowed.add(ProjectAction.WRITE_CHAPTER);
                    allowed.add(ProjectAction.WRITE_ALL);
                }
            }
            case NEEDS_REVISION -> allowed.add(ProjectAction.FINAL_AUDIT);
            case PAUSED_AT_CHECKPOINT, FINAL -> {
                // No primary workflow action is valid in these states.
            }
        }

        // NCS-079：作者宣告完结——写作态（含暂停待审/待修订）且已有成章内容时可用；
        // 待审检查点由完结流程一并关闭，因此有 pending 也不阻塞该动作。
        if (project.getCurrentChapterIndex() >= 1) {
            switch (state) {
                case WRITING, VOLUME_DONE, PAUSED_AT_CHECKPOINT, NEEDS_REVISION ->
                        allowed.add(ProjectAction.FINISH);
                default -> { }
            }
        }

        EnumMap<ProjectAction, String> blocked = new EnumMap<>(ProjectAction.class);
        for (ProjectAction action : ProjectAction.values()) {
            if (!allowed.contains(action)) {
                blocked.put(action, blockedReason(state, action, hasOutline, hasPendingCheckpoint));
            }
        }
        return new ActionAvailability(allowed, blocked);
    }

    public void requireAllowed(Project project, ProjectAction action, boolean hasOutline,
                               boolean hasPendingCheckpoint) {
        ActionAvailability availability = evaluate(project, hasOutline, hasPendingCheckpoint);
        if (!availability.allowedActions().contains(action)) {
            throw new IllegalStateException(availability.blockedReasons().get(action));
        }
    }

    private String blockedReason(NovelState state, ProjectAction action, boolean hasOutline,
                                 boolean hasPendingCheckpoint) {
        if ((action == ProjectAction.WRITE_CHAPTER || action == ProjectAction.WRITE_ALL)
                && hasPendingCheckpoint) {
            return "有待审核项，请先完成审核";
        }
        if ((action == ProjectAction.WRITE_CHAPTER || action == ProjectAction.WRITE_ALL)
                && !hasOutline) {
            return "尚未生成大纲，不能开始写作";
        }
        if (state == NovelState.PAUSED_AT_CHECKPOINT) {
            return "项目已暂停等待审核，请先处理待审核项";
        }
        if (state == NovelState.FINAL) {
            return "项目已完结，不能再执行该操作";
        }
        return switch (action) {
            case ADVANCE -> "当前状态 " + state + " 不允许推进";
            case WRITE_CHAPTER, WRITE_ALL -> "当前状态 " + state + " 不允许写作";
            case FINAL_AUDIT -> "仅待修订状态可重新评审";
            case FINISH -> "尚未写作任何章节，不能宣告完结";
        };
    }
}
