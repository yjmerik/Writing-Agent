package com.writing.novel.spi;

import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.Character;
import com.writing.novel.domain.Foreshadowing;
import com.writing.novel.domain.Project;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NovelRepository 契约测试基类。任何实现都应继承此类，
 * 用相同断言验证行为一致（保证 SQLite/MySQL/Postgres 实现可互换）。
 */
public abstract class NovelRepositoryContractTest {

    /** 子类提供被测实例 */
    protected abstract NovelRepository repository();

    @Test
    void 项目的保存与查询() {
        Project p = new Project("契约测试", "简介", "玄幻");
        String id = repository().saveProject(p);

        Optional<Project> found = repository().findProject(id);
        assertTrue(found.isPresent());
        assertEquals("契约测试", found.get().getTitle());
    }

    @Test
    void 人物的保存与按项目查询() {
        Project p = new Project("t", "c", "tag");
        repository().saveProject(p);
        Character c = new Character(p.getId(), "李四", "配角");
        repository().saveCharacter(c);

        List<Character> chars = repository().findCharactersByProject(p.getId());
        assertEquals(1, chars.size());
        assertEquals("李四", chars.get(0).getName());
    }

    @Test
    void 章节按序号查询与最近章节() {
        Project p = new Project("t", "c", "tag");
        repository().saveProject(p);
        Chapter c1 = new Chapter(p.getId(), 1, "第一章");
        c1.setContent("内容一");
        Chapter c2 = new Chapter(p.getId(), 2, "第二章");
        c2.setContent("内容二");
        repository().saveChapter(c1);
        repository().saveChapter(c2);

        Optional<Chapter> byIndex = repository().findChapterByIndex(p.getId(), 2);
        assertTrue(byIndex.isPresent());
        assertEquals("第二章", byIndex.get().getTitle());

        List<Chapter> recent = repository().findRecentChapters(p.getId(), 1);
        assertEquals(1, recent.size());
        assertEquals(2, recent.get(0).getChapterIndex());
    }

    @Test
    void 伏笔未回收查询() {
        Project p = new Project("t", "c", "tag");
        repository().saveProject(p);
        Foreshadowing resolved = new Foreshadowing(p.getId(), "已回收", "ctx", 1);
        resolved.resolve(10);
        Foreshadowing open = new Foreshadowing(p.getId(), "未回收", "ctx", 2);
        repository().saveForeshadowing(resolved);
        repository().saveForeshadowing(open);

        List<Foreshadowing> pending = repository().findUnresolvedForeshadowings(p.getId());
        assertEquals(1, pending.size());
        assertEquals("未回收", pending.get(0).getDescription());
    }
}
