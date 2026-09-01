package com.writing.novel.service;

import com.writing.novel.agent.TestNovelRepositoryStub;
import com.writing.novel.domain.Character;
import com.writing.novel.domain.KnowledgeEntry;
import com.writing.novel.domain.StoryFact;
import com.writing.novel.memory.MemoryRevision;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteStoryCanon;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.persistence.sqlite.StoryFactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * p13-h1-01: 人改设定必须写真典 + 失效记忆，不能只改卡面。
 */
class CanonPatchServiceTest {

    @TempDir Path tempDir;

    private StoryFactRepository facts;
    private MemoryRevision revision;
    private RecordingRepo repo;
    private CanonPatchService service;
    private com.writing.novel.spi.StoryCanon canon;
    private final String pid = "proj-canon";

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("canon.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        facts = new StoryFactRepository(cp);
        revision = new MemoryRevision();
        repo = new RecordingRepo();
        canon = new SqliteStoryCanon(facts);
        service = new CanonPatchService(repo, canon, revision);
    }

    @Test
    void 改人物能力_旧状态被取代_新事实带人补丁前缀_记忆版本增加() {
        Character c = new Character(pid, "季衡", "主角");
        c.setAbility("筑基初期");
        repo.characters.put(c.getId(), c);

        StoryFact old = new StoryFact(pid, StoryFact.FactType.CHARACTER_STATE, "季衡", "筑基初期", 1);
        facts.save(old);

        long before = revision.current(pid);
        Character patch = new Character(pid, "季衡", "主角");
        patch.setAbility("金丹期");

        var result = service.patchCharacter(pid, c.getId(), patch, 80);

        assertTrue(result.invalidated());
        assertEquals(before + 1, revision.current(pid));
        assertEquals("金丹期", repo.characters.get(c.getId()).getAbility());

        StoryFact oldReloaded = facts.findById(old.getId()).orElseThrow();
        assertEquals(StoryFact.Status.SUPERSEDED, oldReloaded.getStatus());

        var active = facts.findActiveBySubject(pid, "季衡");
        assertEquals(1, active.size());
        assertTrue(active.get(0).getAssertion().startsWith(CanonPatchService.HUMAN_PATCH_PREFIX));
        assertTrue(active.get(0).getAssertion().contains("金丹期"));
        assertEquals(5, active.get(0).getImportance());
        assertEquals(1, result.factsWritten().size());
    }

    @Test
    void 改世界观_写出WORLD_RULE人补丁并失效缓存() {
        KnowledgeEntry e = new KnowledgeEntry(pid, "POWER_SYSTEM", "灵气潮汐", "灵气每旬一涨");
        repo.entries.put(e.getId(), e);

        KnowledgeEntry patch = new KnowledgeEntry(pid, "POWER_SYSTEM", "灵气潮汐", "灵气每旬一涨，满月加倍");

        long before = revision.current(pid);
        var result = service.patchWorld(pid, e.getId(), patch, 12);

        assertTrue(result.invalidated());
        assertEquals(before + 1, revision.current(pid));
        assertEquals("灵气每旬一涨，满月加倍", repo.entries.get(e.getId()).getContent());

        var rules = facts.findActiveByType(pid, StoryFact.FactType.WORLD_RULE);
        assertEquals(1, rules.size());
        assertTrue(rules.get(0).getAssertion().startsWith(CanonPatchService.HUMAN_PATCH_PREFIX));
        assertEquals("灵气潮汐", rules.get(0).getSubject());
    }

    @Test
    void 五章内人补丁受保护_不得被系统自动取代() {
        StoryFact patch = new StoryFact(pid, StoryFact.FactType.CHARACTER_STATE, "季衡",
                CanonPatchService.HUMAN_PATCH_PREFIX + "金丹期", 80);
        patch.setImportance(5);
        patch.setLastValidatedChapter(80);
        facts.save(patch);

        // p22-01: 保护策略随 StoryCanon 端口（接口 default，所有实现一致）
        assertTrue(canon.isProtectedHumanPatch(patch, 84));
        assertFalse(canon.isProtectedHumanPatch(patch, 86));
    }

    @Test
    void 人物不存在_抛错() {
        Character patch = new Character(pid, "无人", "配角");
        assertThrows(IllegalArgumentException.class,
                () -> service.patchCharacter(pid, "missing", patch, 1));
    }

    /** 记录写入的卡面，供断言。 */
    static final class RecordingRepo extends TestNovelRepositoryStub {
        final Map<String, Character> characters = new HashMap<>();
        final Map<String, KnowledgeEntry> entries = new HashMap<>();

        @Override public Optional<Character> findCharacter(String id) {
            return Optional.ofNullable(characters.get(id));
        }
        @Override public String saveCharacter(Character c) {
            characters.put(c.getId(), c);
            return c.getId();
        }
        @Override public Optional<KnowledgeEntry> findKnowledgeEntry(String id) {
            return Optional.ofNullable(entries.get(id));
        }
        @Override public void saveKnowledgeEntry(KnowledgeEntry e) {
            entries.put(e.getId(), e);
        }
    }
}
