package com.writing.novel.service;

import com.writing.novel.domain.KnowledgeEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ec-01: 时代锚点推导测试。
 */
class EraAnchorTest {

    private KnowledgeEntry entry(String type, String name, String content) {
        KnowledgeEntry e = new KnowledgeEntry("p1", type, name, content);
        return e;
    }

    @Test
    void 明代锚点从宝钞与年号推导() {
        var entries = List.of(
                entry("ECONOMY", "大明宝钞", "朝廷发行大明宝钞，洪武年间通行天下。"),
                entry("FACTION", "锦衣卫", "皇帝亲军锦衣卫巡查百官。"));
        String anchor = EraAnchorService.deriveEraAnchor(entries, "落魄账房学徒的故事");
        assertEquals("明代", anchor);
    }

    @Test
    void 清代年号推导() {
        var entries = List.of(entry("HISTORY", "近代", "道光年间鸦片渐入，国库空虚。"));
        assertEquals("清代", EraAnchorService.deriveEraAnchor(entries, null));
    }

    @Test
    void 多朝代冲突标注() {
        var entries = List.of(
                entry("ECONOMY", "宝钞", "大明宝钞通行。"),
                entry("CULTURE", "典籍", "道光年间刊印《千字文》。"));
        String anchor = EraAnchorService.deriveEraAnchor(entries, null);
        assertTrue(anchor.startsWith("⚠️ 时代冲突"), "应标注冲突: " + anchor);
        assertTrue(anchor.contains("明代"), anchor);
        assertTrue(anchor.contains("清代"), anchor);
    }

    @Test
    void 架空修仙世界不推导历史锚点() {
        var entries = List.of(entry("POWER_SYSTEM", "灵气", "修仙界以灵气修炼，架空世界。"));
        assertEquals("", EraAnchorService.deriveEraAnchor(entries, "少年在修仙界成长"));
    }

    @Test
    void 无时代信号返回空() {
        var entries = List.of(entry("GEOGRAPHY", "玄黄大陆", "一片广袤大陆。"));
        assertEquals("", EraAnchorService.deriveEraAnchor(entries, "少年冒险"));
    }

    @Test
    void 从concept也能推导() {
        assertEquals("唐代", EraAnchorService.deriveEraAnchor(null, "大唐贞观年间，长安城内"));
    }
}
