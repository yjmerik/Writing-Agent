package com.writing.novel.domain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProjectTest {

    @Test
    void 新建项目默认为构思状态且Solo模式() {
        Project p = new Project("测试小说", "一个测试简介", "玄幻,热血");
        assertEquals(NovelState.CONCEIVING, p.getState());
        assertEquals(ProjectMode.SOLO, p.getMode());
        assertEquals("测试小说", p.getTitle());
        assertEquals("一个测试简介", p.getConcept());
    }

    @Test
    void 项目支持切换模式() {
        Project p = new Project("t", "c", "tag");
        p.setMode(ProjectMode.INTERACTIVE);
        assertEquals(ProjectMode.INTERACTIVE, p.getMode());
    }

    @Test
    void 项目支持状态转移() {
        Project p = new Project("t", "c", "tag");
        p.setState(NovelState.PLANNING);
        assertEquals(NovelState.PLANNING, p.getState());
    }
}
