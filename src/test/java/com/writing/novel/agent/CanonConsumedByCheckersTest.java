package com.writing.novel.agent;

import com.writing.novel.domain.Character;
import com.writing.novel.domain.StoryFact;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.memory.RelevanceGateway;
import com.writing.novel.spi.NovelRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * p11-q0-02: 验证检查器消费真典 ACTIVE 事实作为校验依据（修复 W2-07 未落地）。
 */
class CanonConsumedByCheckersTest {

    private static String content(int len) {
        StringBuilder sb = new StringBuilder(len + 4);
        while (sb.length() < len) sb.append("正文内容片段");
        return sb.toString();
    }

    /** 捕获 systemPrompt+userMessage 到 sink，返回无问题 JSON */
    private static LlmGateway capturing(List<String> sink, String jsonResp) {
        return new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest req) {
                sink.add(req.getSystemPrompt() + "\n@@@\n" + req.getUserMessage());
                return new LlmResponse(jsonResp, 10, 10);
            }
            @Override public void streamComplete(LlmRequest req, StreamHandler handler) {}
        };
    }

    @Test
    void consistencyGuard_消费真典事实注入prompt() {
        NovelRepository repo = mock(NovelRepository.class);
        RelevanceGateway gw = mock(RelevanceGateway.class);
        var fact = new StoryFact("p", StoryFact.FactType.CHARACTER_STATE, "季衡", "当前修为筑基期", 1);
        when(gw.relevantCharacters(any(), any(), anyInt())).thenReturn(List.of(new Character("p", "季衡", "主角")));
        when(gw.relevantFacts(eq("p"), any(), anyInt())).thenReturn(List.of(fact));

        var sink = new ArrayList<String>();
        var guard = new ConsistencyGuard(capturing(sink,
                "{\"abilityViolations\":[],\"evidenceProblems\":[],\"anachronisms\":[]}"),
                repo, TestPromptHelper.create(), gw);
        guard.check("p", 1, content(300), List.of("季衡"));

        verify(gw).relevantFacts(eq("p"), any(), anyInt());
        assertTrue(sink.stream().anyMatch(s -> s.contains("筑基期")), "真典事实应注入 consistency-guard prompt");
    }

    @Test
    void continuityChecker_消费真典事实注入检查段() {
        NovelRepository repo = mock(NovelRepository.class);
        when(repo.findSummariesByProject(any())).thenReturn(List.of());
        when(repo.findAllNarrativeThreads(any())).thenReturn(List.of());
        when(repo.findKnowledgeEntriesByProject(any())).thenReturn(List.of());
        when(repo.findProject(any())).thenReturn(Optional.empty());
        RelevanceGateway gw = mock(RelevanceGateway.class);
        var fact = new StoryFact("p", StoryFact.FactType.WORLD_RULE, "灵气体系", "九重境界不可越级", 1);
        when(gw.relevantCharacters(any(), any(), anyInt())).thenReturn(List.of());
        // NCS-097: 真典段改用与写作同源的四参口径（带章节号，recency 生效）
        when(gw.relevantFacts(eq("p"), any(), anyInt(), anyInt())).thenReturn(List.of(fact));

        var sink = new ArrayList<String>();
        var agent = new ContinuityCheckerAgent(capturing(sink, "未发现连续性问题。"),
                repo, TestPromptHelper.create(), gw);
        agent.check("p", 1, content(300), "相关设定");

        assertTrue(sink.stream().anyMatch(s -> s.contains("九重境界")), "真典事实应注入连续性检查段");
    }

    @Test
    void qualityEvaluation_消费真典事实注入评价() {
        NovelRepository repo = mock(NovelRepository.class);
        when(repo.findKnowledgeEntriesByProject(any())).thenReturn(List.of());
        when(repo.findCharactersByProject(any())).thenReturn(List.of());
        when(repo.findOutlineByProject(any())).thenReturn(Optional.empty());
        when(repo.findSummariesByProject(any())).thenReturn(List.of());
        when(repo.findChaptersByProject(any())).thenReturn(List.of());
        RelevanceGateway gw = mock(RelevanceGateway.class);
        var fact = new StoryFact("p", StoryFact.FactType.PLOT_COMMITMENT, "主角承诺", "必须手刃仇敌", 1);
        when(gw.relevantFacts(eq("p"), any(), anyInt())).thenReturn(List.of(fact));

        var sink = new ArrayList<String>();
        var agent = new QualityEvaluationAgent(capturing(sink,
                "{\"scores\":{},\"overallScore\":0,\"summary\":\"\",\"issues\":[]}"),
                repo, TestPromptHelper.create(), gw);
        agent.evaluate("p");

        assertTrue(sink.stream().anyMatch(s -> s.contains("手刃仇敌")), "真典事实应注入质量评价依据");
    }

    @Test
    void consistencyGuard_无gateway时降级_不注入真典() {
        NovelRepository repo = mock(NovelRepository.class);
        when(repo.findCharactersByProject(any())).thenReturn(List.of());
        var sink = new ArrayList<String>();
        var guard = new ConsistencyGuard(capturing(sink,
                "{\"abilityViolations\":[],\"evidenceProblems\":[],\"anachronisms\":[]}"),
                repo, TestPromptHelper.create()); // 无 gateway
        guard.check("p", 1, content(300));
        assertTrue(sink.stream().anyMatch(s -> s.contains("无已确立事实"))); // 降级占位
    }
}
