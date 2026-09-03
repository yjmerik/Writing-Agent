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
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
