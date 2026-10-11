package com.writing.novel.agent;

import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.memory.InjectionManifest;
import com.writing.novel.memory.RelevanceGateway;
import com.writing.novel.domain.Character;
import com.writing.novel.domain.KnowledgeEntry;
import com.writing.novel.domain.Outline;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.StoryFact;
import com.writing.novel.domain.Volume;
import com.writing.novel.domain.ChapterSynopsis;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.InjectionManifestRepository;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.persistence.sqlite.SqliteStoryCanon;
import com.writing.novel.persistence.sqlite.StoryFactRepository;
import com.writing.novel.spi.NovelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-097: 检查与写作同源——
 * 1) 检查器真典段用带章节号的 relevantFacts（recency 生效，与写作口径一致）；
 * 2) 真典只注入一次（settings 侧不再重复）；
 * 3) 封闭式核对段：注入清单中的真典/伏笔进入检查 prompt；
 * 4) 重查与首查共用 CheckSettingsBuilder（同判据）。
 */
class ContinuityContextParityTest {

    @TempDir
    Path tempDir;
    private NovelRepository repository;
    private RelevanceGateway gateway;
    private InjectionManifestRepository manifestRepo;
    private String pid;
    private String factId;
    private com.writing.novel.persistence.sqlite.StoryFactRepository factRepo;
    private com.writing.novel.persistence.sqlite.SqliteStoryCanon canon;
    private final AtomicReference<String> lastUserMessage = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("parity.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repository = new SqliteNovelRepository(cp);
        manifestRepo = new InjectionManifestRepository(cp);
        factRepo = new StoryFactRepository(cp);
        canon = new SqliteStoryCanon(factRepo);
        gateway = new RelevanceGateway(repository, canon);

        Project p = new Project("同源测试", "构思", "wuxia");
        repository.saveProject(p);
        pid = p.getId();

        // 大纲：第 5 章 cast 含 林凡
        Outline outline = new Outline(pid, "总纲");
        Volume vol = new Volume(pid, 1, "第一卷", "卷概要");
        ChapterSynopsis syn = new ChapterSynopsis(pid, 5, "夜探", "林凡夜探城主府");
        syn.setCast("林凡,赵九");
        vol.getSynopses().add(syn);
        outline.getVolumes().add(vol);
        repository.saveOutline(outline);

        repository.saveCharacter(new Character(pid, "林凡", "主角"));
        repository.saveKnowledgeEntry(new KnowledgeEntry(pid, "POWER_SYSTEM", "内力", "内力分九重。"));

        // 两条同重要度事实：一条在第 4 章刚验证（recency 高），一条 30 章前验证
        StoryFact recent = new StoryFact(pid, StoryFact.FactType.CHARACTER_STATE, "林凡", "左手旧伤未愈", 3);
        recent.setLastValidatedChapter(4);
        recent.setImportance(3);
        factRepo.save(recent);
        factId = recent.getId();
        StoryFact stale = new StoryFact(pid, StoryFact.FactType.WORLD_RULE, "城主府", "城主府坐北朝南", 1);
        stale.setLastValidatedChapter(1);
        stale.setImportance(3);
        factRepo.save(stale);
    }

    private ContinuityCheckerAgent agent() {
        LlmGateway llm = new LlmGateway() {
            @Override
            public LlmResponse complete(LlmRequest req) {
                lastUserMessage.set(req.getUserMessage());
                return new LlmResponse("未发现连续性问题。", 10, 10);
            }
            @Override
            public void streamComplete(LlmRequest req, StreamHandler handler) {}
        };
        ContinuityCheckerAgent agent = new ContinuityCheckerAgent(llm, repository,
                TestPromptHelper.create(), gateway, null);
        agent.setManifestRepository(manifestRepo);
        agent.setStoryCanon(canon);
        return agent;
    }

    @Test
    void 真典段带章节号_近章验证事实排前() {
        agent().check(pid, 5, "正文", "设定：无");
        String msg = lastUserMessage.get();
        assertTrue(msg.contains("【故事真典"), "真典段应由检查器注入");
        int recentIdx = msg.indexOf("左手旧伤未愈");
        int staleIdx = msg.indexOf("坐北朝南");
        assertTrue(recentIdx >= 0, "近章验证事实应注入（带章节号的 recency 口径）");
        if (staleIdx >= 0) {
            assertTrue(recentIdx < staleIdx, "近章验证事实应排在陈旧事实之前");
        }
    }

