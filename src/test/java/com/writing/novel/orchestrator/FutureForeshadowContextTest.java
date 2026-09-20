package com.writing.novel.orchestrator;

import com.writing.novel.domain.Foreshadowing;
import com.writing.novel.domain.Project;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Future outline foreshadowings must not be injected as facts into earlier chapters. */
class FutureForeshadowContextTest {

    @TempDir
    Path tempDir;
    private SqliteNovelRepository repo;
    private ChapterProduction production;
    private String projectId;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("future-foreshadow.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        Project project = new Project("未来伏笔", "测试", "悬疑");
        repo.saveProject(project);
        projectId = project.getId();
        NovelOrchestrator orchestrator = new NovelOrchestrator(repo, new ProjectService(repo),
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
}
