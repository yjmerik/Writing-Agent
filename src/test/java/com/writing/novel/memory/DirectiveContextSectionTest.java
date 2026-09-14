package com.writing.novel.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * p37-09：写作上下文容纳不可静默裁剪的作者要求。
 * 无要求时与原 prompt 逐字相同；有要求时【作者要求】在原旨之后稳定输出且正文只出现一次；原旨不丢失。
 * 节内容格式由组装方（p37-10）构造，每项含 directiveId/revision/kind/from-to/content。
 */
class DirectiveContextSectionTest {

    private WritingContext contextWithPremise() {
        WritingContext ctx = new WritingContext();
        ctx.setPremiseSection("原旨：账册背后的血案。");
        return ctx;
    }

    @Test
    void 无要求时与原prompt逐字相同() {
        WritingContext base = contextWithPremise();
        WritingContext withNull = contextWithPremise();
        withNull.setAuthorDirectiveSection(null);
        WritingContext withBlank = contextWithPremise();
        withBlank.setAuthorDirectiveSection("   ");

        assertEquals(base.toSystemPrompt(), withNull.toSystemPrompt(),
                "authorDirectiveSection=null 不得改变 prompt");
        assertEquals(base.toSystemPrompt(), withBlank.toSystemPrompt(),
                "authorDirectiveSection=空白 不得改变 prompt");
        assertFalse(base.toSystemPrompt().contains("【作者要求】"));
    }

    @Test
    void 有要求时在原旨后输出且正文只出现一次() {
        String section = "- [D-1 rev2 HARD 全书] 每章至少一场雨夜对话\n"
                + "- [D-2 rev1 ONCE 第3-5章] 提前埋旧发夹线索";
        WritingContext ctx = contextWithPremise();
        ctx.setAuthorDirectiveSection(section);
        String prompt = ctx.toSystemPrompt();

        int directiveIdx = prompt.indexOf("【作者要求】");
        int premiseIdx = prompt.indexOf("【故事原旨");
        assertTrue(directiveIdx >= 0, "应输出【作者要求】节");
        assertTrue(premiseIdx >= 0 && directiveIdx > premiseIdx, "【作者要求】在原旨之后");
        assertEquals(1, countOccurrences(prompt, "雨夜对话"), "正文只出现一次");
        assertTrue(prompt.contains("D-1 rev2"), "每项含 directiveId/revision");
        assertTrue(prompt.contains("HARD") && prompt.contains("全书"), "kind 与 from-to 范围在节中");
        // 原旨不丢失
        assertTrue(prompt.contains("原旨：账册背后的血案。"));
    }

    @Test
    void 稳定排序_相同输入输出逐字相同() {
        String section = "- [D-1 rev2 HARD 全书] 要求甲\n- [D-2 rev1 ONCE 第3-5章] 要求乙";
        WritingContext a = contextWithPremise();
        a.setAuthorDirectiveSection(section);
        WritingContext b = contextWithPremise();
        b.setAuthorDirectiveSection("- [D-1 rev2 HARD 全书] 要求甲\n- [D-2 rev1 ONCE 第3-5章] 要求乙");
        assertEquals(a.toSystemPrompt(), b.toSystemPrompt(), "同输入输出逐字相同");
    }

    private int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
