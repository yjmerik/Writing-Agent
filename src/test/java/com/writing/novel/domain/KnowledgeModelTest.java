package com.writing.novel.domain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeModelTest {

    @Test
    void 世界观由多个知识条目组成() {
        Worldbuilding wb = new Worldbuilding("proj-1");
        wb.addEntry(new KnowledgeEntry("proj-1", "GEOGRAPHY", "玄黄大陆",
                "故事发生的主大陆，分为东玄西黄两域。"));
        wb.addEntry(new KnowledgeEntry("proj-1", "POWER_SYSTEM", "灵气体系",
                "修炼者吸收天地灵气淬炼己身，境界分九重。"));
        assertEquals(2, wb.getEntries().size());
        assertEquals("玄黄大陆", wb.getEntries().get(0).getName());
    }

    @Test
    void 人物卡包含核心属性() {
        Character c = new Character("proj-1", "张三", "主角");
        c.setPersonality("火爆、重情义");
        c.setAbility("金丹期剑修");
        c.setMotivation("为师门复仇");
        assertEquals("张三", c.getName());
        assertEquals("主角", c.getRole());
        assertEquals("火爆、重情义", c.getPersonality());
    }
}
