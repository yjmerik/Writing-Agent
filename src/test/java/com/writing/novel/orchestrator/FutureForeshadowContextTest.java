package com.writing.novel.orchestrator;

import com.writing.novel.domain.Foreshadowing;
import com.writing.novel.domain.Project;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.reflect.Method;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Future outline foreshadowings must not be injected as facts into earlier chapters. */
class FutureForeshadowContextTest {

    @TempDir
    Path tempDir;
    private SqliteNovelRepository repo;
    private ChapterProduction production;
    private NovelOrchestrator orchestrator;
    private String projectId;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("future-foreshadow.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        Project project = new Project("未来伏笔", "测试", "悬疑");
        repo.saveProject(project);
        projectId = project.getId();
        orchestrator = new NovelOrchestrator(repo, new ProjectService(repo),
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null);
        production = new ChapterProduction(orchestrator, repo, null, null, null, null, null,
                null, null, null, null, null, null);
    }

    @Test
    void 当前章只看到已经埋设的伏笔() throws Exception {
        Foreshadowing future = new Foreshadowing(projectId, "第五章才埋设的线索", "规划", 5);
        repo.saveForeshadowing(future);
        Foreshadowing current = new Foreshadowing(projectId, "第一章已经埋设的线索", "正文", 1);
        repo.saveForeshadowing(current);

        Method method = ChapterProduction.class.getDeclaredMethod(
                "collectSettingsForCheck", String.class, int.class, java.util.List.class);
        method.setAccessible(true);
        String settings = (String) method.invoke(production, projectId, 1, java.util.List.of());

        assertTrue(settings.contains("第一章已经埋设的线索"));
        assertFalse(settings.contains("第五章才埋设的线索"),
                "第1章连续性检查不能把第5章规划当成已发生事实");
    }

    @Test
    void 写作待回收列表也排除未来尚未埋设的规划() throws Exception {
        var current = new Foreshadowing(projectId, "已经发现的会票", "正文", 1);
        repo.saveForeshadowing(current);
        var future = new Foreshadowing(projectId, "第五章计划埋下的旧画指", "规划", 5);
        future.setPriority(Foreshadowing.Priority.HIGH);
        future.setMustResolve(true);
        repo.saveForeshadowing(future);
        var assembler = new com.writing.novel.memory.ContextAssembler(repo, null, null, null,
                null, null, null, null, null);
        Method method = assembler.getClass().getDeclaredMethod("buildForeshadowings", String.class, int.class);
        method.setAccessible(true);
        String first = (String) method.invoke(assembler, projectId, 1);
        assertTrue(first.contains(current.getDescription()));
        assertFalse(first.contains(future.getDescription()));
        String fifth = (String) method.invoke(assembler, projectId, 5);
        assertTrue(fifth.contains(current.getDescription()));
        assertTrue(fifth.contains(future.getDescription()), "到对应章仍需提供伏笔信息");
    }

    @ParameterizedTest
    @CsvSource({"6,0,0,HIGH", "0,6,0,NONE", "0,0,6,MEDIUM", "5,6,6,MEDIUM"})
    void 卷末HIGH只统计明确到期的未回收伏笔(int due, int future, int unscheduled, String expected) throws Exception {
        for (int i = 0; i < due; i++) savePlan("本卷到期" + i, 3);
        for (int i = 0; i < future; i++) savePlan("后续回收" + i, 9);
        for (int i = 0; i < unscheduled; i++) savePlan("未指定期限" + i, null);
        Method method = NovelOrchestrator.class.getDeclaredMethod("chekhovAudit",
                String.class, int.class, int.class, int.class);
        method.setAccessible(true);
        method.invoke(orchestrator, projectId, 1, 1, 3);
        var issues = repo.findContinuityIssuesByProject(projectId);
        if (expected.equals("NONE")) {
            assertTrue(issues.isEmpty(), "尚未到期的伏笔不能计作本卷遗漏");
        } else {
            assertEquals(1, issues.size());
            assertEquals(expected, issues.get(0).getSeverity().name());
            assertFalse(issues.get(0).getDescription().contains("后续回收"));
            if (due > 0) assertTrue(issues.get(0).getDescription().contains("本卷到期"));
        }
    }

    private void savePlan(String description, Integer expectedChapter) {
        var f = new Foreshadowing(projectId, description, "规划", 1);
        f.setExpectedResolveChapter(expectedChapter);
        repo.saveForeshadowing(f);
    }
}
