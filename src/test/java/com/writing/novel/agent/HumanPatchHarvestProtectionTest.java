package com.writing.novel.agent;

import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.StoryFact;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.memory.MemoryRevision;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.persistence.sqlite.StoryFactRepository;
import com.writing.novel.service.CanonPatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * p20-07: HUMAN_PATCH 自动冲突保护。
 *
 * 自动收割（FactHarvester）与人工补丁（CanonPatchService）同 subject+type 冲突时：
 * - 5 章保护窗口内：自动事实不得取代 HUMAN_PATCH（人工意图最高优先）
 * - 窗口外：允许剧情演进取代（审计链保留：旧 SUPERSEDED + supersededBy 指向新）
 */
class HumanPatchHarvestProtectionTest {

    @TempDir Path tempDir;
    private StoryFactRepository factRepo;
    private CanonPatchService canonPatch;
    private final String pid = "proj-hp";

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("hp.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        factRepo = new StoryFactRepository(cp);
        canonPatch = new CanonPatchService(new TestNovelRepositoryStub(), factRepo, new MemoryRevision());
    }

    private Chapter chapterAt(int index) {
        Chapter c = new Chapter(pid, index, "第" + index + "章");
        c.setContent("正文内容。");
        return c;
    }

    private FactHarvesterAgent agentReturning(String json) {
        LlmGateway gw = mock(LlmGateway.class);
        when(gw.complete(any())).thenReturn(new LlmResponse(json, 100, 50));
        FactHarvesterAgent agent = new FactHarvesterAgent(gw, factRepo, TestPromptHelper.create());
        agent.setCanonPatchService(canonPatch);
        return agent;
    }

    private StoryFact humanPatch(String subject, String assertion, int establishedAt) {
        StoryFact f = new StoryFact(pid, StoryFact.FactType.CHARACTER_STATE, subject,
                CanonPatchService.HUMAN_PATCH_PREFIX + assertion, establishedAt);
        f.setLastValidatedChapter(establishedAt);
        factRepo.save(f);
        return f;
    }

    @Test
    void 保护窗口内自动冲突事实不得取代HUMAN_PATCH() {
        StoryFact human = humanPatch("季衡", "命格：不灭玄体", 2);
        // 第 4 章（距 2 章 ≤5）收割到冲突断言
        var agent = agentReturning(
                "[{\"type\":\"CHARACTER_STATE\",\"subject\":\"季衡\",\"assertion\":\"命格：凡体\"}]");
        var result = agent.harvest(pid, chapterAt(4));

        assertEquals(0, result.superseded(), "保护窗口内不得取代人工补丁");
        var active = factRepo.findActive(pid);
        assertEquals(1, active.size(), "机器事实被丢弃，不得与人工补丁并存");
        assertEquals(human.getId(), active.get(0).getId(), "ACTIVE 的仍是人工补丁");
        assertEquals(StoryFact.Status.ACTIVE, factRepo.findById(human.getId()).orElseThrow().getStatus());
        assertTrue(factRepo.findById(human.getId()).orElseThrow().getAssertion()
                .startsWith(CanonPatchService.HUMAN_PATCH_PREFIX));
    }

    @Test
    void 保护窗口外允许剧情演进取代且保留审计链() {
        StoryFact human = humanPatch("季衡", "命格：不灭玄体", 2);
        // 第 8 章（2+5 <8）收割到冲突断言 → 剧情演进取代
        var agent = agentReturning(
                "[{\"type\":\"CHARACTER_STATE\",\"subject\":\"季衡\",\"assertion\":\"命格：凡体\"}]");
        var result = agent.harvest(pid, chapterAt(8));

        assertEquals(1, result.superseded(), "窗口外允许取代");
        StoryFact old = factRepo.findById(human.getId()).orElseThrow();
        assertEquals(StoryFact.Status.SUPERSEDED, old.getStatus(), "旧人工补丁保留审计（SUPERSEDED）");
        assertNotNull(old.getSupersededBy(), "审计链必须指向新事实");

        var active = factRepo.findActive(pid);
        assertEquals(1, active.size());
        assertEquals(old.getSupersededBy(), active.get(0).getId());
        assertFalse(active.get(0).getAssertion().startsWith(CanonPatchService.HUMAN_PATCH_PREFIX));
    }

    @Test
    void 无HUMAN_PATCH时自动取代行为不变() {
        factRepo.save(new StoryFact(pid, StoryFact.FactType.CHARACTER_STATE, "季衡", "筑基期", 0));
        var agent = agentReturning(
                "[{\"type\":\"CHARACTER_STATE\",\"subject\":\"季衡\",\"assertion\":\"金丹期\"}]");
        var result = agent.harvest(pid, chapterAt(3));
        assertEquals(1, result.superseded(), "普通事实冲突照常取代");
        assertEquals(1, factRepo.findActive(pid).size());
    }
}
