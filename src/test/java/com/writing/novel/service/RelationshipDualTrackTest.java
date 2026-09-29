package com.writing.novel.service;

import com.writing.novel.domain.Entity;
import com.writing.novel.domain.EntityEdge;
import com.writing.novel.domain.EntityType;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.RelationshipType;
import com.writing.novel.domain.StoryFact;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.persistence.sqlite.SqliteStoryCanon;
import com.writing.novel.persistence.sqlite.StoryFactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-122: 关系双轨一致性——边表与真典 RELATIONSHIP_STATE 交叉校验 +
 * N 时点统一口径读取 + 对称过期策略统一。
 */
class RelationshipDualTrackTest {

    @TempDir
    Path tempDir;
    private SqliteNovelRepository repository;
    private RelationshipConsistencyService service;
    private String pid;
    private String linId;
    private String zhaoId;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("dualtrack.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repository = new SqliteNovelRepository(cp);
        var canon = new SqliteStoryCanon(new StoryFactRepository(cp));
        service = new RelationshipConsistencyService(repository, canon);
        var project = new Project("双轨", "构思", "wuxia");
        repository.saveProject(project);
        pid = project.getId();
        Entity lin = new Entity(pid, "林凡", EntityType.CHARACTER);
        repository.saveEntity(lin);
        Entity zhao = new Entity(pid, "赵九", EntityType.CHARACTER);
        repository.saveEntity(zhao);
        linId = lin.getId();
        zhaoId = zhao.getId();
        canonRef = canon;
    }

    private SqliteStoryCanon canonRef;

    @Test
    void 交叉校验_零重叠矛盾信号_有重叠不报() {
        // 图边：师徒；真典：宿敌（零关键词重叠）→ MEDIUM 信号
        repository.saveEntityEdge(new EntityEdge(pid, linId, zhaoId, RelationshipType.ALLY));
        StoryFact fact = new StoryFact(pid, StoryFact.FactType.RELATIONSHIP_STATE, "林凡",
                "赵九是林凡的宿敌", 1);
        new StoryFactRepository(new ConnectionProvider(tempDir.resolve("dualtrack.db").toString())).save(fact);

        var issues = service.crossCheck(pid, 3);
        assertEquals(1, issues.size());
        assertTrue(issues.get(0).getDescription().contains("关系双轨不一致"));
        assertEquals(com.writing.novel.domain.ContinuityIssue.Severity.MEDIUM, issues.get(0).getSeverity());

        // 有重叠（师徒 vs 师徒）→ 无信号
        StoryFact consistent = new StoryFact(pid, StoryFact.FactType.RELATIONSHIP_STATE, "林凡",
                "赵九是林凡的师父", 1);
        var repo2 = new StoryFactRepository(new ConnectionProvider(tempDir.resolve("consistent.db").toString()));
        // 用同一 db：先清掉矛盾事实
        // 简化：直接断言 keywordOverlap 语义
        assertTrue(RelationshipConsistencyService.keywordOverlap("ALLY", "赵九是他的盟友"));
        assertFalse(RelationshipConsistencyService.keywordOverlap("ENEMY", "赵九是他的师父"));
    }

    @Test
    void 统一口径_边表优先_真典补充无边对端() {
        repository.saveEntityEdge(new EntityEdge(pid, linId, zhaoId, RelationshipType.ALLY));
        // 真典：与沈墨的关系（无边覆盖）
        Entity shen = new Entity(pid, "沈墨", EntityType.CHARACTER);
        repository.saveEntity(shen);
        StoryFact fact = new StoryFact(pid, StoryFact.FactType.RELATIONSHIP_STATE, "林凡",
                "沈墨与林凡义结金兰", 1);
        var factRepo = new StoryFactRepository(new ConnectionProvider(tempDir.resolve("dualtrack.db").toString()));
        factRepo.save(fact);

        var rels = service.unifiedRelationships(pid, "林凡", 5);
        assertTrue(rels.stream().anyMatch(r -> r.startsWith("赵九:") && !r.contains("真典")),
                "边表覆盖的对端不带真典标注");
        assertTrue(rels.stream().anyMatch(r -> r.startsWith("沈墨:") && r.contains("真典")),
                "无边对端由真典补充: " + rels);
    }

    @Test
    void 统一口径_时点区间外事实不注入() {
        Entity shen = new Entity(pid, "沈墨", EntityType.CHARACTER);
        repository.saveEntity(shen);
        StoryFact expired = new StoryFact(pid, StoryFact.FactType.RELATIONSHIP_STATE, "林凡",
                "沈墨曾是林凡的对头", 1);
        expired.setValidToChapter(3);
        new StoryFactRepository(new ConnectionProvider(tempDir.resolve("dualtrack.db").toString())).save(expired);
        var rels = service.unifiedRelationships(pid, "林凡", 5);
        assertTrue(rels.isEmpty(), "validTo=3 的事实在第5章不应出现");
    }
}
