package com.writing.novel.orchestrator;

import com.writing.novel.agent.PlotterAgent;
import com.writing.novel.domain.ChapterSynopsis;
import com.writing.novel.domain.Outline;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.StoryFact;
import com.writing.novel.domain.Volume;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.persistence.sqlite.StoryFactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * p10-w3-02: 延迟卷大纲补全结构化字段——parseAndAppendVolume 调 parseChapterDetails，
 * 修复 40 章后 ClimaxDiversityTracker/NarrativeThread 等追踪器失明（GAP#1）。
 */
class OutlineReplanningTest {

    @TempDir
    Path tempDir;
    private SqliteNovelRepository repo;
    private PlotterAgent plotter;
    private String pid;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("outline.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        plotter = new PlotterAgent(mock(com.writing.novel.llm.LlmGateway.class), repo,
                mock(com.writing.novel.llm.prompt.PromptTemplateService.class));
        Project p = new Project("outline书", "构思", "玄幻");
        repo.saveProject(p);
        pid = p.getId();
    }

    @Test
    void parseChapterDetails解析castClimaxTension() {
        ChapterSynopsis syn = new ChapterSynopsis(pid, 1, "第一章", "");
        String content = "场景节拍：宗门晨练，季衡被挑衅\n爽点：当众突破金丹期\n人物：季衡、沈墨\n目的：建立主角实力\n张力：8";
        plotter.parseChapterDetails(syn, content);
        assertNotNull(syn.getClimax(), "应解析爽点 climax");
        assertTrue(syn.getClimax().contains("金丹"), "climax=" + syn.getClimax());
        assertNotNull(syn.getCast(), "应解析人物 cast");
        assertTrue(syn.getCast().contains("季衡"));
        assertEquals(8, syn.getTensionLevel(), "应解析张力");
    }

    @Test
    void parseAndAppendVolume延迟卷解析出结构化字段() throws Exception {
        // 构造 NovelOrchestrator（plotter 真实，其余 null）
        NovelOrchestrator orchestrator = new NovelOrchestrator(repo, new ProjectService(repo),
                null, null, plotter, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                null, null, null, null);

        Outline outline = new Outline(pid, "总纲");
        // 模拟 planVolume 输出的章节文本（含结构化字段）
        String volumeText = "第1章 宗门挑衅：场景节拍：晨练冲突\n爽点：突破金丹\n人物：季衡\n目的：立威\n张力：8\n"
                + "第2章 暗流：场景节拍：夜探\n爽点：发现密信\n人物：沈墨\n张力：6";

        Method m = NovelOrchestrator.class.getDeclaredMethod("parseAndAppendVolume",
                Outline.class, String.class, String.class, int.class, int.class);
        m.setAccessible(true);
        int parsed = (int) m.invoke(orchestrator, outline, volumeText, pid, 1, 1);

        assertEquals(2, parsed, "应解析 2 章");
        ChapterSynopsis ch1 = outline.getVolumes().get(0).getSynopses().stream()
                .filter(s -> s.getChapterIndex() == 1).findFirst().orElseThrow();
        assertNotNull(ch1.getClimax(), "延迟卷 ch1 应解析 climax（修复 GAP#1 追踪器失明）");
        assertTrue(ch1.getClimax().contains("金丹"), "ch1 climax=" + ch1.getClimax());
        assertEquals(8, ch1.getTensionLevel(), "ch1 应解析张力");
        assertNotNull(ch1.getCast(), "ch1 应解析 cast");
    }

    @Test
    void replanRemainingOutline不再追加偏离段到premise() throws Exception {
        // 创建 NovelOrchestrator（StoryFactRepository 注入 true）
        com.writing.novel.persistence.sqlite.StoryFactRepository factRepo = new com.writing.novel.persistence.sqlite.StoryFactRepository(
                new com.writing.novel.persistence.sqlite.ConnectionProvider(tempDir.resolve("replan.db").toString()));
        new com.writing.novel.persistence.sqlite.SqliteSchemaInitializer(new com.writing.novel.persistence.sqlite.ConnectionProvider(tempDir.resolve("replan.db").toString())).initialize();
        NovelOrchestrator orch = new NovelOrchestrator(repo, new ProjectService(repo),
                null, null, plotter,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null);
        orch.setStoryFactRepository(factRepo);

        Outline outline = new Outline(pid, "原始总纲\n第1章 开局\n第2章 发展\n第3章 高潮");
        repo.saveOutline(outline);

        // 加一条真典事实
        factRepo.save(new com.writing.novel.domain.StoryFact(pid, com.writing.novel.domain.StoryFact.FactType.CHARACTER_STATE, "季衡", "金丹期", 30));
        // 加一条 mustResolve 伏笔
        com.writing.novel.domain.Foreshadowing f = new com.writing.novel.domain.Foreshadowing(pid, "季衡身世之秘", "埋于第1章", 1);
        f.setMustResolve(true);
        repo.saveForeshadowing(f);

        Method m = NovelOrchestrator.class.getDeclaredMethod("replanRemainingOutline", String.class, int.class);
        m.setAccessible(true);
        m.invoke(orch, pid, 40); // 第 40 章，卷 5 末

        Outline after = repo.findOutlineByProject(pid).orElseThrow();
        assertFalse(after.getPremise().contains("剧情偏离与重规划"),
                "总纲不得再追加【剧情偏离与重规划】段");
        assertTrue(after.getPremise().startsWith("原始总纲"),
                "已有总纲应保留");
    }
}
