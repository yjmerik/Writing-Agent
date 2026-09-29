package com.writing.novel.service;

import com.writing.novel.domain.ContinuityIssue;
import com.writing.novel.domain.Project;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-123: 检测器产出分级回流——趋势型 MEDIUM（结构/节奏/密度）卷级聚合：
 * 超阈值发门禁告警（事件），聚合一句话进下一卷反馈，不再逐章噪音堆积。
 */
class DetectorTrendGateTest {

    @TempDir
    Path tempDir;
    private SqliteNovelRepository repository;
    private DetectorTrendService service;
    private String pid;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("trend.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repository = new SqliteNovelRepository(cp);
        service = new DetectorTrendService(repository);
        var project = new Project("趋势", "构思", "wuxia");
        repository.saveProject(project);
        pid = project.getId();
    }

    private void seedIssue(ContinuityIssue.Type type, ContinuityIssue.Severity severity, int chapter) {
        repository.saveContinuityIssue(new ContinuityIssue(pid, chapter, type, severity, "描述" + chapter));
    }

    @Test
    void 卷级门禁_同类超阈值告警_未超静默() {
        for (int i = 1; i <= 5; i++) {
            seedIssue(ContinuityIssue.Type.PACING, ContinuityIssue.Severity.MEDIUM, i);
        }
        String warn = service.volumeGate(pid, 1, 10);
        assertTrue(warn.contains("PACING") && warn.contains("5"),
                "5 次 > 阈值 4 应告警: " + warn);

        // 新项目（1-2 次）静默
        var project2 = new Project("安静", "构思", "wuxia");
        repository.saveProject(project2);
        seedQuiet(project2.getId());
        assertEquals("", service.volumeGate(project2.getId(), 1, 10));
    }

    private void seedQuiet(String pid2) {
        repository.saveContinuityIssue(new ContinuityIssue(pid2, 1,
                ContinuityIssue.Type.PACING, ContinuityIssue.Severity.MEDIUM, "x"));
        repository.saveContinuityIssue(new ContinuityIssue(pid2, 2,
                ContinuityIssue.Type.STRUCTURE, ContinuityIssue.Severity.MEDIUM, "y"));
    }

    @Test
    void 聚合反馈_同类一句话_无趋势为空() {
        for (int i = 1; i <= 3; i++) {
            seedIssue(ContinuityIssue.Type.DENSITY, ContinuityIssue.Severity.MEDIUM, i);
        }
        String feedback = service.feedbackSection(pid, 1, 10);
        assertTrue(feedback.contains("DENSITY"), "聚合按类型归并: " + feedback);
        assertTrue(feedback.contains("3"));
        assertEquals(-1, feedback.indexOf("DENSITY", feedback.indexOf("DENSITY") + 1),
                "同类型只出现一次（聚合去重）");

        var project2 = new Project("无趋势", "构思", "wuxia");
        repository.saveProject(project2);
        assertEquals("", service.feedbackSection(project2.getId(), 1, 10));
    }

    @Test
    void 高危与窗外问题不参与趋势统计() {
        seedIssue(ContinuityIssue.Type.PACING, ContinuityIssue.Severity.HIGH, 2);
        seedIssue(ContinuityIssue.Type.PACING, ContinuityIssue.Severity.MEDIUM, 50); // 窗外
        assertEquals("", service.volumeGate(pid, 1, 10));
    }
}
