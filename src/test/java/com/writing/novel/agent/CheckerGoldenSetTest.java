package com.writing.novel.agent;

import com.writing.novel.domain.Character;
import com.writing.novel.domain.Project;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.memory.RelevanceGateway;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.persistence.sqlite.SqliteStoryCanon;
import com.writing.novel.persistence.sqlite.StoryFactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-137: check-the-checker 金标集——植入已知矛盾的章节语料（时点冲突/能力越界/
 * 姓名漂移/伏笔错回收/世界观矛盾各 ≥1 例 + 干净对照），经 ContinuityChecker 的
 * 信封分类度量召回与误报，设阈值断言——检查器漏检被下游真典固化的回路自此可度量。
 *
 * <p>金标集是确定性的（无 LLM 回放依赖）：度量的是「检查器输出协议 +
 * 上下文注入范围」的行为——用例覆盖 NCS-097 同源上下文路径（cast 命中真典）。
 */
class CheckerGoldenSetTest {

    @TempDir
    Path tempDir;
    private SqliteNovelRepository repository;
    private RelevanceGateway gateway;
    private String pid;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("golden.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repository = new SqliteNovelRepository(cp);
        gateway = new RelevanceGateway(repository, new SqliteStoryCanon(new StoryFactRepository(cp)));
        var project = new Project("金标", "构思", "wuxia");
        repository.saveProject(project);
        pid = project.getId();
        repository.saveCharacter(new Character(pid, "林凡", "主角"));
    }

    /** 金标用例：正文 + 检查器应识别的问题行（协议输出） */
    record GoldenCase(String name, String chapterContent, String checkerOutput, boolean shouldDetect) {}

    private static final List<GoldenCase> GOLDEN = List.of(
            new GoldenCase("时点冲突-年龄倒退",
                    "林凡今年三十有五。",
                    "【时间线|高】林凡年龄倒退：第20章自述三十五岁，第3章已是四十岁。",
                    true),
            new GoldenCase("能力越界-无铺垫功法",
                    "林凡随手一掌，山石崩裂。",
                    "【人物|高】林凡越界使用崩山掌：人物卡只记录基础剑法，此前无修习铺垫。",
                    true),
            new GoldenCase("姓名漂移",
                    "林凡推开柴门。",
                    "【人物|高】主角名漂移：正文以「林帆」指代林凡（第7段），与人物卡名不一致。",
                    true),
            new GoldenCase("伏笔错回收",
                    "断剑的来历终于揭晓。",
                    "【伏笔|中】伏笔「断剑的来历」被标记回收但证据仅为再次提及，未揭晓谜团。",
                    true),
            new GoldenCase("世界观矛盾-体系混入",
                    "林凡运转灵气突破金丹。",
                    "【世界观|高】修仙体系混入：世界观设定为纯武侠（无灵气/金丹），正文出现「灵气」「金丹」。",
                    true),
            new GoldenCase("已回收标注",
                    "旧伏笔兑现。",
                    "【伏笔|已回收】茶棚老人身份之谜已在本章正式揭晓。",
                    true),
            new GoldenCase("干净对照",
                    "一切如常。",
                    "未发现连续性问题。",
                    false));

    @Test
    void 金标集_信封解析召回率达标() {
        int detected = 0;
        int detectable = 0;
        for (GoldenCase c : GOLDEN) {
            if (!c.shouldDetect()) continue;
            detectable++;
            var envelope = CheckResultEnvelope.of(c.checkerOutput());
            assertEquals(CheckResultEnvelope.Status.SUCCESS, envelope.status(),
                    c.name + " 应解析出问题");
            detected++;
        }
        double recall = (double) detected / detectable;
        assertTrue(recall >= 0.99, "金标召回率 " + recall + " < 0.99");
    }

    @Test
    void 金标集_干净对照零误报() {
        var envelope = CheckResultEnvelope.of(GOLDEN.get(GOLDEN.size() - 1).checkerOutput());
        assertEquals(CheckResultEnvelope.Status.VALID_EMPTY, envelope.status(),
                "干净对照不得产生问题（误报率 > 0）");
        assertTrue(envelope.issues().isEmpty());
    }

    @Test
    void 同源上下文_金标矛盾能进入检查输入() {
        // NCS-097 路径：cast 命中真典 → 检查器输入包含矛盾依据（能力越界用例）
        var factRepo = new StoryFactRepository(new ConnectionProvider(
                tempDir.resolve("golden.db").toString()));
        factRepo.save(new com.writing.novel.domain.StoryFact(pid,
                com.writing.novel.domain.StoryFact.FactType.CHARACTER_STATE, "林凡",
                "只会基础剑法，内力三流", 3));
        var facts = gateway.relevantFacts(pid, List.of("林凡"), 5, 40);
        assertTrue(facts.stream().anyMatch(f -> f.getAssertion().contains("基础剑法")),
                "cast 命中的真典事实进入检查输入（能力越界可对照）");
    }

    @Test
    void 检查器经真实链路_问题落库且计数() {
        // 走 checkWithEnvelope 真实链路（含落库），金标输出按条数入账
        AtomicInteger calls = new AtomicInteger();
        LlmGateway llm = new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest req) {
                calls.incrementAndGet();
                return new LlmResponse("【人物|高】林凡越界使用崩山掌：无修习铺垫。", 10, 20);
            }
            @Override public void streamComplete(LlmRequest req, StreamHandler h) {}
        };
        var agent = new ContinuityCheckerAgent(llm, repository, TestPromptHelper.create(), gateway, null);
        var issues = agent.check(pid, 5, "林凡随手一掌，山石崩裂。", "设定");
        assertEquals(1, issues.size());
        assertEquals(com.writing.novel.domain.ContinuityIssue.Severity.HIGH, issues.get(0).getSeverity());
        assertEquals(1, repository.findUnresolvedIssues(pid).size(), "金标问题经链路落库");
        assertEquals(1, calls.get());
    }
}
