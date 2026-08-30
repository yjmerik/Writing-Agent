package com.writing.novel.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * p23-01: 确定性场景网关——按任务类型产出语法合法、逐字可复现的响应，全程零网络。
 * 为 400 章 soak / 业务生命周期 E2E 提供驱动原语。
 */
class ScenarioLlmGatewayTest {

    private final ScenarioLlmGateway gateway = new ScenarioLlmGateway();

    private LlmRequest req(LlmTaskType type, String systemPrompt) {
        return LlmRequest.builder()
                .taskType(type)
                .systemPrompt(systemPrompt)
                .userMessage("请提取第3章确立的事实。")
                .build();
    }

    @Test
    void 全部任务类型响应非空且无网络依赖() {
        for (LlmTaskType type : LlmTaskType.values()) {
            LlmResponse r = gateway.complete(req(type, "任意上下文"));
            assertNotNull(r);
            assertNotNull(r.getContent());
            assertFalse(r.getContent().isBlank(), type + " 响应不得为空");
        }
    }

    @Test
    void 正文响应满足入库下限且镜像章节号() {
        LlmResponse r = gateway.complete(req(LlmTaskType.CHAPTER_WRITING, "第12章 试炼"));
        assertTrue(r.getContent().length() >= 2000, "场景正文必须达到最小入库门槛");
        assertTrue(r.getContent().contains("第12章"), "正文应镜像 prompt 中的章节号");
    }

    @Test
    void 构思类响应可被解析器识别() {
        String world = gateway.complete(req(LlmTaskType.WORLDBUILDING, "设定")).getContent();
        assertTrue(world.contains("【地理】") && world.contains("【力量体系】"));

        String character = gateway.complete(req(LlmTaskType.CHARACTER_DESIGN, "设计")).getContent();
        assertTrue(character.contains("【姓名】") && character.contains("【角色定位】"));

        String plot = gateway.complete(req(LlmTaskType.PLOTTING, "规划")).getContent();
        assertTrue(plot.contains("【总纲】") && plot.contains("【卷1】"));
        assertTrue(plot.contains("第1章") && plot.contains("第3章"), "总纲应含可解析的章节行");
    }

    @Test
    void 校验收割类任务返回可解析JSON数组() {
        String continuity = gateway.complete(req(LlmTaskType.CONTINUITY_CHECK, "检查")).getContent();
        assertTrue(continuity.trim().startsWith("["), "一致性检查应返回 JSON 数组（零问题）");

        String general = gateway.complete(req(LlmTaskType.GENERAL, "收割/抽取")).getContent();
        assertTrue(general.trim().startsWith("["), "GENERAL（收割/抽取族）应返回 JSON 数组");
    }

    @Test
    void 同参数响应逐字可复现() {
        LlmRequest r = req(LlmTaskType.CHAPTER_WRITING, "第5章");
        String first = gateway.complete(r).getContent();
        String second = gateway.complete(r).getContent();
        assertEquals(first, second, "场景网关必须确定性（逐字一致）");
    }

    @Test
    void 不同章节号的正文响应可区分() {
        String a = gateway.complete(req(LlmTaskType.CHAPTER_WRITING, "第7章")).getContent();
        String b = gateway.complete(req(LlmTaskType.CHAPTER_WRITING, "第8章")).getContent();
        assertNotEquals(a, b, "不同章节应产出可区分的确定性响应");
    }
}
