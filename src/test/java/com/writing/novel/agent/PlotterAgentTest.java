package com.writing.novel.agent;

import com.writing.novel.domain.Outline;
import com.writing.novel.domain.Volume;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.client.StreamHandler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlotterAgentTest {

    @Test
    void 生成全书大纲与卷() {
        LlmGateway mockGateway = new LlmGateway() {
            @Override
            public LlmResponse complete(LlmRequest request) {
                return new LlmResponse("""
                    【总纲】张三从凡人少年成长为剑道宗师，期间经历灭门、拜师、复仇、悟道，最终证道飞升。

                    【卷1】初入仙门
                    张三家破人亡，机缘巧合拜入青云宗，开始修炼之路。
                    第1章 灵根测试：张三参加灵根测试，意外觉醒上古剑骨。
                    第2章 拜入宗门：张三正式成为青云宗外门弟子。
                    第3章 初次修炼：张三开始修炼基础剑法，展现惊人天赋。

                    【卷2】宗门风云
                    张三在宗门中崭露头角，卷入内门争斗。
                    第4章 外门比武：张三参加外门比武大会。
                    第5章 结怨：张三与内门弟子结下梁子。
                    """, 100, 500);
            }
            @Override
            public void streamComplete(LlmRequest request, StreamHandler handler) {}
        };

        PlotterAgent agent = new PlotterAgent(mockGateway, new TestNovelRepositoryStub());
        Outline outline = agent.plan("proj-1", "剑修复仇玄幻故事",
                "主角从凡人修炼至大乘", 3);

        assertNotNull(outline);
        assertTrue(outline.getPremise().contains("张三从凡人少年成长为剑道宗师"));
        assertEquals(2, outline.getVolumes().size());
        assertEquals("初入仙门", outline.getVolumes().get(0).getTitle());
        assertEquals(3, outline.getVolumes().get(0).getSynopses().size());
        assertEquals(2, outline.getVolumes().get(1).getSynopses().size());
    }
}
