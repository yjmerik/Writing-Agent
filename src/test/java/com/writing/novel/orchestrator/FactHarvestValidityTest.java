package com.writing.novel.orchestrator;

import com.writing.novel.agent.FactHarvesterAgent;
import com.writing.novel.agent.TestPromptHelper;
import com.writing.novel.domain.Chapter;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.persistence.sqlite.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FactHarvestValidityTest {
    @TempDir Path dir;
    private ConnectionProvider cp;
    private StoryFactRepository facts;
    private LlmGateway gateway;
    private FactHarvesterAgent agent;
    private Chapter chapter;

    @BeforeEach void setUp() {
        cp = new ConnectionProvider(dir.resolve("facts.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        facts = new StoryFactRepository(cp);
        gateway = mock(LlmGateway.class);
        agent = new FactHarvesterAgent(gateway, new SqliteStoryCanon(facts), TestPromptHelper.create());
        chapter = new Chapter("p", 3, "取证");
        chapter.setContent("已取得会票。");
        chapter.setStatus(Chapter.Status.POST_PROCESSING);
    }

    @ParameterizedTest
    @ValueSource(strings = {"[", "[broken]", "{}", "[{}]", "[null]",
            "[{\"type\":\"CHARACTER_STATE\",\"subject\":\"裴照微\",\"assertion\":\"取得会票\"},{\"type\":\"INVALID\"}]"})
    void 无效响应不能伪成功或部分写入(String response) {
        when(gateway.complete(any())).thenReturn(new LlmResponse(response, 1, 1));
        assertThrows(IllegalStateException.class, () -> agent.harvest("p", chapter));
        assertTrue(facts.findAll("p").isEmpty());
    }

    @Test void 合法空数组仍成功() {
        when(gateway.complete(any())).thenReturn(new LlmResponse("[]", 1, 1));
        assertEquals(0, agent.harvest("p", chapter).total());
    }

    @Test void 截断响应保留账本续跑且恢复不重复() {
        var repo = new SqliteNovelRepository(cp);
        repo.saveChapter(chapter);
        var ledger = new PostProcessLedgerRepository(cp);
        var stage = ChapterPostProcessor.Stage.FACT_HARVEST;
        ledger.ensureStage("p", 3, stage);
        var orch = mock(NovelOrchestrator.class);
        when(orch.factHarvester()).thenReturn(agent);
        var processor = new ChapterPostProcessor(orch, repo, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, ledger);
        when(gateway.complete(any())).thenReturn(new LlmResponse("[", 1, 1),
                new LlmResponse("[{\"type\":\"CHARACTER_STATE\",\"subject\":\"裴照微\",\"assertion\":\"取得会票\"}]", 1, 1));
        var error = assertThrows(IllegalStateException.class,
                () -> processor.resume("p", chapter, 3, null, null));
        assertTrue(error.getMessage().contains("真典"));
        assertEquals(PostProcessLedgerRepository.StageStatus.RUNNING, ledger.statusOf("p", 3, stage).orElseThrow());
        assertEquals(Chapter.Status.POST_PROCESSING, repo.findChapterByIndex("p", 3).orElseThrow().getStatus());
        assertEquals(chapter.getContent(), repo.findChapterByIndex("p", 3).orElseThrow().getContent());
        processor.resume("p", chapter, 3, null, null);
        processor.resume("p", chapter, 3, null, null);
        assertEquals(PostProcessLedgerRepository.StageStatus.COMPLETED, ledger.statusOf("p", 3, stage).orElseThrow());
        assertEquals(1, facts.findAll("p").size());
        verify(gateway, times(2)).complete(any());
    }
}
