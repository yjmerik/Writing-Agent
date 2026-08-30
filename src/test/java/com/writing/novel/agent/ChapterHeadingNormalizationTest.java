package com.writing.novel.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * p20-09: 章节号归一化只作用于开头章节标题。
 *
 * 旧实现 content.replaceAll("第\\d+章", ...) 把对话、引文、跨章引用中的"第N章"
 * 也一并改写，破坏正文逐字内容。修复后仅归一化正文开头的章节标题。
 */
class ChapterHeadingNormalizationTest {

    private static final int CHAPTER = 2;

    @Test
    void 开头错误章节标题被归一化() {
        String in = "第9章 修炼：初次修炼。\n张三盘膝而坐。";
        String out = ChapterWriterAgent.sanitizeChapterNumbers(in, CHAPTER);
        assertEquals("第2章 修炼：初次修炼。\n张三盘膝而坐。", out);
    }

    @Test
    void 对话中的跨章引用保持逐字不动() {
        String in = "第2章 试炼\n「这就是第7章里说的剑法？」张三问。";
        String out = ChapterWriterAgent.sanitizeChapterNumbers(in, CHAPTER);
        assertEquals(in, out, "对话/引文中的第N章必须逐字保留");
    }

    @Test
    void 正文叙述中的跨章引用保持逐字不动() {
        String in = "第2章 回忆\n他想起了第3章发生的事，那是第1章埋下的伏笔。";
        String out = ChapterWriterAgent.sanitizeChapterNumbers(in, CHAPTER);
        assertEquals(in, out, "叙述中的跨章引用必须逐字保留");
    }

    @Test
    void 前导空白与井号标题仍可归一化() {
        String in = "  #第9章 试炼之路\n正文。";
        String out = ChapterWriterAgent.sanitizeChapterNumbers(in, CHAPTER);
        assertEquals("  #第2章 试炼之路\n正文。", out, "前导空白保留，标题号归一化");
    }

    @Test
    void 开头无章节标题时全文原样() {
        String in = "晨雾未散，张三推门而出，想起第5章的约定。";
        String out = ChapterWriterAgent.sanitizeChapterNumbers(in, CHAPTER);
        assertEquals(in, out);
    }

    @Test
    void 正确标题是幂等noOp() {
        String in = "第2章 试炼\n后续提到第9章的内容。";
        String out = ChapterWriterAgent.sanitizeChapterNumbers(in, CHAPTER);
        assertEquals(in, out);
    }

    @Test
    void null输入原样返回() {
        assertNull(ChapterWriterAgent.sanitizeChapterNumbers(null, CHAPTER));
    }
}
