package com.writing.novel.orchestrator;

import com.writing.novel.domain.Outline;
import com.writing.novel.domain.Project;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * mc-p3-01 回归：分层大纲 volume-outline 解析健壮性。
 * 复现：LLM 输出超长被截断（completion 触顶 8192）时，第N章行未完整 → 解析 0 章 → 写作循环空转。
 */
class LayeredOutlineParsingTest {

    @TempDir
    Path tempDir;
    private SqliteNovelRepository repo;
    private NovelOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("layered.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        orchestrator = new NovelOrchestrator(repo, new ProjectService(repo),
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /** 真实 volume-outline 模板要求的格式（来自 volume-outline.st） */
    private String realisticVolumeText() {
        StringBuilder sb = new StringBuilder();
        sb.append("【总纲】卷一（确认卷名）\n\n");
        for (int i = 1; i <= 8; i++) {
            sb.append("第").append(i).append("章 章节").append(i)
              .append("：场景节拍：开场→推进→冲突→收尾。爽点：本章爽感。人物：沈砚、林照夜。")
              .append("目的：推进主线。张力：7。伏笔：埋设旧书店老板身份。\n");
        }
        return sb.toString();
    }

    /** 模拟 LLM 输出被 maxTokens 截断——最后一行不完整 */
    private String truncatedVolumeText() {
        String full = realisticVolumeText();
        // 砍掉最后 40 个字符 → 最后一章行不完整
        return full.substring(0, full.length() - 40);
    }

    private int invokeParse(Outline outline, String text, String projectId, int volNum, int startChapter) throws Exception {
        Method m = NovelOrchestrator.class.getDeclaredMethod(
                "parseAndAppendVolume", Outline.class, String.class, String.class, int.class, int.class);
        m.setAccessible(true);
        return (int) m.invoke(orchestrator, outline, text, projectId, volNum, startChapter);
    }

    @Test
    void 真实格式解析出全部章节() throws Exception {
        Outline outline = new Outline("p1", "总纲");
        int parsed = invokeParse(outline, realisticVolumeText(), "p1", 1, 1);
        assertEquals(8, parsed, "真实 volume-outline 格式应解析出 8 章");
        assertEquals(8, outline.getVolumes().get(0).getSynopses().size());
    }

    @Test
    void 截断输出至少解析出完整章节() throws Exception {
        Outline outline = new Outline("p1", "总纲");
        int parsed = invokeParse(outline, truncatedVolumeText(), "p1", 1, 1);
        assertTrue(parsed >= 7, "截断输出也应解析出至少 7 个完整章节，实际 " + parsed);
    }

    @Test
    void 空输出返回0章() throws Exception {
        Outline outline = new Outline("p1", "总纲");
        int parsed = invokeParse(outline, "", "p1", 1, 1);
        assertEquals(0, parsed);
    }

    @Test
    void 解析0章时占位兜底生成卷() throws Exception {
        Project p = new Project("占位", "测试", "悬疑");
        repo.saveProject(p);
        Outline outline = new Outline(p.getId(), "总纲：雨夜账册的传说。");
        repo.saveOutline(outline);

        // 解析 0 章 → 占位生成该卷章节（volumeNumber=1, ch 1-8），写作循环不会空转
        Method m = NovelOrchestrator.class.getDeclaredMethod(
                "createPlaceholderVolume", Outline.class, String.class, int.class, int.class);
        m.setAccessible(true);
        int created = (int) m.invoke(orchestrator, outline, p.getId(), 1, 8);

        assertEquals(8, created, "占位兜底应生成 8 章");
        assertEquals(8, outline.getVolumes().get(0).getSynopses().size());
        assertFalse(outline.getVolumes().get(0).getSynopses().get(0).getSynopsis().isBlank());
    }
}
