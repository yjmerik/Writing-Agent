package com.writing.novel.memory;

import com.writing.novel.domain.*;
import com.writing.novel.domain.Character;
import com.writing.novel.spi.NovelRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@TestPropertySource(properties = {
        "persistence.embedding=stub",
        "persistence.sqlite.db-path=./data/test-mem.db",
        "persistence.vector-store.index-path=./data/test-mem-vecs.idx"
})
class MemoryIntegrationTest {

    @Autowired KnowledgeBaseService knowledgeBase;
    @Autowired SummaryService summaryService;
    @Autowired ContextAssembler contextAssembler;
    @Autowired NovelRepository repository;

    @org.junit.jupiter.api.BeforeAll
    static void createDataDir() { new File("./data").mkdirs(); }

    @Test
    void 全链路_存设定_写章节_组装上下文() {
        Project p = new Project("记忆集成测试", "剑修故事", "玄幻");
        repository.saveProject(p);

        StyleProfile style = new StyleProfile(p.getId());
        style.setTone("热血");
        repository.saveStyleProfile(style);

        knowledgeBase.saveEntry(new KnowledgeEntry(p.getId(), "POWER_SYSTEM",
                "灵气体系", "修炼九重"));
        Character mc = new Character(p.getId(), "张三", "主角");
        mc.setPersonality("火爆");
        mc.setAbility("剑修");
        knowledgeBase.saveCharacter(mc);

        Chapter c1 = new Chapter(p.getId(), 1, "第一章");
        c1.setContent("张三踏入青云宗山门，剑意凛然。");
        repository.saveChapter(c1);
        summaryService.save(new Summary(p.getId(), 1, 1, "张三拜入青云宗。"));

        WritingContext ctx = contextAssembler.assemble(
                p.getId(), 2, "第二章 初次修炼",
                "张三开始修炼基础剑法", "3000 字");

        assertNotNull(ctx);
        assertTrue(ctx.getStyleSection().contains("热血"));
        assertTrue(ctx.getWorldbuildingSection().contains("灵气体系"));
        assertTrue(ctx.getRecentSummarySection().contains("青云宗"));
        assertTrue(ctx.getRecentTextSection().contains("剑意凛然"));
        assertFalse(ctx.getRelevantCharacters().isEmpty());
        assertTrue(ctx.getTaskSection().contains("修炼基础剑法"));

        String system = ctx.toSystemPrompt();
        String user = ctx.toUserPrompt();
        assertTrue(system.contains("张三"));
        assertTrue(user.contains("第二章"));
    }
}
