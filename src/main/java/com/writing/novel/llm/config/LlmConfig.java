package com.writing.novel.llm.config;

import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.client.LlmClient;
import com.writing.novel.llm.client.OpenAiCompatibleClient;
import com.writing.novel.llm.gateway.DefaultLlmGateway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LLM 模块的 Bean 装配。
 * 装配 OpenAiCompatibleClient（HTTP 客户端）和 DefaultLlmGateway（路由网关）。
 */
@Configuration
public class LlmConfig {

    @Bean
    public LlmClient llmClient() {
        return new OpenAiCompatibleClient();
    }

    @Bean
    public LlmGateway llmGateway(LlmProperties properties, LlmClient client) {
        return new DefaultLlmGateway(properties, client);
    }
}
