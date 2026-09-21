package com.writing.novel.web;

import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.client.StreamHandler;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Web 控制器测试专用：用桩 LlmGateway 覆盖真实的 DefaultLlmGateway，
 * 避免测试依赖外部 API Key。返回内容按 {@code ResponseParser} 可解析的格式给出，
 * 与 NovelOrchestratorConceiveTest 中的桩响应保持一致。
 */
@TestConfiguration
public class StubLlmGatewayTestConfig {

    @Bean
    @Primary
    public LlmGateway stubLlmGateway() {
        return new LlmGateway() {
            @Override
            public LlmResponse complete(LlmRequest req) {
                if (req.getUserMessage().startsWith("请提取第") && req.getUserMessage().endsWith("章确立的事实。")) {
                    return new LlmResponse("[]", 1, 1);
                }
                // NCS-067: 实体提取拆为两阶段——各自返回合法空结果
                if (req.getUserMessage().startsWith("请提取第") && req.getUserMessage().endsWith("章中的实体。")) {
                    return new LlmResponse("{\"entities\":[]}", 1, 1);
                }
                if (req.getUserMessage().startsWith("请提取第") && req.getUserMessage().endsWith("章中实体之间的关系。")) {
                    return new LlmResponse("{\"edges\":[]}", 1, 1);
                }
                String content = switch (req.getTaskType()) {
                    case WORLDBUILDING ->
                            "【地理】玄黄大陆：东方主大陆。\n【力量体系】灵气：修炼九重。";
                    case CHARACTER_DESIGN ->
                            "【姓名】张三\n【角色定位】主角\n【性格】火爆\n【能力】剑修";
                    case PLOTTING ->
                            "【总纲】张三的修炼之路。\n\n【卷1】初入仙门\n拜入宗门。\n第1章 测试：灵根测试。";
                    default -> "这是桩 LLM 生成的正文内容，足够长用于测试章节写入和存储。".repeat(80);
                };
                return new LlmResponse(content, 50, 100);
            }

            @Override
            public void streamComplete(LlmRequest req, StreamHandler handler) {
                // no-op for tests
            }
        };
    }
}
