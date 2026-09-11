package com.writing.novel.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.agent.ForeshadowingExtractor;
import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.Foreshadowing;
import com.writing.novel.domain.Project;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * p33-07（设计 5.5 / 体验 #15）：细节线索先入候选层，正式伏笔只收叙事承诺。
 * 复现：5 章 97 条伏笔（70 未回收），时间差/口型等细节全进台账且无回收窗口。
 * 修复：HIGH/mustResolve/带预计回收章 → 正式 Foreshadowing；
 * 其余 → foreshadow_candidates（PENDING），作者可提升或拒绝。
 */
class ForeshadowCandidateLayerTest {

    @TempDir
    Path tempDir;
    private SqliteNovelRepository repo;
    private String pid;

    private ForeshadowingExtractor extractorReturning(String json) {
        LlmGateway gw = new LlmGateway() {
            @Override
            public LlmResponse complete(LlmRequest req) {
                return new LlmResponse(json, 50, 50);
            }

            @Override
            public void streamComplete(LlmRequest req, StreamHandler h) {
            }
        };
        return new ForeshadowingExtractor(gw, repo,
                com.writing.novel.agent.TestPromptHelper.create());
    }

    private int extract(String json) {
        Chapter ch = new Chapter(pid, 3, "第三章");
        ch.setContent("正文采样，包含足够长度的叙述内容以供提取器解析。".repeat(30));
        return extractorReturning(json).extract(pid, ch);
    }

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("fcl.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        Project p = new Project("候选分层", "概念", "悬疑");
        repo.saveProject(p);
        pid = p.getId();
    }

    @Test
    void 高重要性进正式伏笔细节线索进候选层() {
        String json = """
                {"foreshadowings":[
                 {"description":"铜牌日期差暗示两份档案造假","priority":"HIGH","context":"卷宗对比"},
                 {"description":"报警信寄出时间差三天","priority":"MEDIUM","context":"时间线细节"},
                 {"description":"门口皮鞋泥痕方向","priority":"LOW","context":"现场细节"}
                ]}
                """;
        int processed = extract(json);

        assertEquals(3, processed, "三条都被处理");
        assertEquals(1, repo.findForeshadowingsByProject(pid).size(),
                "仅 HIGH/带承诺属性者进入正式伏笔");
        assertEquals("铜牌日期差暗示两份档案造假",
                repo.findForeshadowingsByProject(pid).get(0).getDescription());
        assertTrue(repo.findForeshadowingsByProject(pid).get(0).getPriority() == Foreshadowing.Priority.HIGH);

        var candidates = repo.findForeshadowCandidates(pid);
        assertEquals(2, candidates.size(), "其余细节进候选层");
        assertTrue(candidates.stream().allMatch(c -> c.getStatus()
                == com.writing.novel.domain.ForeshadowCandidate.Status.PENDING));
    }

    @Test
    void 候选可提升为正式伏笔或拒绝() {
        extract("""
                {"foreshadowings":[
                 {"description":"线索甲待作者裁决","priority":"MEDIUM","context":"细节"},
                 {"description":"线索乙待作者裁决","priority":"LOW","context":"细节"}
                ]}
                """);
        var candidates = repo.findForeshadowCandidates(pid);
        assertEquals(2, candidates.size());

        repo.promoteForeshadowCandidate(pid, candidates.get(0).getId());
        repo.rejectForeshadowCandidate(pid, candidates.get(1).getId());

        assertEquals(1, repo.findForeshadowingsByProject(pid).size(), "提升后成为正式伏笔");
        var after = repo.findForeshadowCandidates(pid);
        assertEquals(1, after.stream().filter(c -> c.getStatus()
                == com.writing.novel.domain.ForeshadowCandidate.Status.PROMOTED).count());
        assertEquals(1, after.stream().filter(c -> c.getStatus()
                == com.writing.novel.domain.ForeshadowCandidate.Status.REJECTED).count());
    }

    @Test
    void 候选与正式伏笔描述去重互通() {
        extract("""
                {"foreshadowings":[
                 {"description":"铜牌日期差暗示两份档案造假","priority":"HIGH","context":"卷宗对比"}
                ]}
                """);
        // 下一章再次提取同一描述：正式表命中去重，不新增候选也不新增正式
        int second = extract("""
                {"foreshadowings":[
                 {"description":"铜牌日期差暗示两份档案造假","priority":"MEDIUM","context":"卷宗对比"}
                ]}
                """);
        assertEquals(0, second, "重复描述被去重，不重复入任何层");
        assertEquals(1, repo.findForeshadowingsByProject(pid).size());
        assertEquals(0, repo.findForeshadowCandidates(pid).size());
    }
}
