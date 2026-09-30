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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NCS-144（旅程三 F-2）+ NCS-154（review 整改）：框架审阅「修改意见」点名既有卡时，
 * 生成该卡的修订预览（逐字段 diff）等待作者确认——确认前库中零变动（NCS-144 实现曾
 * design 后直接落库、绕过作者批准制，NCS-154 修正）；未点名卡全程逐字不变。
 */
class ReviseNamedCharacterTest {

    @TempDir Path tempDir;
    private NovelOrchestrator orchestrator;
    private NovelRepository repo;
    private String pid;
    private Checkpoint checkpoint;
    private Character shen;
    private Character pu;

    @BeforeEach
    void setUp() {
        com.writing.novel.persistence.sqlite.ConnectionProvider cp =
                new com.writing.novel.persistence.sqlite.ConnectionProvider(
                        tempDir.resolve("char-revise.db").toString());
        new com.writing.novel.persistence.sqlite.SqliteSchemaInitializer(cp).initialize();
        repo = new com.writing.novel.persistence.sqlite.SqliteNovelRepository(cp);

        // CHARACTER_DESIGN 桩：按请求是否为"修订人物卡"返回不同内容，供断言区分
        com.writing.novel.llm.LlmGateway gateway = new com.writing.novel.llm.LlmGateway() {
            @Override
            public com.writing.novel.llm.LlmResponse complete(com.writing.novel.llm.LlmRequest req) {
                return switch (req.getTaskType()) {
                    case WORLDBUILDING -> new com.writing.novel.llm.LlmResponse(
                            "【地理】河伯渡：三水交汇渡口。\n【历史】沉船封口案：前朝旧案。", 50, 100);
                    case CHARACTER_DESIGN -> new com.writing.novel.llm.LlmResponse(
                            "【姓名】沈铁衣\n【角色定位】主角\n【性格】修订后的性格：寡言而烈\n【能力】听雨辨位", 50, 100);
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
                tempDir.resolve("char-revise.idx").toString());
        var kbSvc = new com.writing.novel.memory.KnowledgeBaseService(repo,
                new com.writing.novel.memory.VectorRetrievalService(store, emb));

        Project p = new Project("人物定向修订", "运河武侠", "武侠");
        repo.saveProject(p);
        pid = p.getId();

        Outline outline = new Outline(pid, "总纲：定向修订验证");
        Volume volume = new Volume(pid, 1, "卷一",
                "卷梗概：核心冲突：沉钟禁忌。新配角：柳拂水(对手转盟友)。");
        volume.addSynopsis(new ChapterSynopsis(pid, 1, "捞钟不捞财",
                "暗潭测流→铁网挂钟。人物：沈铁衣、赵老栓。目的：引出钟与铜尺。"));
        outline.addVolume(volume);
        repo.saveOutline(outline);

        shen = new Character(pid, "沈铁衣", "主角");
        shen.setPersonality("旧性格：守时如命");
        repo.saveCharacter(shen);
        pu = new Character(pid, "蒲慎川", "配角");
        pu.setPersonality("旧性格：谨慎过人");
        repo.saveCharacter(pu);

        checkpoint = new Checkpoint(pid, Checkpoint.Type.AFTER_INITIAL_SETUP, "初始设定已生成，待审核");
        repo.saveCheckpoint(checkpoint);

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
    void 点名既有卡生成待确认预览_确认前库中零变动() {
        var result = orchestrator.reviseCheckpoint(pid, checkpoint,
                "人物修订：沈铁衣去掉守时如命的强迫感，改为寡言而烈");

        // NCS-154 核心断言：修订不再自动落库
        Character inRepo = repo.findCharactersByProject(pid).stream()
                .filter(c -> c.getName().equals("沈铁衣")).findFirst().orElseThrow();
        assertEquals("旧性格：守时如命", inRepo.getPersonality(),
                "作者确认前，点名卡必须保持原值（批准制）");

        // 预览随修订结果返回，含逐字段 diff
        assertNotNull(result.characterRevisions(), "修订结果应携带待确认人物卡预览");
        assertEquals(1, result.characterRevisions().size());
        var card = result.characterRevisions().get(0);
        assertEquals(shen.getId(), card.characterId());
        assertTrue(card.changes().stream().anyMatch(ch ->
                        "personality".equals(ch.field()) && ch.after().contains("寡言而烈")),
                "diff 应含 personality 变更: " + card.changes());
        assertTrue(result.afterSummary().contains("待作者确认"), "摘要应说明待确认状态");
    }

    @Test
    void 确认后按原卡id落库() {
        orchestrator.reviseCheckpoint(pid, checkpoint,
                "人物修订：沈铁衣去掉守时如命的强迫感，改为寡言而烈");
        String previewId = orchestrator.characterRevisionPreviews().find(pid).orElseThrow().previewId();

        orchestrator.characterRevisionPreviews().confirm(pid, previewId);
        Character revised = repo.findCharactersByProject(pid).stream()
                .filter(c -> c.getName().equals("沈铁衣")).findFirst().orElseThrow();
        assertEquals(shen.getId(), revised.getId(), "必须原地修订（同 id），不得另立新卡");
        assertTrue(String.valueOf(revised.getPersonality()).contains("寡言而烈"),
                "确认后点名卡内容生效: " + revised.getPersonality());
        Character untouched = repo.findCharactersByProject(pid).stream()
                .filter(c -> c.getName().equals("蒲慎川")).findFirst().orElseThrow();
        assertEquals("旧性格：谨慎过人", untouched.getPersonality(), "未点名卡逐字不变");
        // 确认后预览清空；同候选再次修订无 diff，不再产生预览（幂等）
        assertTrue(orchestrator.characterRevisionPreviews().find(pid).isEmpty());
    }

    @Test
    void 放弃则库中不变且预览清空() {
        orchestrator.reviseCheckpoint(pid, checkpoint,
                "人物修订：沈铁衣去掉守时如命的强迫感，改为寡言而烈");
        String previewId = orchestrator.characterRevisionPreviews().find(pid).orElseThrow().previewId();

        orchestrator.characterRevisionPreviews().discard(pid, previewId);
        Character afterDiscard = repo.findCharactersByProject(pid).stream()
                .filter(c -> c.getName().equals("沈铁衣")).findFirst().orElseThrow();
        assertEquals("旧性格：守时如命", afterDiscard.getPersonality(),
                "放弃后点名卡保持原值");
        assertTrue(orchestrator.characterRevisionPreviews().find(pid).isEmpty(), "放弃后预览清空");
    }

    @Test
    void 未点名卡零变动_点名单独成立() {
        var result = orchestrator.reviseCheckpoint(pid, checkpoint,
                "人物修订：只改沈铁衣的性格，其余人物一律不动");
        var previewCards = result.characterRevisions() == null
                ? java.util.Collections.<com.writing.novel.service.CharacterRevisionPreviewService.CardRevision>emptyList()
                : result.characterRevisions();
        assertTrue(previewCards.stream().allMatch(c -> c.characterId().equals(shen.getId())),
                "预览只含点名卡");
        Character untouched = repo.findCharactersByProject(pid).stream()
                .filter(c -> c.getName().equals("蒲慎川")).findFirst().orElseThrow();
        assertEquals("旧性格：谨慎过人", untouched.getPersonality(), "未点名卡必须逐字不变");
    }

    @Test
    void 不点名任何既有卡时不触发修订调用() {
        var before = repo.findCharactersByProject(pid).stream()
                .map(c -> c.getName() + ":" + c.getPersonality()).toList();
        var result = orchestrator.reviseCheckpoint(pid, checkpoint, "世界观增加河工号子的设定");
        var after = repo.findCharactersByProject(pid).stream()
                .map(c -> c.getName() + ":" + c.getPersonality()).toList();
        assertEquals(before, after, "未点名时人物层零变动");
        assertNull(result.characterRevisions(), "未点名时无人物卡预览");
    }
}
