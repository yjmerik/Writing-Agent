package com.writing.novel;

import com.writing.novel.domain.*;
import com.writing.novel.domain.Character;
import com.writing.novel.spi.NovelRepository;
import com.writing.novel.spi.VectorStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@TestPropertySource(properties = {
        "persistence.sqlite.db-path=./data/test-smoke.db",
        "persistence.vector-store.index-path=./data/test-smoke-vecs.idx"
})
class PersistenceSmokeTest {

    // SQLite JDBC 不会自动创建父目录；静态 @BeforeAll 早于 Spring 上下文加载，故在此确保目录存在。
    @BeforeAll
    static void createDataDir() {
        new java.io.File("./data").mkdirs();
    }

    @Autowired NovelRepository repo;
    @Autowired VectorStore vectors;

    @Test
    void 全链路存取() {
        // 1. 创建项目
        Project p = new Project("冒烟测试", "剑修复仇的故事", "玄幻,热血");
        repo.saveProject(p);

        // 2. 世界观
        repo.saveKnowledgeEntry(new KnowledgeEntry(p.getId(), "POWER_SYSTEM",
                "灵气体系", "修炼者吸收天地灵气。"));
        repo.saveKnowledgeEntry(new KnowledgeEntry(p.getId(), "GEOGRAPHY",
                "玄黄大陆", "故事主大陆。"));

        // 3. 人物
        Character mc = new Character(p.getId(), "张三", "主角");
        mc.setPersonality("火爆");
        mc.setAbility("剑修");
        repo.saveCharacter(mc);

        // 4. 章节
        Chapter c1 = new Chapter(p.getId(), 1, "第一章");
        c1.setContent("张三踏上复仇之路。");
        repo.saveChapter(c1);

        // 5. 摘要
        repo.saveSummary(new Summary(p.getId(), 1, 1, "主角踏上复仇之路。"));

        // 6. 向量
        vectors.upsert("mc-char", p.getId(), "主角张三火爆剑修", new float[]{1.0f});

        // --- 验证查询 ---
        Project found = repo.findProject(p.getId()).orElseThrow();
        assertEquals("冒烟测试", found.getTitle());

        List<KnowledgeEntry> entries = repo.findKnowledgeEntriesByProject(p.getId());
        assertEquals(2, entries.size());

        List<Character> chars = repo.findCharactersByProject(p.getId());
        assertEquals(1, chars.size());
        assertEquals("火爆", chars.get(0).getPersonality());

        List<Chapter> chapters = repo.findChaptersByProject(p.getId());
        assertEquals(1, chapters.size());

        List<Summary> summaries = repo.findSummariesByProject(p.getId());
        assertEquals(1, summaries.size());

        assertEquals("主角张三火爆剑修", vectors.getText("mc-char"));
    }
}
