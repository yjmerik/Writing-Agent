package com.writing.novel.agent;

import com.writing.novel.domain.KnowledgeEntry;
import com.writing.novel.domain.Worldbuilding;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.memory.KnowledgeBaseService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorldBuilderAgentTest {

    @Test
    void 从构思生成世界观条目() {
        // LlmGateway 有 2 个抽象方法（complete + streamComplete），不是函数式接口，
        // 因此不能用 lambda，必须用匿名内部类实现两个方法。
        LlmGateway mockGateway = new LlmGateway() {
            @Override
            public LlmResponse complete(LlmRequest request) {
                return new LlmResponse("""
                        【地理】玄黄大陆：故事主大陆，分东西两域。
                        【力量体系】灵气体系：修炼者吸收天地灵气，境界分九重。
                        【势力】青云宗：玄黄大陆东部第一大宗。
                        """, 100, 200);
            }

            @Override
            public void streamComplete(LlmRequest request, StreamHandler handler) {
                // 测试中不使用流式调用
            }
        };

        KnowledgeBaseService mockKb = new KnowledgeBaseService(null, null) {
            @Override public void saveEntry(KnowledgeEntry e) { }
            @Override public List<KnowledgeEntry> findAllEntries(String pid) { return List.of(); }
        };

        WorldBuilderAgent agent = new WorldBuilderAgent(mockGateway, mockKb, TestPromptHelper.create());
        Worldbuilding wb = agent.build("proj-1", "一个剑修复仇的玄幻故事", "玄幻,热血");

        assertNotNull(wb);
        assertFalse(wb.getEntries().isEmpty(), "应生成世界观条目");
        assertTrue(wb.getEntries().stream().anyMatch(e -> e.getName().contains("玄黄大陆")));
        assertTrue(wb.getEntries().stream().anyMatch(e -> e.getName().contains("灵气")));
    }
}
