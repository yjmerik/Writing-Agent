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
    void 行内章节引用不截断解析() throws Exception {
        // 复现 mc-p3-01：伏笔文本含"预计第N章回收"等行内章号引用，
        // 旧正则 (?=第\d+章) 会被截断 → 8 章大纲误解析为 5 章、章节错乱
        String text = "【总纲】卷1\n\n"
            + "第1章 开局：开场冲突。爽点：反转。人物：沈砚。目的：建立主线。张力：6。伏笔：埋设甲（伏笔，预计第5章回收）；埋设乙（预计卷2回收）。\n"
            + "第2章 推进：开场→冲突。爽点：爽感。人物：楚蘅。目的：推进。张力：7。伏笔：埋设丙（预计第3章回收）。\n"
            + "第3章 高潮：对决。爽点：碾压。人物：邵孤舟。目的：转折。张力：8。伏笔：埋设丁。\n"
            + "第4章 收尾：收束。爽点：悬念。人物：范伯衡。目的：埋线。张力：5。伏笔：无。\n"
            + "第5章 转折：意外。爽点：惊变。人物：温不换。目的：升级。张力：9。伏笔：埋设戊（预计第7章回收）。\n"
            + "第6章 喘息：对话。爽点：互动。人物：沈砚。目的：刻画。张力：3。伏笔：无。\n"
            + "第7章 推进二：线索。爽点：发现。人物：许清让。目的：铺陈。张力：6。伏笔：埋设己。\n"
            + "第8章 卷末：引爆。爽点：决战。人物：全员。目的：收束卷。张力：10。伏笔：回收甲乙丙。\n";
        Outline outline = new Outline("p1", "总纲");
        int parsed = invokeParse(outline, text, "p1", 1, 1);
        assertEquals(8, parsed, "行内'预计第N章回收'不应截断解析，实际 " + parsed);
        // 验证各章都解析出正确的标题
        var synopses = outline.getVolumes().get(0).getSynopses();
        assertEquals(8, synopses.size());
        assertTrue(synopses.stream().anyMatch(s -> s.getChapterIndex() == 3), "第3章不应丢失");
        assertTrue(synopses.stream().anyMatch(s -> s.getChapterIndex() == 5 && s.getTitle().contains("转折")),
                "第5章应解析出正确标题");
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
