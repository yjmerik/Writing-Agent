package com.writing.novel.orchestrator;

import com.writing.novel.domain.Checkpoint;
import com.writing.novel.domain.ChapterSynopsis;
import com.writing.novel.domain.Character;
import com.writing.novel.domain.Outline;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.Volume;
import com.writing.novel.spi.NovelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NCS-090（第二轮旅程 A-1）：AFTER_INITIAL_SETUP 的「修改意见重写」必须覆盖人物层——
 * 此前 reviseInitialSetup 只重写世界观条目，作者"为章纲新配角补人物卡/处置零出场卡"
 * 的反馈被静默丢弃（旅程实测 8 个章纲关键配角无卡且修订前后人物集合不变）。
 * 设计：确定性规则 + CharacterDesignerAgent——
 * 1) 卷梗概「新配角：」约定提取候选名 → 无卡即补卡（姓名强制为候选名）；
 * 2) 零出场的既有卡不静默删除，但在修订结果摘要中点名提示（处置留给作者）；
 * 3) 无人物诉求时不触发人物层调用。
 */
class InitialSetupRevisionCharacterTest {

    @TempDir Path tempDir;
    private NovelOrchestrator orchestrator;
    private NovelRepository repo;
    private String pid;
    private Checkpoint checkpoint;

    @BeforeEach
    void setUp() {
        com.writing.novel.persistence.sqlite.ConnectionProvider cp =
                new com.writing.novel.persistence.sqlite.ConnectionProvider(
                        tempDir.resolve("char-sync.db").toString());
        new com.writing.novel.persistence.sqlite.SqliteSchemaInitializer(cp).initialize();
        repo = new com.writing.novel.persistence.sqlite.SqliteNovelRepository(cp);

        com.writing.novel.llm.LlmGateway gateway = new com.writing.novel.llm.LlmGateway() {
            @Override
            public com.writing.novel.llm.LlmResponse complete(com.writing.novel.llm.LlmRequest req) {
                return switch (req.getTaskType()) {
                    case WORLDBUILDING -> new com.writing.novel.llm.LlmResponse(
                            "【地理】河伯渡：三水交汇渡口。\n【历史】沉船封口案：前朝旧案。", 50, 100);
                    case CHARACTER_DESIGN -> new com.writing.novel.llm.LlmResponse(
                            "【姓名】张三\n【角色定位】漕帮少女（对手转盟友）\n【性格】飒爽\n【能力】水性刀法", 50, 100);
                    default -> new com.writing.novel.llm.LlmResponse("ok", 1, 1);
                };
            }
            @Override
            public void streamComplete(com.writing.novel.llm.LlmRequest req,
                                       com.writing.novel.llm.client.StreamHandler h) {}
        };
        var promptSvc = com.writing.novel.agent.TestPromptHelper.create();
        com.writing.novel.spi.EmbeddingProvider emb = new com.writing.novel.spi.EmbeddingProvider() {
            @Override public float[] embed(String text) { return new float[]{1.0f}; }
            @Override public int dimension() { return 1; }
        };
        var store = new com.writing.novel.persistence.embedding.EmbeddedVectorStore(
                tempDir.resolve("char-sync.idx").toString());
        var kbSvc = new com.writing.novel.memory.KnowledgeBaseService(repo,
                new com.writing.novel.memory.VectorRetrievalService(store, emb));

        Project p = new Project("人物同步", "运河武侠", "武侠");
        repo.saveProject(p);
        pid = p.getId();

        Outline outline = new Outline(pid, "总纲：人物同步验证");
        Volume volume = new Volume(pid, 1, "卷一",
                "卷梗概：核心冲突：沉钟禁忌。新配角：柳拂水(对手转盟友/暗恋)、顾星桥(盟友/利用)。");
        volume.addSynopsis(new ChapterSynopsis(pid, 1, "捞钟不捞财",
                "暗潭测流→铁网挂钟。人物：沈铁衣、赵老栓。目的：引出钟与铜尺。"));
        volume.addSynopsis(new ChapterSynopsis(pid, 2, "师父绳结",
                "赵老栓死→绳结刻默。人物：沈铁衣、马九刀。目的：埋师门疑云。"));
        outline.addVolume(volume);
        repo.saveOutline(outline);

        // 既有卡：沈铁衣在章纲出场；蒲慎川零出场
        repo.saveCharacter(new Character(pid, "沈铁衣", "主角"));
        repo.saveCharacter(new Character(pid, "蒲慎川", "配角"));

        checkpoint = new Checkpoint(pid, Checkpoint.Type.AFTER_INITIAL_SETUP, "初始设定已生成，待审核");
        repo.saveCheckpoint(checkpoint);

        // 构造器 36 参：repo, projectService, worldBuilder, characterDesigner, 其余 32 null
        orchestrator = new NovelOrchestrator(repo,
                new com.writing.novel.orchestrator.ProjectService(repo),
                new com.writing.novel.agent.WorldBuilderAgent(gateway, kbSvc, promptSvc),
                new com.writing.novel.agent.CharacterDesignerAgent(gateway, kbSvc, promptSvc),
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    @Test
    void 修改意见重写为章纲新配角补人物卡并点名零出场卡() {
        int before = repo.findCharactersByProject(pid).size();

        NovelOrchestrator.CheckpointRevisionResult result = orchestrator.reviseCheckpoint(
                pid, checkpoint, "为章纲新配角柳拂水、顾星桥补人物卡（性格/动机与章纲出场一致）");

        var names = repo.findCharactersByProject(pid).stream().map(Character::getName).toList();
        assertTrue(names.contains("柳拂水"), "新配角柳拂水应有卡: " + names);
        assertTrue(names.contains("顾星桥"), "新配角顾星桥应有卡: " + names);
        assertEquals(before + 2, repo.findCharactersByProject(pid).size(),
                "恰好新增两张卡");
        assertTrue(names.contains("蒲慎川"), "零出场卡不静默删除");
        Character liu = repo.findCharactersByProject(pid).stream()
                .filter(c -> c.getName().equals("柳拂水")).findFirst().orElseThrow();
        assertNotNull(liu.getRole());
        assertTrue(result.afterSummary().contains("蒲慎川") || result.afterSummary().contains("零出场"),
                "修订结果应点名零出场卡: " + result.afterSummary());
    }

    @Test
    void 无人物诉求时不新增人物卡() {
        int before = repo.findCharactersByProject(pid).size();
        orchestrator.reviseCheckpoint(pid, checkpoint, "世界观增加一段河工号子的设定");
        assertEquals(before, repo.findCharactersByProject(pid).size(),
                "无补卡诉求时不新增人物卡");
    }
}
