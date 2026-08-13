package com.writing.novel.agent;

import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.StoryFact;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.persistence.sqlite.StoryFactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * p10-w2-03: FactHarvesterAgent——每章从正文提取事实变更，与真典比对（新/刷新/取代）。
 */
class FactHarvesterTest {

    @TempDir
    Path tempDir;
    private StoryFactRepository factRepo;
    private String pid = "proj-1";

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("harvest.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        factRepo = new StoryFactRepository(cp);
    }

    private Chapter chapter(String content) {
        Chapter c = new Chapter(pid, 1, "第一章");
        c.setContent(content);
        return c;
    }

    private FactHarvesterAgent agentReturning(String json) {
        LlmGateway gw = mock(LlmGateway.class);
        when(gw.complete(any())).thenReturn(new LlmResponse(json, 100, 50));
        return new FactHarvesterAgent(gw, factRepo, TestPromptHelper.create());
    }

    @Test
    void 新事实入库() {
        FactHarvesterAgent agent = agentReturning(
                "[{\"type\":\"CHARACTER_STATE\",\"subject\":\"季衡\",\"assertion\":\"突破至金丹期\"}]");
        var result = agent.harvest(pid, chapter("季衡盘膝吐纳，灵气冲破经脉，晋入金丹期。"));
        assertEquals(1, result.added());
        assertEquals(0, result.superseded());
        var facts = factRepo.findActive(pid);
        assertTrue(facts.stream().anyMatch(f -> f.getSubject().equals("季衡")
                && f.getAssertion().contains("金丹期")
                && f.getStatus() == StoryFact.Status.ACTIVE));
    }

    @Test
    void 一致事实刷新不新增() {
        // 真典已有 季衡/筑基期
        factRepo.save(new StoryFact(pid, StoryFact.FactType.CHARACTER_STATE, "季衡", "筑基期", 0));
        // LLM 返回 assertion 包含旧值（视为一致）
        FactHarvesterAgent agent = agentReturning(
                "[{\"type\":\"CHARACTER_STATE\",\"subject\":\"季衡\",\"assertion\":\"筑基期（初期）\"}]");
        var result = agent.harvest(pid, chapter("正文"));
        assertEquals(0, result.added(), "一致应不新增");
        assertEquals(1, result.validated(), "应刷新 lastValidatedChapter");
        // 仍只有 1 条 ACTIVE（未重复）
        assertEquals(1, factRepo.findActive(pid).size());
    }

    @Test
    void 冲突事实取代_旧SUPERSEDED新ACTIVE() {
        factRepo.save(new StoryFact(pid, StoryFact.FactType.CHARACTER_STATE, "季衡", "筑基期", 0));
        // LLM 返回 assertion 与旧值互不包含（冲突）→ supercede
        FactHarvesterAgent agent = agentReturning(
                "[{\"type\":\"CHARACTER_STATE\",\"subject\":\"季衡\",\"assertion\":\"金丹期\"}]");
        var result = agent.harvest(pid, chapter("正文"));
        assertEquals(1, result.superseded(), "冲突应取代");
        var active = factRepo.findActive(pid);
        assertEquals(1, active.size(), "ACTIVE 仍 1 条（旧被取代）");
        assertEquals("金丹期", active.get(0).getAssertion());
        // 旧事实 SUPERSEDED 保留（审计）
        assertEquals(2, factRepo.findAll(pid).size());
        var superseded = factRepo.findAll(pid).stream()
                .filter(f -> f.getStatus() == StoryFact.Status.SUPERSEDED).toList();
        assertEquals(1, superseded.size());
        assertEquals("筑基期", superseded.get(0).getAssertion());
    }

    @Test
    void LLM返回空数组或非法JSON时不报错() {
        FactHarvesterAgent agent = agentReturning("[]");
        var result = agent.harvest(pid, chapter("正文"));
        assertEquals(0, result.total());
        agentReturning("无 JSON 内容").harvest(pid, chapter("正文")); // 不抛异常
    }

    @Test
    void 无效事实类型被跳过() {
        FactHarvesterAgent agent = agentReturning(
                "[{\"type\":\"INVALID_TYPE\",\"subject\":\"x\",\"assertion\":\"y\"}]");
        var result = agent.harvest(pid, chapter("正文"));
        assertEquals(0, result.total());
        assertEquals(0, factRepo.findActive(pid).size());
    }
}
