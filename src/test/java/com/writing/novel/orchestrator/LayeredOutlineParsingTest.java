package com.writing.novel.orchestrator;

import com.writing.novel.domain.Outline;
import com.writing.novel.domain.Project;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.telemetry.EventLog;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

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

    /** p32-03: 计划章号区间校验事件捕获 */
    private static final List<Map<String, Object>> RANGE_EVENTS = new CopyOnWriteArrayList<>();
    private static final EventLog.Listener RANGE_LISTENER = record -> {
        if ("PLAN_VOLUME_RANGE_REJECTED".equals(record.get("event"))) RANGE_EVENTS.add(record);
    };

    @BeforeAll
    static void redirectEventLog() {
        new File("./data").mkdirs();
        System.setProperty(EventLog.PATH_PROPERTY, "./data/test-layered-parsing-events.jsonl");
        EventLog.subscribe(RANGE_LISTENER);
    }

    @AfterAll
    static void restoreEventLog() {
        EventLog.unsubscribe(RANGE_LISTENER);
        System.clearProperty(EventLog.PATH_PROPERTY);
    }

    @BeforeEach
    @AfterEach
    void clearRangeEvents() {
        RANGE_EVENTS.clear();
    }

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("layered.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        orchestrator = new NovelOrchestrator(repo, new ProjectService(repo),
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null);
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
        return invokeParse(outline, text, projectId, volNum, startChapter, startChapter + 7);
    }

    /** p32-03: 解析入口带显式区间（start..end），区间外章号拒绝入库 */
    private int invokeParse(Outline outline, String text, String projectId, int volNum,
                            int startChapter, int endChapter) throws Exception {
        Method m = NovelOrchestrator.class.getDeclaredMethod(
                "parseAndAppendVolume", Outline.class, String.class, String.class,
                int.class, int.class, int.class);
        m.setAccessible(true);
        return (int) m.invoke(orchestrator, outline, text, projectId, volNum, startChapter, endChapter);
    }

    /** 报告 #1 复现：请求 1-8 卷，LLM 照抄总纲中另一卷范围输出 41-48 */
    private String wrongRangeVolumeText(int from) {
        StringBuilder sb = new StringBuilder();
        sb.append("【总纲】卷1（重规划）\n\n");
        for (int i = from; i < from + 8; i++) {
            sb.append("第").append(i).append("章 错误章节").append(i)
              .append("：场景节拍：开场→推进→冲突→收尾。爽点：爽感。人物：沈砚。")
              .append("目的：推进。张力：7。伏笔：无。\n");
        }
        return sb.toString();
    }

    @Test
    void 章号全在区间外时拒绝入库并发出计划校验事件() throws Exception {
        Outline outline = new Outline("p1", "总纲");
        int parsed = invokeParse(outline, wrongRangeVolumeText(41), "p1", 1, 1, 8);
        assertEquals(0, parsed, "41-48 全部落在请求区间 1-8 之外，应解析 0 章走占位兜底");
        assertTrue(outline.getVolumes().get(0).getSynopses().isEmpty(),
                "区间外章号不得入库: " + outline.getVolumes().get(0).getSynopses());
        assertTrue(RANGE_EVENTS.stream().anyMatch(e -> "p1".equals(e.get("projectId"))
                        && Integer.valueOf(41).equals(((java.util.List<Integer>) e.get("rejectedChapters")).get(0))),
                "应发出 PLAN_VOLUME_RANGE_REJECTED 事件，实际: " + RANGE_EVENTS);
    }

    @Test
    void 混合章号仅区间内条目入库并记录被拒章号() throws Exception {
        String text = "第1章 甲：节拍。\n第2章 乙：节拍。\n第41章 丙：节拍。\n第9章 丁：节拍。\n";
        Outline outline = new Outline("p1", "总纲");
        int parsed = invokeParse(outline, text, "p1", 1, 1, 8);
        assertEquals(2, parsed, "只应接受区间 1-8 内的第1、2章");
        var synopses = outline.getVolumes().get(0).getSynopses();
        assertEquals(java.util.Set.of(1, 2),
                synopses.stream().map(com.writing.novel.domain.ChapterSynopsis::getChapterIndex)
                        .collect(java.util.stream.Collectors.toSet()));
        assertTrue(RANGE_EVENTS.stream().anyMatch(e ->
                        ((java.util.List<Integer>) e.get("rejectedChapters")).contains(41)
                                && ((java.util.List<Integer>) e.get("rejectedChapters")).contains(9)),
                "事件应记录被拒章号 41 与 9，实际: " + RANGE_EVENTS);
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
    void markdown加粗章节行也能解析() throws Exception {
        // 复现：planVolume 输出 **第N章 标题** + 换行 + **场景节拍：** 的格式
        String text = "【总纲】卷2 积阴堂来客\n\n"
            + "**第9章 灰烬里的官靴**\n**场景节拍：** 乔二娘心神不宁，雨夜查访。\n**爽点：** 账册侦查。\n**人物：** 沈砚舟、裴照临\n**目的：** 引入大理寺。\n**张力：** 6\n**伏笔：** 埋设青蚨钱挂坠（重要，预计卷9回收）。\n\n"
            + "**第10章 雨吏的脚印**\n**场景节拍：** 雨夜追踪。\n**爽点：** 反杀。\n**人物：** 裴照临\n**目的：** 推进。\n**张力：** 7\n**伏笔：** 无。\n\n"
            + "**第11章 账册的裂缝**\n**场景节拍：** 账册异变。\n**爽点：** 玄奇。\n**人物：** 沈砚舟\n**目的：** 揭示设定。\n**张力：** 8\n**伏笔：** 埋设裂缝原因。\n";
        Outline outline = new Outline("p1", "总纲");
        // p32-03: 卷2 真实调用 start=9（第9-16章区间）
        int parsed = invokeParse(outline, text, "p1", 1, 9);
        assertEquals(3, parsed, "markdown 加粗章节行应解析 3 章，实际 " + parsed);
        var synopses = outline.getVolumes().get(0).getSynopses();
        assertEquals(3, synopses.size());
        assertTrue(synopses.stream().anyMatch(s -> s.getChapterIndex() == 9 && s.getTitle().contains("灰烬")),
                "第9章标题应解析干净，实际: " + synopses.stream().map(s -> s.getChapterIndex() + ":" + s.getTitle()).toList());
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
