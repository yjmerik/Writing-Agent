package com.writing.novel.orchestrator;

import com.writing.novel.domain.Chapter;
import com.writing.novel.orchestrator.NovelOrchestrator;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * NCS-159（review 整改）：静默吞异常治理——降级路径必须留日志痕迹：
 * 卷小结失败记 DEBUG（主流程不受影响）、裁决台账查询失败记 WARN（降级为空集）。
 * 降级行为本身不变，仅补可观测性。
 */
class SilentCatchLoggingTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger attachedLogger;
    private Level originalLevel;

    private void attach(Class<?> loggerClass) {
        attachedLogger = (Logger) LoggerFactory.getLogger(loggerClass);
        originalLevel = attachedLogger.getLevel();
        attachedLogger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        attachedLogger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        attachedLogger.detachAppender(appender);
        attachedLogger.setLevel(originalLevel);
    }

    @Test
    void 裁决台账查询失败记warn且降级行为不变() {
        attach(com.writing.novel.service.ReviewClassificationService.class);
        var repo = mock(com.writing.novel.persistence.sqlite.SqliteNovelRepository.class);
        when(repo.findChaptersByProject("p-log")).thenReturn(java.util.List.of());
        when(repo.findAdjudicatedIssueIds("p-log"))
                .thenThrow(new RuntimeException("台账查询炸了"));
        when(repo.findUnresolvedIssues("p-log")).thenReturn(java.util.List.of());
        var service = new com.writing.novel.service.ReviewClassificationService(repo,
                mock(com.writing.novel.persistence.sqlite.CheckResultRepository.class));

        var result = service.classify("p-log");

        assertNotNull(result, "降级后仍返回分类结果");
        assertEquals(0, result.values().stream()
                .filter(v -> v instanceof java.util.List<?>)
                .mapToInt(v -> ((java.util.List<?>) v).size()).sum(), "降级为空集（无卡）");
        boolean warned = appender.list.stream()
                .anyMatch(e -> e.getLevel() == Level.WARN
                        && String.valueOf(e.getFormattedMessage()).contains("裁决台账"));
        org.junit.jupiter.api.Assertions.assertTrue(warned,
                "裁决查询失败必须记 WARN，实际日志: "
                        + appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
    }

    @Test
    void 卷小结失败记debug且主流程返回payload() {
        attach(NovelOrchestrator.class);
        var repo = mock(com.writing.novel.spi.NovelRepository.class);
        when(repo.findOutlineByProject("p-log2"))
                .thenThrow(new RuntimeException("大纲读取炸了"));
        var orchestrator = new NovelOrchestrator(repo,
                new com.writing.novel.orchestrator.ProjectService(repo),
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
        Chapter chapter = new Chapter("p-log2", 3, "第三章");
        chapter.setContent("正文内容");

        String payload = orchestrator.buildChapterCheckpointPayload("p-log2", chapter);

        assertNotNull(payload);
        org.junit.jupiter.api.Assertions.assertTrue(payload.contains("chapterIndex"),
                "主流程不受影响，payload 正常返回");
        boolean debugged = appender.list.stream()
                .anyMatch(e -> e.getLevel() == Level.DEBUG
                        && String.valueOf(e.getFormattedMessage()).contains("卷小结"));
        org.junit.jupiter.api.Assertions.assertTrue(debugged,
                "卷小结失败必须记 DEBUG，实际日志: "
                        + appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
    }
}
