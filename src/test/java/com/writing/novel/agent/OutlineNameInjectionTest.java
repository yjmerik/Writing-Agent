package com.writing.novel.agent;

import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.llm.prompt.PromptTemplateService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * cn-01/cn-02: master/volume outline 主角名注入测试。
 * 验证 planMasterOutline / planVolume 接收主角名并渲染到模板变量。
 */
class OutlineNameInjectionTest {

    private static class CapturingGateway implements LlmGateway {
        String capturedSystem;
        @Override public LlmResponse complete(LlmRequest req) {
            capturedSystem = req.getSystemPrompt();
            return new LlmResponse("【总纲】大纲内容", 50, 50);
        }
        @Override public void streamComplete(LlmRequest req, StreamHandler h) {}
    }

    @Test
    void masterOutline注入主角名() {
        CapturingGateway gw = new CapturingGateway();
        PromptTemplateService promptSvc = TestPromptHelper.create();
        PlotterAgent plotter = new PlotterAgent(gw, null, promptSvc);

        plotter.planMasterOutline("p1", "账房学徒的故事", 400,
                "世界观摘要", "题材规则", "顾蘅秋", "主角：顾蘅秋，账房学徒");

        assertNotNull(gw.capturedSystem);
        assertTrue(gw.capturedSystem.contains("顾蘅秋"), "master outline 应含主角名: " + gw.capturedSystem);
        assertTrue(gw.capturedSystem.contains("主角名锚定"), "应含锚定原则");
    }

    @Test
    void masterOutline无主角名时不报错() {
        CapturingGateway gw = new CapturingGateway();
        PromptTemplateService promptSvc = TestPromptHelper.create();
        PlotterAgent plotter = new PlotterAgent(gw, null, promptSvc);

        // 旧签名（无主角名）——兼容
        plotter.planMasterOutline("p1", "故事", 400, "世界观", "规则");
        assertNotNull(gw.capturedSystem);
    }

    @Test
    void volumeOutline注入主角名() {
        CapturingGateway gw = new CapturingGateway();
        PromptTemplateService promptSvc = TestPromptHelper.create();
        PlotterAgent plotter = new PlotterAgent(gw, null, promptSvc);

        plotter.planVolume("总纲文本", "前文摘要", "卷1", "卷梗概",
                1, 8, "沈砚(主角)", "沈砚");

        assertNotNull(gw.capturedSystem);
        assertTrue(gw.capturedSystem.contains("「沈砚」"), "volume outline 应含主角名: " + gw.capturedSystem);
        assertTrue(gw.capturedSystem.contains("主角名锚定"));
    }
}
