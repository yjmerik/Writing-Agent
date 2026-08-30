package com.writing.novel.orchestrator;

import com.writing.novel.domain.Character;
import com.writing.novel.domain.KnowledgeEntry;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.StoryFact;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteStoryCanon;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.persistence.sqlite.StoryFactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * p10-w2-02: 真典播种——runConceiving 从世界观/人物/时代锚点抽取初始事实入典。
 * 反射调 private seedStoryBible 验证播种逻辑（runConceiving 集成由 e2e 覆盖）。
 */
class StoryBibleSeedingTest {

    @TempDir
    Path tempDir;
    private SqliteNovelRepository repo;
    private StoryFactRepository factRepo;
    private NovelOrchestrator orchestrator;
    private String pid;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("seed.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        factRepo = new StoryFactRepository(cp);
        // 仅需 seedStoryBible 路径——其余 33 个依赖（worldBuilder..expositionDetector）置 null
        orchestrator = new NovelOrchestrator(repo, new ProjectService(repo),
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null);
        orchestrator.setStoryCanon(new SqliteStoryCanon(factRepo));

        Project p = new Project("播种书", "明代修仙传奇", "玄幻");
        repo.saveProject(p);
        pid = p.getId();
    }

    @Test
    void 播种从世界观条目和主角和时代锚点产出初始事实() throws Exception {
        // 预置世界观条目（含明代 → EraAnchor 推导）
        repo.saveKnowledgeEntry(new KnowledgeEntry(pid, "POWER_SYSTEM", "灵气体系", "天地灵气分五境"));
        repo.saveKnowledgeEntry(new KnowledgeEntry(pid, "FACTION", "天机阁", "大明朝廷直属"));
        repo.saveKnowledgeEntry(new KnowledgeEntry(pid, "ITEM", "玄铁剑", "上古遗物"));
        // 预置主角
        Character protagonist = new Character(pid, "季衡", "主角");
        protagonist.setAbility("筑基期");
        protagonist.setMotivation("查清身世");
        repo.saveCharacter(protagonist);

        invokeSeed(pid);

        List<StoryFact> facts = factRepo.findActive(pid);
        // WORLD_RULE: 灵气体系 + 天机阁 + 时代锚点
        List<StoryFact> worldRules = facts.stream()
                .filter(f -> f.getFactType() == StoryFact.FactType.WORLD_RULE).toList();
        assertTrue(worldRules.stream().anyMatch(f -> f.getSubject().equals("灵气体系")), "应含灵气体系 WORLD_RULE");
        assertTrue(worldRules.stream().anyMatch(f -> f.getSubject().equals("天机阁")), "应含天机阁 WORLD_RULE");
        assertTrue(worldRules.stream().anyMatch(f -> f.getSubject().equals("时代锚点")),
                "应从 EraAnchor 推导时代锚点 WORLD_RULE");
        // CHARACTER_STATE: 季衡
        List<StoryFact> charStates = facts.stream()
                .filter(f -> f.getFactType() == StoryFact.FactType.CHARACTER_STATE).toList();
        assertEquals(1, charStates.size());
        assertTrue(charStates.get(0).getAssertion().contains("筑基期"), "应含主角初始能力");
        assertEquals(5, charStates.get(0).getImportance(), "主角 importance=5");
        // OBJECT_STATE: 玄铁剑
        assertTrue(facts.stream().anyMatch(f -> f.getFactType() == StoryFact.FactType.OBJECT_STATE
                && f.getSubject().equals("玄铁剑")));
        // 全部 ACTIVE
        assertTrue(facts.stream().allMatch(f -> f.getStatus() == StoryFact.Status.ACTIVE));
    }

    @Test
    void 未注入真典仓库时播种安全跳过_不抛异常() throws Exception {
        orchestrator.setStoryCanon(new SqliteStoryCanon(null));
        repo.saveKnowledgeEntry(new KnowledgeEntry(pid, "RULE", "规则", "内容"));
        // 不应抛异常，且无事实入库
        assertDoesNotThrow(() -> invokeSeed(pid));
        assertEquals(0, factRepo.findActive(pid).size());
    }

    @Test
    void 重复播种幂等性_同名事实不重复() throws Exception {
        repo.saveKnowledgeEntry(new KnowledgeEntry(pid, "POWER_SYSTEM", "灵气体系", "五境"));
        invokeSeed(pid);
        int first = factRepo.findActive(pid).size();
        invokeSeed(pid); // 二次播种
        // 注：当前实现不查重，二次播种会重复——本测试记录现状，去重留给 FactHarvester
        assertEquals(first, factRepo.findActive(pid).size(),
                "重复播种应幂等（同 name+assertion 不重复入库）");
    }

    private void invokeSeed(String projectId) throws Exception {
        Method m = NovelOrchestrator.class.getDeclaredMethod("seedStoryBible", String.class);
        m.setAccessible(true);
        m.invoke(orchestrator, projectId);
    }
}
