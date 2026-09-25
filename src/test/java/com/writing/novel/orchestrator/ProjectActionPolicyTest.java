package com.writing.novel.orchestrator;

import com.writing.novel.domain.NovelState;
import com.writing.novel.domain.Project;
import com.writing.novel.orchestrator.ProjectActionPolicy.ProjectAction;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Set;
import java.util.stream.Stream;

import static com.writing.novel.orchestrator.ProjectActionPolicy.ProjectAction.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectActionPolicyTest {

    private final ProjectActionPolicy policy = new ProjectActionPolicy();

    @ParameterizedTest(name = "{0}, outline={1}, pending={2} -> {3}")
    @MethodSource("actionMatrix")
    void exposesOnlyActionsAllowedByProjectState(NovelState state,
                                                  boolean hasOutline,
                                                  boolean hasPendingCheckpoint,
                                                  Set<ProjectAction> expected) {
        Project project = new Project("动作矩阵", "测试", "悬疑");
        project.setState(state);

        assertEquals(expected,
                policy.evaluate(project, hasOutline, hasPendingCheckpoint).allowedActions());
    }

    private static Stream<Arguments> actionMatrix() {
        return Stream.of(
                Arguments.of(NovelState.CONCEIVING, false, false, Set.of(ADVANCE)),
                Arguments.of(NovelState.PLANNING, true, false, Set.of(ADVANCE)),
                Arguments.of(NovelState.WRITING, true, false, Set.of(WRITE_CHAPTER, WRITE_ALL)),
                Arguments.of(NovelState.WRITING, false, false, Set.of()),
                Arguments.of(NovelState.WRITING, true, true, Set.of()),
                Arguments.of(NovelState.VOLUME_DONE, true, false, Set.of(ADVANCE)),
                Arguments.of(NovelState.NEEDS_REVISION, true, false, Set.of(FINAL_AUDIT)),
                Arguments.of(NovelState.PAUSED_AT_CHECKPOINT, true, true, Set.of()),
                Arguments.of(NovelState.FINAL, true, false, Set.of())
        );
    }

    @org.junit.jupiter.api.Test
    void rejectionUsesThePublishedServerReason() {
        Project project = new Project("无大纲", "测试", "悬疑");
        project.setState(NovelState.WRITING);
        var availability = policy.evaluate(project, false, false);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> policy.requireAllowed(project, WRITE_CHAPTER, false, false));

        assertEquals(availability.blockedReasons().get(WRITE_CHAPTER), error.getMessage());
    }

    /** NCS-079：作者宣告完结动作——写作态且已有成章内容时可用。 */
    @org.junit.jupiter.api.Test
    void finishIsAllowedFromWritingStatesWithWrittenChapters() {
        for (NovelState state : java.util.List.of(NovelState.WRITING, NovelState.VOLUME_DONE,
                NovelState.PAUSED_AT_CHECKPOINT, NovelState.NEEDS_REVISION)) {
            Project project = new Project("宣告完结" + state, "测试", "武侠");
            project.setState(state);
            project.setCurrentChapterIndex(2);
            assertTrue(policy.evaluate(project, true, true).allowedActions().contains(FINISH),
                    state + " 下已写章节应允许宣告完结（待审检查点由完结一并关闭）");
        }
    }

    @org.junit.jupiter.api.Test
    void finishIsBlockedBeforeWritingAndAfterFinal() {
        for (NovelState state : java.util.List.of(NovelState.CONCEIVING, NovelState.PLANNING, NovelState.FINAL)) {
            Project project = new Project("不可完结" + state, "测试", "武侠");
            project.setState(state);
            project.setCurrentChapterIndex(2);
            assertFalse(policy.evaluate(project, true, false).allowedActions().contains(FINISH),
                    state + " 下不应允许宣告完结");
        }
        Project nothingWritten = new Project("未动笔", "测试", "武侠");
        nothingWritten.setState(NovelState.WRITING);
        assertFalse(policy.evaluate(nothingWritten, true, false).allowedActions().contains(FINISH),
                "未写任何章节时不应允许宣告完结");

        Project conceiving = new Project("构思中", "测试", "武侠");
        conceiving.setState(NovelState.CONCEIVING);
        conceiving.setCurrentChapterIndex(0);
        assertEquals("尚未写作任何章节，不能宣告完结",
                policy.evaluate(conceiving, false, false).blockedReasons().get(FINISH));
    }
}
