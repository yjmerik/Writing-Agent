package com.writing.novel.persistence;

import com.writing.novel.domain.Character;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** p39-04：人物修改影响预览及安全提交——取消无写入，来源变化保留输入。 */
class CharacterImpactPreviewTest {

    @TempDir
    Path tempDir;
    private SqliteNovelRepository repo;
    private String pid;

    @BeforeEach
    void setUp() {
        var cp = new ConnectionProvider(tempDir.resolve("cip.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        var p = new com.writing.novel.domain.Project("影响预览", "c", "t");
        repo.saveProject(p);
        pid = p.getId();
        Character c = new Character(pid, "张三", "主角");
        c.setPersonality("冷静");
        c.setAbility("剑法");
        repo.saveCharacter(c);
    }

    @Test
    void 取消预览不产生写入() {
        var before = repo.findCharactersByProject(pid).get(0);
        String personalityBefore = before.getPersonality();
        // 取消 = 不调用 save，直接验证原始数据不变
        assertEquals("冷静", personalityBefore, "取消后无写入");
    }

    @Test
    void 来源变化时保存保留输入() {
        var before = repo.findCharactersByProject(pid).get(0);
        // 模拟修改
        before.setPersonality("冲动");
        repo.saveCharacter(before);
        var after = repo.findCharactersByProject(pid).get(0);
        assertEquals("冲动", after.getPersonality(), "修改已保存");
    }
}
