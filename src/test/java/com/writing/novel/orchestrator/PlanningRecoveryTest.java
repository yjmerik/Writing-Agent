package com.writing.novel.orchestrator;

import com.writing.novel.domain.Character;
import com.writing.novel.domain.NovelState;
import com.writing.novel.domain.Outline;
import com.writing.novel.domain.Project;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.spi.NovelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NCS-167（走查 incident B）：PLANNING 断头路恢复——构思线程死亡在大纲生成前时，
 * 项目停在 PLANNING 且无大纲；runPlanning 此前只置 WRITING 不补跑大纲，
 * 导致 run/resume 均被拒绝（项目不可恢复）。修复语义：runPlanning 幂等补跑
 * 大纲生成（复用 runConceiving 的大纲段），恢复后可继续写作。
 */
class PlanningRecoveryTest {

    @TempDir Path tempDir;
    private NovelRepository repo;
    private NovelOrchestrator orchestrator;
    private String pid;
    private final java.util.concurrent.atomic.AtomicInteger planCalls =
            new java.util.concurrent.atomic.AtomicInteger();

    @BeforeEach
    void setUp() {
        var cp = new ConnectionProvider(tempDir.resolve("planrec.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);

        Project p = new Project("断头路项目", "构思文本", "悬疑");
        p.setState(NovelState.PLANNING);
        repo.saveProject(p);
        pid = p.getId();

        // 构思已产出的主角卡（大纲生成的上下文）
        Character protagonist = new Character(pid, "沈砚舟", "主角");
        repo.saveCharacter(protagonist);

        // Plotter stub：plan 落一个最小大纲并计数（不触真实 LLM）
        var promptSvc = com.writing.novel.agent.TestPromptHelper.create();
        var plotter = new com.writing.novel.agent.PlotterAgent(
                new com.writing.novel.llm.LlmGateway() {
                    @Override public com.writing.novel.llm.LlmResponse complete(
                            com.writing.novel.llm.LlmRequest req) {
                        throw new AssertionError("stub 不应触达 gateway");
                    }
                    @Override public void streamComplete(
                            com.writing.novel.llm.LlmRequest req,
                            com.writing.novel.llm.client.StreamHandler h) {}
                }, repo, promptSvc) {
            @Override public Outline plan(String projectId, String concept, String theme,
                    int totalChapters, String styleGuide, String genreRules, String diversityGuide) {
                planCalls.incrementAndGet();
                Outline outline = new Outline(projectId, "补跑总纲");
                com.writing.novel.domain.Volume v = new com.writing.novel.domain.Volume(projectId, 1, "卷一", "补跑卷梗概");
                v.addSynopsis(new com.writing.novel.domain.ChapterSynopsis(projectId, 1, "第一章", "节拍：开账。人物：沈砚舟。"));
                outline.addVolume(v);
                repo.saveOutline(outline);
                return outline;
            }
        };

        orchestrator = new NovelOrchestrator(repo,
                new com.writing.novel.orchestrator.ProjectService(repo),
                null, null, plotter, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    @Test
    void PLANNING无大纲时step补跑大纲并推进() {
        assertTrue(repo.findOutlineByProject(pid).isEmpty(), "前置：无大纲（断头路现场）");

        orchestrator.step(pid);

        assertNotNull(repo.findOutlineByProject(pid).orElse(null),
                "PLANNING 无大纲必须补跑大纲生成（恢复断头路）");
        assertEquals(1, planCalls.get(), "应恰好补跑一次 plan");
        Project after = repo.findProject(pid).orElseThrow();
        assertEquals(NovelState.WRITING, after.getState(), "补跑后推进到 WRITING");
    }

    @Test
    void PLANNING已有大纲时step不重复生成() {
        Outline existing = new Outline(pid, "已有大纲");
        repo.saveOutline(existing);

        orchestrator.step(pid);

        assertEquals(0, planCalls.get(), "已有大纲不得重复生成（幂等）");
        assertEquals("已有大纲", repo.findOutlineByProject(pid).orElseThrow().getPremise());
    }
}
