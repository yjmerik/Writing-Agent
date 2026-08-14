package com.writing.novel.agent;

import com.writing.novel.domain.Character;
import com.writing.novel.domain.Entity;
import com.writing.novel.domain.EntityType;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.memory.RelevanceGateway;
import com.writing.novel.spi.NovelRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * p11-q0-01: 验证检查器经 RelevanceGateway 收窄，不再全量 findAll*（修复 W2-05 未落地）。
 */
class RelevanceGatewayNarrowingTest {

    private static LlmGateway stubLlm() {
        return new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest req) {
                return new LlmResponse("{\"abilityViolations\":[],\"evidenceProblems\":[],\"anachronisms\":[]}", 10, 10);
            }
            @Override public void streamComplete(LlmRequest req, StreamHandler handler) {}
        };
    }

    private static String content(int len) {
        StringBuilder sb = new StringBuilder(len + 4);
        while (sb.length() < len) sb.append("正文内容片段");
        return sb.toString();
    }

    @Test
    void consistencyGuard_注入gateway后走relevantCharacters_不再全量() {
        NovelRepository repo = mock(NovelRepository.class);
        RelevanceGateway gw = mock(RelevanceGateway.class);
        Character mc = new Character("p", "季衡", "主角");
        mc.setAbility("剑术初成");
        when(gw.relevantCharacters(eq("p"), anyList(), anyInt())).thenReturn(List.of(mc));

        ConsistencyGuard guard = new ConsistencyGuard(stubLlm(), repo, TestPromptHelper.create(), gw);
        guard.check("p", 1, content(300), List.of("季衡"));

        verify(gw).relevantCharacters(eq("p"), anyList(), anyInt());
        verify(repo, never()).findCharactersByProject(any()); // 不再全量加载
    }

    @Test
    void consistencyGuard_无gateway时降级全量_向后兼容() {
        NovelRepository repo = mock(NovelRepository.class);
        when(repo.findCharactersByProject("p")).thenReturn(List.of());

        ConsistencyGuard guard = new ConsistencyGuard(stubLlm(), repo, TestPromptHelper.create());
        guard.check("p", 1, content(300)); // 旧 3 参签名仍可用

        verify(repo).findCharactersByProject("p"); // 降级全量
    }

    @Test
    void consistencyGuard_旧三参签名等价于cast为null() {
        NovelRepository repo = mock(NovelRepository.class);
        RelevanceGateway gw = mock(RelevanceGateway.class);
        when(gw.relevantCharacters(any(), any(), anyInt())).thenReturn(List.of());

        ConsistencyGuard guard = new ConsistencyGuard(stubLlm(), repo, TestPromptHelper.create(), gw);
        guard.check("p", 1, content(300)); // 旧签名经 gateway，主角保底

        verify(gw).relevantCharacters(eq("p"), any(), anyInt());
        verify(repo, never()).findCharactersByProject(any());
    }

    @Test
    void relevanceGateway_relevantEntities_按recency收窄() {
        NovelRepository repo = mock(NovelRepository.class);
        Entity old = new Entity("p", "旧物", EntityType.ITEM); old.seenAt(2);
        Entity recent = new Entity("p", "新物", EntityType.ITEM); recent.seenAt(20);
        when(repo.findEntitiesByProject("p")).thenReturn(List.of(old, recent));

        RelevanceGateway gw = new RelevanceGateway(repo, null);
        List<Entity> out = gw.relevantEntities("p", List.of(), 1);

        assertEquals(1, out.size());
        assertEquals("新物", out.get(0).getName()); // 最近出现的优先
    }

    @Test
    void relevanceGateway_relevantEntities_按关键词匹配() {
        NovelRepository repo = mock(NovelRepository.class);
        Entity a = new Entity("p", "断水剑", EntityType.ITEM); a.seenAt(5);
        Entity b = new Entity("p", "九重塔", EntityType.LOCATION); b.seenAt(6);
        when(repo.findEntitiesByProject("p")).thenReturn(List.of(a, b));

        RelevanceGateway gw = new RelevanceGateway(repo, null);
        List<Entity> out = gw.relevantEntities("p", List.of("断水"), 5);

        assertEquals(1, out.size());
        assertEquals("断水剑", out.get(0).getName());
    }
}
