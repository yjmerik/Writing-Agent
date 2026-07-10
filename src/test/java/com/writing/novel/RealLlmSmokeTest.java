package com.writing.novel;

import com.writing.novel.domain.*;
import com.writing.novel.domain.Character;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.memory.*;
import com.writing.novel.orchestrator.NovelOrchestrator;
import com.writing.novel.orchestrator.ProjectService;
import com.writing.novel.agent.*;
import com.writing.novel.spi.NovelRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * 真实 LLM 端到端测试。
 * 不 mock LlmGateway，真正调用 GLM-5.2 / DeepSeek API 生成内容。
 * 验证系统的实际写作能力。
 *
 * 注意：此测试消耗真实 API 额度，运行较慢（几分钟）。
 * 仅在设置了 REAL_LLM=true 环境变量时运行。
 */

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实 LLM 端到端测试。
 * 不 mock LlmGateway，真正调用 GLM-5.2 / DeepSeek API 生成内容。
 * 验证系统的实际写作能力。
 *
 * 注意：此测试消耗真实 API 额度，运行较慢（几十秒）。
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "REAL_LLM", matches = "true")
@TestPropertySource(properties = {
        "persistence.embedding=cloud-api",
        "persistence.sqlite.db-path=./data/real-test.db",
        "persistence.vector-store.index-path=./data/real-test-vecs.idx"
})
class RealLlmSmokeTest {

    @Autowired NovelRepository repository;
    @Autowired LlmGateway llmGateway;
    @Autowired KnowledgeBaseService knowledgeBase;
    @Autowired SummaryService summaryService;
    @Autowired ContextAssembler contextAssembler;
    @Autowired VectorRetrievalService vectorRetrieval;

    @org.junit.jupiter.api.BeforeAll
    static void createDataDir() { new File("./data").mkdirs(); }

    @Test
    void 真实LLM生成世界观与人物() {
        Project p = new Project("剑骨天尊", "一个拥有上古剑骨的少年，在修仙世界中成长、复仇、证道的故事",
                "玄幻,热血,复仇");
        repository.saveProject(p);

        // 1. 世界观
        WorldBuilderAgent worldBuilder = new WorldBuilderAgent(llmGateway, knowledgeBase);
        Worldbuilding wb = worldBuilder.build(p.getId(), p.getConcept(), p.getTags());
        System.out.println("=== 世界观条目数: " + wb.getEntries().size() + " ===");
        wb.getEntries().forEach(e -> System.out.println("  [" + e.getType() + "] " + e.getName()));
        assertFalse(wb.getEntries().isEmpty(), "应生成世界观条目");

        // 2. 人物
        CharacterDesignerAgent charDesigner = new CharacterDesignerAgent(llmGateway, knowledgeBase);
        Character mc = charDesigner.design(p.getId(), "男主角，剑修，复仇者", List.of());
        System.out.println("=== 主角: " + mc.getName() + " ===");
        System.out.println("  性格: " + mc.getPersonality());
        System.out.println("  能力: " + mc.getAbility());
        assertNotNull(mc.getName());
        assertNotNull(mc.getPersonality());

        // 3. 大纲
        PlotterAgent plotter = new PlotterAgent(llmGateway, repository);
        Outline outline = plotter.plan(p.getId(), p.getConcept(), "复仇与成长", 5);
        System.out.println("=== 总纲: " + outline.getPremise() + " ===");
        outline.getVolumes().forEach(v -> {
            System.out.println("  卷" + v.getVolumeIndex() + " " + v.getTitle()
                    + " (" + v.getSynopses().size() + "章)");
        });
        assertFalse(outline.getVolumes().isEmpty(), "应生成卷");

        System.out.println("=== 真实 LLM 生成测试通过 ===");
    }
}
