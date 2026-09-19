package com.writing.novel.service;

import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.orchestrator.ChapterProduction;
import com.writing.novel.domain.*;
import com.writing.novel.persistence.sqlite.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-026（方案 §13/Reflexion·L4）：运行经验库。
 * 接受/拒绝样本按类落库；同类修订注入相关经验（拒绝理由优先）；作者可删除；
 * 空库不注入；经验不写真典。
 */
class RevisionExperienceTest {

    @TempDir
    Path tempDir;

    private SqliteNovelRepository repo;
    private RevisionExperienceService service;
    private String projectId;

    @BeforeEach
    void setUp() {
        var cp = new ConnectionProvider(tempDir.resolve("experience.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        service = new RevisionExperienceService(new RevisionOutcomeRepository(cp));
        Project project = new Project("经验项目", "构思", "武侠");
        repo.saveProject(project);
        projectId = project.getId();
    }

    @Test
    void 样本落库_按类查询() {
        service.recordRejected(projectId, "FACT_LOCAL", "把密信改成藏信", "候选A",
                "藏起来削弱了焚信的不可逆代价");
        service.recordAccepted(projectId, "FACT_LOCAL", "修复第12章告密依赖", "按焚毁后口供改写");
        service.recordAccepted(projectId, "CROSS_CHAPTER", "跨章修复", "三章联动");

        var all = service.list(projectId);
        assertEquals(3, all.size());
        var factLocal = java.util.stream.Stream.of(all.toArray(RevisionOutcome[]::new))
                .filter(o -> "FACT_LOCAL".equals(o.getChangeClass())).count();
        assertEquals(2, factLocal);
    }

    @Test
    void 同类注入_拒绝理由优先_空类不混入() {
        service.recordRejected(projectId, "FACT_LOCAL", "把密信改成藏信", "候选A", "削弱不可逆代价");
        service.recordAccepted(projectId, "FACT_LOCAL", "修复告密依赖", "口供改写");
        service.recordAccepted(projectId, "CROSS_CHAPTER", "跨章修复", "三章联动");

        String injection = service.buildInjection(projectId, "FACT_LOCAL");
        assertTrue(injection.contains("曾被拒绝"), "拒绝样本优先注入");
        assertTrue(injection.contains("削弱不可逆代价"), "拒绝理由注入");
        assertTrue(injection.contains("曾被接受"));
        assertFalse(injection.contains("三章联动"), "他类经验不混入");
    }

    @Test
    void 空库不注入() {
        assertEquals("", service.buildInjection(projectId, "FACT_LOCAL"),
                "无经验时注入为空（不伪造经验）");
    }

    @Test
    void 作者删除后不再注入() {
        service.recordRejected(projectId, "FACT_LOCAL", "指令", "摘要", "理由");
        var id = service.list(projectId).get(0).getId();
        assertTrue(service.delete(projectId, id));
        assertEquals("", service.buildInjection(projectId, "FACT_LOCAL"), "删除后不再注入");
        assertFalse(service.delete(projectId, id), "重复删除无效");
    }

    @Test
    void 经验不写真典() {
        service.recordAccepted(projectId, "FACT_LOCAL", "指令", "摘要");
        var canon = new SqliteStoryCanon(new StoryFactRepository(
                new ConnectionProvider(tempDir.resolve("experience.db").toString())), repo);
        assertTrue(canon.recall(projectId).isEmpty(), "运行经验不进入故事真典");
    }
}
