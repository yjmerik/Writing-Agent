package com.writing.novel.agent;

import com.writing.novel.agent.util.ExcerptPolicy;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.persistence.sqlite.SqliteStoryCanon;
import com.writing.novel.persistence.sqlite.StoryFactRepository;
import com.writing.novel.spi.StoryCanon;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-117: 抽取输入策略统一——FactHarvester/EntityExtractor/ConsistencyGuard
 * 共享统一窗口（≤12000 字）+ 所见范围声明；后半章事实不再系统性丢失。
 */
class ExcerptPolicyTest {

    @Test
    void 覆盖窗口_短章原文_长章封顶声明() {
        String shortText = "短章正文";
        assertEquals(shortText, ExcerptPolicy.coverage(shortText));
        assertEquals("", ExcerptPolicy.coverage(null));

        String medium = "中".repeat(9000);   // 4000 < len <= 12000：全覆盖 + 分段说明
        String withNote = ExcerptPolicy.coveredWithNote(medium, "本章正文");
        assertTrue(withNote.contains("以上为全部所见分段"), "超 4000 字的全覆盖应说明分段");
        assertEquals(medium.length() + withNote.indexOf(medium.substring(0, 5)),
                withNote.indexOf(medium.substring(0, 5)) + medium.length(), "中章全覆盖");

        String huge = "长".repeat(20000);    // > 12000：封顶 + 未提供声明
        String covered = ExcerptPolicy.coveredWithNote(huge, "本章正文");
        assertTrue(covered.startsWith("【范围声明】"), "超窗必须先声明");
        assertTrue(covered.contains("不得对其下任何结论"));
        assertTrue(covered.contains("长".repeat(100)));
        // 覆盖窗口恰为 12000 字（声明文本不含「长」）
        assertEquals(12000, covered.chars().filter(c -> c == '长').count(), "覆盖窗口封顶 12000 字");
    }

    @TempDir
    Path tempDir;
    private StoryCanon canon;
    private String pid;
    private final AtomicReference<String> lastSystemPrompt = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("excerpt.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        var repository = new SqliteNovelRepository(cp);
        var project = new com.writing.novel.domain.Project("抽取统一", "构思", "wuxia");
        repository.saveProject(project);
        pid = project.getId();
        canon = new SqliteStoryCanon(new StoryFactRepository(cp));
    }

    @Test
    void 后半章事实不再丢失_范围声明入prompt() {
        // 9000 字章节：关键事实只在第 ~8000 字处（旧 4000 截断必然丢失）
        StringBuilder content = new StringBuilder();
        while (content.length() < 7900) content.append("平淡的过渡叙述文字。");
        content.append("林凡在藏经阁深处取得了寒霜古剑。");
        while (content.length() < 9000) content.append("收束文字。");

        LlmGateway gateway = new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest req) {
                lastSystemPrompt.set(req.getSystemPrompt());
                return new LlmResponse(
                        "[{\"type\":\"OBJECT_STATE\",\"subject\":\"林凡\",\"assertion\":\"取得寒霜古剑\"}]",
                        10, 100);
            }
            @Override public void streamComplete(LlmRequest req, StreamHandler h) {}
        };
        var agent = new FactHarvesterAgent(gateway, canon, TestPromptHelper.create());
        var chapter = new com.writing.novel.domain.Chapter(pid, 1, "第一章");
        chapter.setContent(content.toString());
        var result = agent.harvest(pid, chapter);

        assertTrue(result.added() >= 1, "第 8000 字处的事实应被收割（旧 4000 窗口丢失）");
        String prompt = lastSystemPrompt.get();
        assertTrue(prompt.contains("寒霜古剑"), "统一窗口必须覆盖后半章");
        assertTrue(prompt.contains("范围声明") || prompt.contains("全部所见分段"),
                "超 4000 字必须声明所见范围: " + prompt.substring(0, 80));
        var saved = canon.recallBySubject(pid, "林凡");
        assertTrue(saved.stream().anyMatch(f -> f.getAssertion().contains("寒霜古剑")));
    }
}
