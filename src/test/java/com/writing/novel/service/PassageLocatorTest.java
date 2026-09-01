package com.writing.novel.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PassageLocatorTest {

    @Test
    void 前端跳过空行后的段号应对上原文含空行的段落() {
        String content = String.join("\n",
                "第一段。",
                "",
                "第二段。",
                "",
                "雨越下越大。他的靛蓝棉袍很快湿透。",
                "",
                "第四段。");
        int frontendIndex = 2;
        String selected = "雨越下越大。他的靛蓝棉袍很快湿透。";

        int resolved = PassageLocator.resolve(content, frontendIndex, selected);

        String[] raw = content.split("\n", -1);
        assertEquals(4, resolved);
        assertTrue(raw[resolved].contains("雨越下越大"));
        assertNotEquals(frontendIndex, resolved);
    }

    @Test
    void 索引对不上时按选中文本定位_不抛错() {
        String content = "甲\n\n乙\n\n丙";
        int resolved = PassageLocator.resolve(content, 14, "丙");
        assertEquals(4, resolved);
    }

    @Test
    void 文本也对不上才失败() {
        assertThrows(IllegalArgumentException.class,
                () -> PassageLocator.resolve("甲\n乙", 0, "完全不存在的句子"));
    }
}
