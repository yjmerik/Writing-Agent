package com.writing.novel.memory;

import com.writing.novel.domain.Character;
import com.writing.novel.domain.Entity;
import com.writing.novel.domain.EntityType;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.StoryFact;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.persistence.sqlite.SqliteStoryCanon;
import com.writing.novel.persistence.sqlite.StoryFactRepository;
import com.writing.novel.service.EntityGraphService;
import com.writing.novel.spi.StoryCanon;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-109: RelevanceGateway 相关性硬化——
 * 1) 别名回解：resolveCanonical 查别名表；cast 写别名时真典/角色照常命中；
 * 2) 时间有效性：生效区间（validFrom/validTo）外的事实不注入；
 * 3) must 截断保序：cast 命中与人工补丁优先于 importance 排序。
 */
class RelevanceHardeningTest {

    @TempDir
    Path tempDir;
    private SqliteNovelRepository repository;
    private StoryFactRepository factRepo;
    private StoryCanon canon;
    private RelevanceGateway gateway;
    private EntityGraphService entityGraph;
    private String pid;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("hardening.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repository = new SqliteNovelRepository(cp);
        factRepo = new StoryFactRepository(cp);
        canon = new SqliteStoryCanon(factRepo);
        gateway = new RelevanceGateway(repository, canon);
        entityGraph = new EntityGraphService(repository, null);

        var project = new Project("硬化测试", "构思", "wuxia");
        repository.saveProject(project);
        pid = project.getId();

        Entity lin = new Entity(pid, "林凡", EntityType.CHARACTER);
        lin.addAlias("林公子");
        repository.saveEntity(lin);
        repository.saveCharacter(new Character(pid, "林凡", "主角"));
    }

    @Test
    void 别名回解_规范名解析() {
        assertEquals("林凡", entityGraph.resolveCanonical(pid, "林公子"),
                "别名应回解为规范名（此前只查 name）");
        assertEquals("林凡", entityGraph.resolveCanonical(pid, "林凡"));
        assertEquals("陌生名", entityGraph.resolveCanonical(pid, "陌生名"));
    }

    @Test
    void cast写别名_真典与角色照常命中() {
        StoryFact fact = new StoryFact(pid, StoryFact.FactType.CHARACTER_STATE, "林凡", "左手旧伤", 1);
        fact.setPredicate("伤势");
        factRepo.save(fact);

        // 大纲 cast 只写「林公子」——旧逻辑 subject=林凡 contains 林公子 不成立 → miss
        var facts = gateway.relevantFacts(pid, List.of("林公子"), 5, 40);
        assertTrue(facts.stream().anyMatch(f -> "左手旧伤".equals(f.getAssertion())),
                "cast 别名经扩展后应命中规范名事实");
        var characters = gateway.relevantCharacters(pid, List.of("林公子"), 8);
        assertTrue(characters.stream().anyMatch(c -> "林凡".equals(c.getName())),
                "角色匹配同样受益于别名扩展");
    }

    @Test
    void 时间有效性_区间外事实不注入() {
        StoryFact expired = new StoryFact(pid, StoryFact.FactType.CHARACTER_STATE, "林凡", "暂居渡口", 1);
        expired.setValidToChapter(3);
        factRepo.save(expired);
        StoryFact future = new StoryFact(pid, StoryFact.FactType.CHARACTER_STATE, "林凡", "已至北境", 1);
        future.setValidFromChapter(6);
        factRepo.save(future);
        StoryFact active = new StoryFact(pid, StoryFact.FactType.CHARACTER_STATE, "林凡", "持断剑", 1);
        factRepo.save(active);

        var at5 = gateway.relevantFacts(pid, List.of("林凡"), 5, 40);
        assertTrue(at5.stream().noneMatch(f -> f.getAssertion().contains("渡口")), "validTo=3 的事实在第5章失效");
        assertTrue(at5.stream().noneMatch(f -> f.getAssertion().contains("北境")), "validFrom=6 的事实第5章未生效");
        assertTrue(at5.stream().anyMatch(f -> f.getAssertion().contains("断剑")));

        // chapterIndex=0（上下文未知，如旧调用路径）保守放行
        var at0 = gateway.relevantFacts(pid, List.of("林凡"), 0, 40);
        assertEquals(at0.size(), at5.size() + 2);
    }

    @Test
    void must截断保序_cast与人工补丁优先() {
        // 25 条高重要度 WORLD_RULE（CONFIRMED，恒 must，非 cast 命中）
        for (int i = 0; i < 25; i++) {
            StoryFact rule = new StoryFact(pid, StoryFact.FactType.WORLD_RULE, "规则" + i, "铁律" + i, 1);
            rule.setImportance(4);
            factRepo.save(rule);
        }
        // 1 条 cast 命中的低重要度角色状态（正文核心，must）
        StoryFact castState = new StoryFact(pid, StoryFact.FactType.CHARACTER_STATE, "林凡", "左手旧伤未愈", 1);
        castState.setImportance(2);
        factRepo.save(castState);
        // 1 条低重要度人工补丁（恒 must）
        StoryFact patch = canon.applyHumanPatch(pid, StoryFact.FactType.WORLD_RULE, "铁律",
                "全书不得出现火器", 1, 1);

        var out = gateway.relevantFacts(pid, List.of("林凡"), 5, 10);
        // must 总数 27 > mustCap=max(10,24)=24 → 截断 3 条；被截的必须是排序最低的世界铁律
        assertTrue(out.stream().anyMatch(f -> f.getAssertion().contains("旧伤未愈")),
                "cast 命中的关键状态不得因 importance 低被静默丢弃");
        assertTrue(out.stream().anyMatch(f -> f.getAssertion().contains("火器")),
                "人工补丁保持恒注入优先级");
        long rules = out.stream().filter(f -> f.getAssertion().startsWith("铁律")).count();
        assertEquals(22, rules, "25 条同档铁律按序保留 22 条");
    }
}
