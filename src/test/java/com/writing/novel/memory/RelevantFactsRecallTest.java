package com.writing.novel.memory;

import com.writing.novel.domain.Project;
import com.writing.novel.domain.StoryFact;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.persistence.sqlite.StoryFactRepository;
import com.writing.novel.service.CanonPatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RelevantFactsRecallTest {

    @TempDir Path tempDir;
    private StoryFactRepository factRepo;
    private RelevanceGateway gateway;
    private String pid;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("rf.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        SqliteNovelRepository repo = new SqliteNovelRepository(cp);
        factRepo = new StoryFactRepository(cp);
        gateway = new RelevanceGateway(repo, factRepo);
        Project p = new Project("召回", "构思", "玄幻");
        repo.saveProject(p);
        pid = p.getId();
    }

    @Test
    void 八十条事实时人补丁与铁律与本章角色必入() {
        StoryFact iron = new StoryFact(pid, StoryFact.FactType.WORLD_RULE, "灵气", "第1卷铁律：灵气不可逆", 1);
        iron.setImportance(5);
        factRepo.save(iron);

        StoryFact patch = new StoryFact(pid, StoryFact.FactType.CHARACTER_STATE, "季衡",
                CanonPatchService.HUMAN_PATCH_PREFIX + "金丹期", 80);
        patch.setImportance(5);
        factRepo.save(patch);

        StoryFact castState = new StoryFact(pid, StoryFact.FactType.CHARACTER_STATE, "沈墨", "在京中", 80);
        factRepo.save(castState);

        for (int i = 0; i < 80; i++) {
            StoryFact noise = new StoryFact(pid, StoryFact.FactType.OBJECT_STATE, "杂物" + i, "无意义" + i, i + 1);
            noise.setImportance(1);
            factRepo.save(noise);
        }

        var out = gateway.relevantFacts(pid, List.of("沈墨"), 80, 40);
        assertTrue(out.stream().anyMatch(f -> f.getAssertion().contains("第1卷铁律")), "第1卷铁律必须召回");
        assertTrue(out.stream().anyMatch(f -> f.getAssertion().startsWith(CanonPatchService.HUMAN_PATCH_PREFIX)),
                "人补丁必须召回");
        assertTrue(out.stream().anyMatch(f -> "沈墨".equals(f.getSubject())), "本章 cast 状态必须召回");
        assertTrue(out.size() <= 40);
    }
}