    @Test
    void 真典只注入一次_settings侧无重复() {
        String settings = com.writing.novel.orchestrator.CheckSettingsBuilder.build(
                repository, gateway, pid, 5, java.util.List.of("林凡"));
        assertFalse(settings.contains("故事真典"), "settings 侧不得再注入真典（消除双通道）");
        assertTrue(settings.contains("林凡"), "settings 侧应含人物卡");
        assertTrue(settings.contains("内力"), "settings 侧应含知识条目");
    }

    @Test
    void 封闭式核对段_注入清单条目进入检查prompt() {
        InjectionManifest manifest = new InjectionManifest(pid, 5, 1, 16000);
        manifest.addEntry("bibleSection", InjectionManifest.Kind.FACT, factId,
                "林凡：左手旧伤未愈", 10);
        manifest.addEntry("relevantForeshadowings", InjectionManifest.Kind.FORESHADOW,
                "f-1", "断剑的来历", 5);
        manifestRepo.save(manifest);

        agent().check(pid, 5, "正文", "设定：无");
        String msg = lastUserMessage.get();
        assertTrue(msg.contains("封闭式核对"), "应包含封闭式核对段标题");
        assertTrue(msg.contains("[真典] 林凡：左手旧伤未愈"), "真典注入条目应逐项列出");
        assertTrue(msg.contains("[伏笔] 断剑的来历"), "伏笔注入条目应逐项列出");
    }

    @Test
    void cast解析_与写作侧同源() {
        var cast = com.writing.novel.orchestrator.CheckSettingsBuilder.castOf(repository, pid, 5);
        assertEquals(java.util.List.of("林凡", "赵九"), cast, "应从大纲解析本章 cast");
    }

    /** NCS-180: 典目修正后约束清单标注「已修正」并给出现行真典，检查不再把旧约束写作判为矛盾 */
    @org.junit.jupiter.api.Test
    void 典目修正后约束清单标注已修正并给现行值() {
        // 写作时清单快照 = 旧断言
        InjectionManifest manifest = new InjectionManifest(pid, 5, 1, 16000);
        manifest.addEntry("bibleSection", InjectionManifest.Kind.FACT, factId,
                "林凡：左手旧伤未愈", 10);
        manifestRepo.save(manifest);
        // 作者修正真典：旧目 SUPERSEDED → 新目 ACTIVE（断言变更）
        StoryFact patched = new StoryFact(pid, StoryFact.FactType.CHARACTER_STATE, "林凡",
                "左手伤已痊愈", 3);
        factRepo.supersede(factRepo.findById(factId).orElseThrow(), patched);

        agent().check(pid, 5, "正文提到左手旧伤未愈。", "设定：无");
        String msg = lastUserMessage.get();
        assertTrue(msg.contains("[真典·已修正]"), "修正条目应标注: " + msg);
        assertTrue(msg.contains("左手伤已痊愈"), "应给出现行真典");
        assertTrue(msg.contains("写作时旧约束"), "应保留旧约束对照");
        assertTrue(msg.contains("不得据此报告矛盾"), "应含门禁区分指令");
    }

    /** NCS-180: 典目废止后约束清单标注「已废止」 */
    @org.junit.jupiter.api.Test
    void 典目废止后约束清单标注已废止() {
        InjectionManifest manifest = new InjectionManifest(pid, 5, 1, 16000);
        manifest.addEntry("bibleSection", InjectionManifest.Kind.FACT, "nonexistent-fact-id",
                "林凡：不存在的旧约束", 10);
        manifestRepo.save(manifest);
        agent().check(pid, 5, "正文", "设定：无");
        String msg = lastUserMessage.get();
        assertTrue(msg.contains("[真典·已废止]"), "查无条目应标注废止: " + msg);
    }

    /** NCS-180: 未修正的条目保持原样（无标注） */
    @org.junit.jupiter.api.Test
    void 未修正条目保持原样() {
        InjectionManifest manifest = new InjectionManifest(pid, 5, 1, 16000);
        manifest.addEntry("bibleSection", InjectionManifest.Kind.FACT, factId,
                "林凡：左手旧伤未愈", 10);
        manifestRepo.save(manifest);
        agent().check(pid, 5, "正文", "设定：无");
        String msg = lastUserMessage.get();
        assertTrue(msg.contains("- [真典] 林凡：左手旧伤未愈"), "未修正条目原样呈现");
        assertFalse(msg.contains("[真典·已修正]"), "不得误标");
    }
}
