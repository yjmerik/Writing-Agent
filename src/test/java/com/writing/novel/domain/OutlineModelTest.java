package com.writing.novel.domain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OutlineModelTest {

    @Test
    void 大纲由总纲与多卷组成() {
        Outline o = new Outline("proj-1", "主角从凡人修炼至大乘的史诗。");
        Volume v1 = new Volume("proj-1", 1, "初入仙门", "主角拜入青云宗。");
        v1.addSynopsis(new ChapterSynopsis("proj-1", 1, "第一章 灵根测试",
                "张三参加灵根测试，意外觉醒上古剑骨。"));
        o.addVolume(v1);

        assertEquals("主角从凡人修炼至大乘的史诗。", o.getPremise());
        assertEquals(1, o.getVolumes().size());
        assertEquals("初入仙门", o.getVolumes().get(0).getTitle());
        assertEquals(1, o.getVolumes().get(0).getSynopses().size());
    }

    @Test
    void 伏笔可标记为已回收() {
        Foreshadowing f = new Foreshadowing("proj-1", "主角血脉之谜",
                "第1章主角觉醒剑骨暗示其特殊血脉。", 1);
        assertFalse(f.isResolved());
        f.resolve(45);  // 第45章回收
        assertTrue(f.isResolved());
        assertEquals(45, f.getResolvedAtChapter());
    }

    @Test
    void 章节包含正文与字数() {
        Chapter c = new Chapter("proj-1", 1, "第一章 灵根测试");
        c.setContent("这是正文内容。");
        assertEquals("第一章 灵根测试", c.getTitle());
        assertEquals("这是正文内容。", c.getContent());
    }
}
