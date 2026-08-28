package com.writing.novel.agent;

import com.writing.novel.domain.Character;
import com.writing.novel.domain.Chapter;
import com.writing.novel.llm.*;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.llm.prompt.PromptTemplateService;
import com.writing.novel.memory.WritingContext;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.persistence.sqlite.StoryFactRepository;
import com.writing.novel.service.ArcScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * p19-03/01: 角色生死状态链。
 * 原状：Character 无死亡/退场状态——死亡只记真典但 cast 匹配失效，向量检索按活人召回，
 * ArcScheduler 仍调度死人出场——第 300 章「死人复活」是必然事故。
 */
class CharacterStatusTest {

    @TempDir
    Path tempDir;
    private SqliteNovelRepository repo;
    private StoryFactRepository factRepo;
    private FactHarvesterAgent harvester;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("status.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        factRepo = new StoryFactRepository(cp);
        harvester = new FactHarvesterAgent(new StubGateway("[]"), factRepo, TestPromptHelper.create());
        harvester.setNovelRepository(repo);
    }

    @Test
    void 收割死亡事实自动置位角色DECEASED与deathChapter() {
        Character victim = new Character("proj-s", "叶秋", "配角");
        repo.saveCharacter(victim);

        harvester = new FactHarvesterAgent(new StubGateway("""
                [{"type":"CHARACTER_STATE","subject":"叶秋","assertion":"第42章为掩护众人断后，身中数刀气绝身亡"}]
                """), factRepo, TestPromptHelper.create());
        harvester.setNovelRepository(repo);
        Chapter ch = new Chapter("proj-s", 42, "第42章");
        ch.setContent("叶秋为掩护众人断后，身中数刀，倒在血泊中气绝身亡。");
        harvester.harvest("proj-s", ch);

        Character saved = repo.findCharactersByProject("proj-s").stream()
                .filter(c -> c.getName().equals("叶秋")).findFirst().orElseThrow();
        assertEquals(Character.Status.DECEASED, saved.getStatus(), "死亡事实应自动置位 DECEASED");
        assertEquals(42, saved.getDeathChapter(), "deathChapter 应记录死亡章");
    }

    @Test
    void 死人在出场人物段标注已故_ArcScheduler不再调度() {
        Character ghost = new Character("proj-t", "老掌门", "配角");
        ghost.setStatus(Character.Status.DECEASED);
        ghost.setDeathChapter(30);
        repo.saveCharacter(ghost);

        // WritingContext 注入标注
        WritingContext ctx = new WritingContext();
        ctx.setRelevantCharacters(java.util.List.of(ghost));
        String prompt = ctx.toSystemPrompt();
        assertTrue(prompt.contains("已故"), "出场人物段应标注已故");
        assertTrue(prompt.contains("第30章"), "应标注殁于第几章");

        // ArcScheduler 冷场调度跳过死人
        ArcScheduler scheduler = new ArcScheduler(repo);
        String suggestion = scheduler.scheduleAppearance("proj-t", 60);
        assertFalse(suggestion.contains("老掌门"), "ArcScheduler 不应建议死人出场: " + suggestion);
    }

    @Test
    void 退场状态同样标注() {
        Character gone = new Character("proj-d", "游方僧", "配角");
        gone.setStatus(Character.Status.DEPARTED);
        repo.saveCharacter(gone);

        WritingContext ctx = new WritingContext();
        ctx.setRelevantCharacters(java.util.List.of(gone));
        assertTrue(ctx.toSystemPrompt().contains("已退场"));
    }

    private static class StubGateway implements LlmGateway {
        private final String content;
        StubGateway(String content) { this.content = content; }
        @Override
        public LlmResponse complete(LlmRequest request) {
            return new LlmResponse(content, 5, 2);
        }
        @Override
        public void streamComplete(LlmRequest request, StreamHandler handler) {
            throw new UnsupportedOperationException();
        }
    }
}
