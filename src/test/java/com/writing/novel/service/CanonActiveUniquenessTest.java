package com.writing.novel.service;

import com.writing.novel.agent.TestNovelRepositoryStub;
import com.writing.novel.domain.Character;
import com.writing.novel.domain.StoryFact;
import com.writing.novel.memory.MemoryRevision;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.persistence.sqlite.StoryFactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * p20-08: 人工编辑的真典 ACTIVE 唯一性。
 *
 * 同一 project/type/subject 的人工补丁重复写入时，必须取代前一条 HUMAN_PATCH
 * （保护窗口只约束自动收割，不约束人工自己），保证每 subject+type 恰好一条 ACTIVE。
 */
class CanonActiveUniquenessTest {

    @TempDir Path tempDir;
    private StoryFactRepository facts;
    private CanonPatchService service;
    private final String pid = "proj-unique";

    static final class RecordingRepo extends TestNovelRepositoryStub {
        final Map<String, Character> characters = new HashMap<>();
        @Override public Optional<Character> findCharacter(String id) {
            return Optional.ofNullable(characters.get(id));
        }
        @Override public String saveCharacter(Character c) {
            characters.put(c.getId(), c);
            return c.getId();
        }
    }

    private RecordingRepo repo;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("unique.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        facts = new StoryFactRepository(cp);
        repo = new RecordingRepo();
        service = new CanonPatchService(repo, facts, new MemoryRevision());
    }

    @Test
    void 保护窗口内重复人工编辑仍只留一条ACTIVE() {
        Character c = new Character(pid, "季衡", "主角");
        repo.characters.put(c.getId(), c);

        Character patch1 = new Character(pid, "季衡", "主角");
        patch1.setAbility("筑基初期");
        service.patchCharacter(pid, c.getId(), patch1, 3);

        Character patch2 = new Character(pid, "季衡", "主角");
        patch2.setAbility("金丹期");
        service.patchCharacter(pid, c.getId(), patch2, 5);

        List<StoryFact> active = facts.findActiveBySubject(pid, "季衡").stream()
                .filter(f -> f.getFactType() == StoryFact.FactType.CHARACTER_STATE)
                .toList();
        assertEquals(1, active.size(),
                "保护窗口内的人工再编辑必须取代前一条 HUMAN_PATCH，不得产生平行 ACTIVE");
        assertTrue(active.get(0).getAssertion().contains("金丹期"));
        assertTrue(active.get(0).getAssertion().startsWith(CanonPatchService.HUMAN_PATCH_PREFIX));
    }

    @Test
    void addFact同类型同主体重复写入只留一条ACTIVE且审计链完整() {
        service.addFact(pid, StoryFact.FactType.PLOT_COMMITMENT, "总纲",
                "主角终将复仇", 1);
        service.addFact(pid, StoryFact.FactType.PLOT_COMMITMENT, "总纲",
                "主角放弃复仇，选择守护", 4);

        List<StoryFact> active = facts.findActiveBySubject(pid, "总纲").stream()
                .filter(f -> f.getFactType() == StoryFact.FactType.PLOT_COMMITMENT)
                .toList();
        assertEquals(1, active.size());
        assertTrue(active.get(0).getAssertion().contains("守护"));

        // 审计链：旧事实 SUPERSEDED 且指向新事实
        long supersededCount = facts.findAll(pid).stream()
                .filter(f -> f.getStatus() == StoryFact.Status.SUPERSEDED)
                .count();
        assertEquals(1, supersededCount, "被取代的人工事实保留审计");
        StoryFact supersededOld = facts.findAll(pid).stream()
                .filter(f -> f.getStatus() == StoryFact.Status.SUPERSEDED).findFirst().orElseThrow();
        assertEquals(active.get(0).getId(), supersededOld.getSupersededBy());
    }

    @Test
    void patchFact人工编辑取代被编辑事实() {
        StoryFact seed = service.addFact(pid, StoryFact.FactType.WORLD_RULE, "境界体系",
                "九境三阶", 1);
        StoryFact edited = (StoryFact) service.patchFact(pid, seed.getId(), "十二境三阶", null, 2).entity();

        List<StoryFact> active = facts.findActiveBySubject(pid, "境界体系").stream()
                .filter(f -> f.getFactType() == StoryFact.FactType.WORLD_RULE)
                .toList();
        assertEquals(1, active.size(), "人工编辑后仅一条 ACTIVE");
        assertEquals(edited.getId(), active.get(0).getId());
    }
}
