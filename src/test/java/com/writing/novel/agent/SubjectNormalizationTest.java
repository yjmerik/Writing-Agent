package com.writing.novel.agent;

import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.Entity;
import com.writing.novel.domain.EntityType;
import com.writing.novel.domain.StoryFact;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteStoryCanon;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.persistence.sqlite.StoryFactRepository;
import com.writing.novel.service.EntityGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * p19-02: 真典 subject 规范化 + story_facts 索引。
 * 1) story_facts 缺 (project_id, status) 索引——findActive 每章被调 5+ 次全表扫，千条事实时劣化
 * 2) merge 按精确字符串去重——「林公子」与「林凡」是同一实体却分裂成平行 ACTIVE 事实；
 *    应先经 EntityGraphService.resolveCanonical 解析为规范名再比对
 */
class SubjectNormalizationTest {

    @TempDir
    Path tempDir;
    private ConnectionProvider cp;
    private StoryFactRepository factRepo;
    private SqliteNovelRepository repo;
    private final String pid = "proj-p19-02";

    @BeforeEach
    void setUp() {
        cp = new ConnectionProvider(tempDir.resolve("p1902.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        factRepo = new StoryFactRepository(cp);
    }

    private Chapter chapter(String content) {
        Chapter c = new Chapter(pid, 5, "第五章");
        c.setContent(content);
        return c;
    }

    /** 已有实体：规范名 林凡，别名 林公子 */
    private void seedAliasEntity() {
        Entity e = new Entity(pid, "林凡", EntityType.CHARACTER);
        e.addAlias("林公子");
        repo.saveEntity(e);
    }

    private FactHarvesterAgent agentWithGraphReturning(String json) {
        LlmGateway gw = mock(LlmGateway.class);
        when(gw.complete(any())).thenReturn(new LlmResponse(json, 100, 50));
        FactHarvesterAgent agent = new FactHarvesterAgent(gw, new SqliteStoryCanon(factRepo), TestPromptHelper.create());
        agent.setEntityGraphService(new EntityGraphService(repo, null));
        return agent;
    }

    @Test
    void story_facts建立project_status索引() throws Exception {
        List<String> names = new ArrayList<>();
        try (Connection conn = cp.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA index_list(story_facts)")) {
            while (rs.next()) names.add(rs.getString("name"));
        }
        assertTrue(names.contains("idx_story_facts_project_status"),
                "story_facts 应有 (project_id,status) 索引，实际索引: " + names);
    }

    @Test
    void 别名主体规范化_一致事实validate不分裂() {
        seedAliasEntity();
        factRepo.save(new StoryFact(pid, StoryFact.FactType.CHARACTER_STATE, "林凡", "筑基期修为", 0));
        FactHarvesterAgent agent = agentWithGraphReturning(
                "[{\"type\":\"CHARACTER_STATE\",\"subject\":\"林公子\",\"assertion\":\"筑基期修为（已稳固）\"}]");
        var result = agent.harvest(pid, chapter("林公子在客栈中吐纳修炼。"));

        assertEquals(0, result.added(), "别名命中既有主体应不新增");
        assertEquals(1, result.validated(), "一致事实应 validate 刷新");
        var active = factRepo.findActive(pid);
        assertEquals(1, active.size(), "不应产生平行 ACTIVE 事实");
        assertEquals("林凡", active.get(0).getSubject(), "subject 应规范化为规范名");
        assertTrue(factRepo.findActiveBySubject(pid, "林公子").isEmpty(),
                "不应存在以别名为主体的 ACTIVE 事实");
    }

    @Test
    void 别名主体规范化_冲突事实supersede到规范名下() {
        seedAliasEntity();
        factRepo.save(new StoryFact(pid, StoryFact.FactType.CHARACTER_STATE, "林凡", "筑基期", 0));
        FactHarvesterAgent agent = agentWithGraphReturning(
                "[{\"type\":\"CHARACTER_STATE\",\"subject\":\"林公子\",\"assertion\":\"元婴期\"}]");
        var result = agent.harvest(pid, chapter("林公子一举突破元婴。"));

        assertEquals(1, result.superseded(), "冲突应 supersede");
        var active = factRepo.findActive(pid);
        assertEquals(1, active.size(), "ACTIVE 仍 1 条（旧被取代）");
        assertEquals("林凡", active.get(0).getSubject(), "新 ACTIVE 事实应挂在规范名下");
        assertEquals("元婴期", active.get(0).getAssertion());
        assertTrue(factRepo.findActiveBySubject(pid, "林公子").isEmpty(),
                "不应存在以别名为主体的 ACTIVE 事实");
    }

    @Test
    void 未注入EntityGraphService时降级为原行为() {
        factRepo.save(new StoryFact(pid, StoryFact.FactType.CHARACTER_STATE, "林凡", "筑基期修为", 0));
        LlmGateway gw = mock(LlmGateway.class);
        when(gw.complete(any())).thenReturn(new LlmResponse(
                "[{\"type\":\"CHARACTER_STATE\",\"subject\":\"林公子\",\"assertion\":\"筑基期修为（初期）\"}]",
                100, 50));
        FactHarvesterAgent agent = new FactHarvesterAgent(gw, new SqliteStoryCanon(factRepo), TestPromptHelper.create());
        // 不调 setEntityGraphService → null 安全降级，subject 原样入库
        var result = agent.harvest(pid, chapter("林公子吐纳。"));
        assertEquals(1, result.added(), "无图服务时保持原行为（按原始 subject 新增）");
    }
}
