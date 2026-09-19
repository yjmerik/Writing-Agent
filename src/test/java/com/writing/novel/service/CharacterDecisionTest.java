package com.writing.novel.service;

import com.writing.novel.agent.CharacterDecisionExtractor;
import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.Project;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.persistence.sqlite.*;
import com.writing.novel.telemetry.EventLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-012（方案 §07/F16）：CharacterDecision 决策记录。
 * 关键决策按章提取落库（触发/所知/选择/代价/冲突/证据锚点）；坏输出→UNKNOWN 不伪装为空；
 * 按需端点幂等；管线阶段默认关闭不影响既有回放。
 */
class CharacterDecisionTest {

    @TempDir
    Path tempDir;

    private SqliteNovelRepository repo;
    private CharacterDecisionRepository decisionRepo;
    private CharacterDecisionService service;
    private String projectId;
    private Chapter chapter;
    private EventLog.Listener listener;
    private final List<String> events = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        var cp = new ConnectionProvider(tempDir.resolve("decision.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        decisionRepo = new CharacterDecisionRepository(cp);
        Project project = new Project("决策项目", "构思", "武侠");
        repo.saveProject(project);
        projectId = project.getId();
        chapter = new Chapter(projectId, 8, "第八章");
        chapter.setContent("江砚把密信凑近烛火。他想起沈昭还在等他的回信，但信里的名字一旦泄露，渡口所有人都活不成。他松开手，纸角卷起来，火舌吞掉了那行字。".repeat(30));
        chapter.setStatus(Chapter.Status.DONE);
        repo.saveChapter(chapter);
        listener = e -> events.add(String.valueOf(e.get("event")));
        EventLog.subscribe(listener);
    }

    @AfterEach
    void tearDown() {
        EventLog.unsubscribe(listener);
    }

    private CharacterDecisionService serviceWith(LlmGateway gateway) {
        return new CharacterDecisionService(decisionRepo,
                new CharacterDecisionExtractor(gateway, com.writing.novel.agent.TestPromptHelper.create()));
    }

    @Test
    void 提取落库_字段完整_证据锚点可定位() {
        LlmGateway gateway = new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest req) {
                return new LlmResponse("""
                        [{"character":"江砚","triggerEvent":"沈昭催要回信，官差开始搜查渡口",
                          "knewThen":"信中名单足以让渡口所有人获罪；沈昭不知道他的真实身份",
                          "options":["交出密信保全自己","焚毁密信保护众人","把密信藏起来观望"],
                          "chosenAction":"焚毁密信",
                          "cost":"从此无法自证清白，沈昭的等待落空",
                          "conflictWithCommitment":"曾向沈昭承诺'信必亲手交还'",
                          "evidenceQuote":"他松开手，纸角卷起来，火舌吞掉了那行字"}]
                        """, 200, 400);
            }
            @Override public void streamComplete(LlmRequest req, com.writing.novel.llm.client.StreamHandler h) {}
        };
        var result = serviceWith(gateway).extractForChapter(projectId, chapter);

        assertEquals(1, result.extracted());
        var decisions = decisionRepo.findByProject(projectId);
        assertEquals(1, decisions.size());
        var d = decisions.get(0);
        assertEquals("江砚", d.getCharacterName());
        assertEquals("焚毁密信", d.getChosenAction());
        assertEquals("从此无法自证清白，沈昭的等待落空", d.getCost());
        assertEquals(8, d.getChapterIndex());
        assertEquals(2, d.getChapterVersion());  // setContent 自增：初稿1+内容2
        assertTrue(d.getEvidenceQuote().contains("火舌吞掉"));
        assertEquals(3, d.getOptions().size());
        assertNotNull(d.getId());
    }

    @Test
    void 坏输出标UNKNOWN_不产生空决策() {
        LlmGateway gateway = new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest req) {
                return new LlmResponse("###ERR 截断", 5, 5);
            }
            @Override public void streamComplete(LlmRequest req, com.writing.novel.llm.client.StreamHandler h) {}
        };
        var result = serviceWith(gateway).extractForChapter(projectId, chapter);
        assertEquals(0, result.extracted());
        assertTrue(decisionRepo.findByProject(projectId).isEmpty(), "坏输出不得产生决策条目");
        assertEquals(1, decisionRepo.findExtractions(projectId).stream()
                .filter(x -> x.status() == com.writing.novel.agent.CheckResultEnvelope.Status.UNKNOWN
                        || x.status() == com.writing.novel.agent.CheckResultEnvelope.Status.INVALID).count(),
                "提取执行记录必须标 UNKNOWN/INVALID（不伪装为无决策）");
    }

    @Test
    void 按需端点幂等_同章同稿重复提取不重复落库() {
        LlmGateway gateway = new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest req) {
                return new LlmResponse(
                        "[{\"character\":\"江砚\",\"triggerEvent\":\"t\",\"knewThen\":\"k\"," +
                        "\"options\":[\"a\",\"b\"],\"chosenAction\":\"a\",\"cost\":\"c\"," +
                        "\"conflictWithCommitment\":\"\",\"evidenceQuote\":\"q\"}]", 50, 80);
            }
            @Override public void streamComplete(LlmRequest req, com.writing.novel.llm.client.StreamHandler h) {}
        };
        var service = serviceWith(gateway);
        service.extractForChapter(projectId, chapter);
        service.extractForChapter(projectId, chapter); // 同章同稿幂等
        assertEquals(1, decisionRepo.findByProject(projectId).size(),
                "同章同稿的重复提取不重复落库（同源覆盖）");
    }

    @Test
    void 正文修订后重新提取产生新版决策() {
        LlmGateway gateway = new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest req) {
                return new LlmResponse(
                        "[{\"character\":\"江砚\",\"triggerEvent\":\"t1\",\"knewThen\":\"k\"," +
                        "\"options\":[\"a\"],\"chosenAction\":\"a\",\"cost\":\"c\"," +
                        "\"conflictWithCommitment\":\"\",\"evidenceQuote\":\"q1\"}]", 50, 80);
            }
            @Override public void streamComplete(LlmRequest req, com.writing.novel.llm.client.StreamHandler h) {}
        };
        var service = serviceWith(gateway);
        service.extractForChapter(projectId, chapter);
        // 章节修订 → 新稿
        chapter.setContent("江砚把密信塞回袖中，转身走进雾里。".repeat(40));
        repo.saveChapter(chapter);
        int newVersion = repo.findChapterByIndex(projectId, 8).orElseThrow().getVersion();
        service.extractForChapter(projectId, repo.findChapterByIndex(projectId, 8).orElseThrow());
        var decisions = decisionRepo.findByProject(projectId);
        assertTrue(decisions.stream().anyMatch(d -> d.getChapterVersion() == newVersion),
                "新稿产生绑定新版本的决策（旧版保留可追溯）");
        assertTrue(decisions.size() >= 2, "历史决策不删除");
    }
}
