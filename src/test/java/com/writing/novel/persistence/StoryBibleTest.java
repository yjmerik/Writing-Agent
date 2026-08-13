package com.writing.novel.persistence;

import com.writing.novel.domain.StoryFact;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.persistence.sqlite.StoryFactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * p10-w2-01: 故事真典 story_facts CRUD + supercession 审计链。
 */
class StoryBibleTest {

    @TempDir
    Path tempDir;
    private StoryFactRepository repo;
    private String pid = "proj-1";

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("bible.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new StoryFactRepository(cp);
    }

    @Test
    void saveFind往返_默认ACTIVE() {
        StoryFact f = new StoryFact(pid, StoryFact.FactType.WORLD_RULE, "时代", "明代背景，禁清代年号", 1);
        repo.save(f);
        StoryFact loaded = repo.findById(f.getId()).orElseThrow();
        assertEquals(StoryFact.Status.ACTIVE, loaded.getStatus());
        assertEquals("明代背景，禁清代年号", loaded.getAssertion());
        assertEquals(3, loaded.getImportance(), "默认重要性 3");
    }

    @Test
    void findActive只返回ACTIVE_按importance降序() {
        repo.save(new StoryFact(pid, StoryFact.FactType.CHARACTER_STATE, "季衡", "筑基期", 1));
        StoryFact high = new StoryFact(pid, StoryFact.FactType.CHARACTER_STATE, "季衡", "金丹期", 30);
        high.setImportance(5);
        repo.save(high);
        StoryFact retired = new StoryFact(pid, StoryFact.FactType.OBJECT_STATE, "铜钱", "已丢失", 5);
        retired.retire();
        repo.save(retired);

        List<StoryFact> active = repo.findActive(pid);
        assertEquals(2, active.size(), "RETIRED 不计入 ACTIVE");
        assertEquals(5, active.get(0).getImportance(), "importance 高的排前面");
        assertEquals("金丹期", active.get(0).getAssertion());
    }

    @Test
    void supersede建立审计链_旧SUPERSEDED新ACTIVE() {
        StoryFact old = new StoryFact(pid, StoryFact.FactType.CHARACTER_STATE, "季衡", "筑基期", 1);
        repo.save(old);
        StoryFact newer = new StoryFact(pid, StoryFact.FactType.CHARACTER_STATE, "季衡", "金丹期（第30章突破）", 30);
        repo.supersede(old, newer);

        // 旧事实：SUPERSEDED + 指向新
        StoryFact oldReloaded = repo.findById(old.getId()).orElseThrow();
        assertEquals(StoryFact.Status.SUPERSEDED, oldReloaded.getStatus());
        assertEquals(newer.getId(), oldReloaded.getSupersededBy(), "旧事实 supersededBy 应指向新事实");

        // 新事实：ACTIVE
        StoryFact newReloaded = repo.findById(newer.getId()).orElseThrow();
        assertEquals(StoryFact.Status.ACTIVE, newReloaded.getStatus());

        // findActive 只含新事实（旧的不注入上下文）
        List<StoryFact> active = repo.findActive(pid);
        assertEquals(1, active.size());
        assertEquals("金丹期（第30章突破）", active.get(0).getAssertion());

        // 审计链：旧事实仍可查（永不删除）
        assertEquals(2, repo.findAll(pid).size(), "SUPERSEDED 事实保留在 findAll（审计）");
    }

    @Test
    void findBySubject与findByType过滤() {
        repo.save(new StoryFact(pid, StoryFact.FactType.CHARACTER_STATE, "季衡", "主角", 1));
        repo.save(new StoryFact(pid, StoryFact.FactType.CHARACTER_STATE, "沈墨", "反派", 5));
        repo.save(new StoryFact(pid, StoryFact.FactType.WORLD_RULE, "力量体系", "五境制", 1));

        assertEquals(1, repo.findActiveBySubject(pid, "季衡").size());
        assertEquals(2, repo.findActiveByType(pid, StoryFact.FactType.CHARACTER_STATE).size());
        assertEquals(1, repo.findActiveByType(pid, StoryFact.FactType.WORLD_RULE).size());
    }

    @Test
    void validateAt刷新最近确认章_不改状态() {
        StoryFact f = new StoryFact(pid, StoryFact.FactType.RELATIONSHIP_STATE, "季衡", "与沈墨敌对", 10);
        repo.save(f);
        f.validateAt(50);
        repo.save(f);
        assertEquals(50, repo.findById(f.getId()).orElseThrow().getLastValidatedChapter());
        assertEquals(StoryFact.Status.ACTIVE, f.getStatus(), "validate 不改状态");
    }
}
