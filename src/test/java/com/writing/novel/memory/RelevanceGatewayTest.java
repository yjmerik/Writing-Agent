package com.writing.novel.memory;

import com.writing.novel.domain.Character;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.StoryFact;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.persistence.sqlite.StoryFactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * p10-w2-04: RelevanceGateway 检索收窄——按 cast 返回相关切片，替代全量加载。
 */
class RelevanceGatewayTest {

    @TempDir
    Path tempDir;
    private SqliteNovelRepository repo;
    private StoryFactRepository factRepo;
    private RelevanceGateway gateway;
    private String pid;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("gw.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        factRepo = new StoryFactRepository(cp);
        gateway = new RelevanceGateway(repo, factRepo);
        Project p = new Project("gw书", "构思", "玄幻");
        repo.saveProject(p);
        pid = p.getId();
    }

    @Test
    void relevantCharacters按cast匹配加主角保底_不回退全量() {
        // 建主角 + 2 配角 + 1 不相关角色
        repo.saveCharacter(new Character(pid, "季衡", "主角"));
        repo.saveCharacter(new Character(pid, "沈墨", "配角"));
        repo.saveCharacter(new Character(pid, "程拾青", "配角"));
        repo.saveCharacter(new Character(pid, "路人甲", "龙套"));

        // cast 含 沈墨
        var out = gateway.relevantCharacters(pid, List.of("沈墨"), 5);
        assertTrue(out.stream().anyMatch(c -> c.getName().equals("沈墨")), "应含 cast 命中的沈墨");
        assertTrue(out.stream().anyMatch(c -> c.getName().equals("季衡")), "主角保底应含季衡");
        assertFalse(out.stream().anyMatch(c -> c.getName().equals("路人甲")), "无关龙套不应含（不回退全量）");
    }

    @Test
    void relevantCharacters受cap限制() {
        for (int i = 0; i < 10; i++) repo.saveCharacter(new Character(pid, "角色" + i, "配角"));
        var out = gateway.relevantCharacters(pid, List.of("角色0", "角色1", "角色2", "角色3"), 3);
        assertEquals(3, out.size(), "应受 cap 限制");
    }

    @Test
    void relevantFacts按cast关联_无关联时按importance降序() {
        StoryFact f1 = new StoryFact(pid, StoryFact.FactType.CHARACTER_STATE, "季衡", "金丹期", 30);
        f1.setImportance(5);
        factRepo.save(f1);
        factRepo.save(new StoryFact(pid, StoryFact.FactType.WORLD_RULE, "天机阁", "朝廷势力", 1));
        factRepo.save(new StoryFact(pid, StoryFact.FactType.OBJECT_STATE, "玄铁剑", "已损毁", 20));

        // cast 含季衡 → 只关联季衡的事实
        var out = gateway.relevantFacts(pid, List.of("季衡"), 10);
        assertEquals(1, out.size());
        assertEquals("季衡", out.get(0).getSubject());

        // 无 cast → importance 降序（季衡 importance=5 在前）
        var all = gateway.relevantFacts(pid, null, 10);
        assertEquals("季衡", all.get(0).getSubject(), "importance 高的在前");
    }

    @Test
    void 空库时返回空列表不报错() {
        assertTrue(gateway.relevantCharacters(pid, List.of("x"), 5).isEmpty());
        assertTrue(gateway.relevantFacts(pid, null, 5).isEmpty());
    }
}
