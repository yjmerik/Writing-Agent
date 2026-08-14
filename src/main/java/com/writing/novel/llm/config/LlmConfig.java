package com.writing.novel.llm.config;

import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.client.LlmClient;
import com.writing.novel.llm.client.OpenAiCompatibleClient;
import com.writing.novel.llm.gateway.DefaultLlmGateway;
import com.writing.novel.llm.gateway.ObservedLlmGateway;
import com.writing.novel.llm.gateway.RecordReplayLlmGateway;
import com.writing.novel.telemetry.CostBudgetMonitor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * LLM 模块的 Bean 装配。
 * 装饰链（外→内）：ObservedLlmGateway（事件埋点）→ [RecordReplayLlmGateway（录制/回放，可选）] → DefaultLlmGateway（路由）。
 */
@Configuration
public class LlmConfig {

    @Bean
    public LlmClient llmClient() {
        return new OpenAiCompatibleClient();
    }

    @Bean
    public LlmGateway llmGateway(LlmProperties properties, LlmClient client,
                                 @Value("${llm.replay.mode:off}") String replayMode,
                                 @Value("${llm.replay.dir:data/fixtures/llm}") String replayDir,
                                 CostBudgetMonitor costBudgetMonitor) {
        // mc-p0-04: 启动时校验凭据（replay 模式跳过）
        properties.setReplayMode(replayMode.equalsIgnoreCase("replay"));
        properties.validateKeys();

        LlmGateway gateway = new DefaultLlmGateway(properties, client);
        switch (replayMode.toLowerCase()) {
            case "record" -> gateway = new RecordReplayLlmGateway(
                    gateway, RecordReplayLlmGateway.Mode.RECORD, Path.of(replayDir));
            case "replay" -> gateway = new RecordReplayLlmGateway(
                    gateway, RecordReplayLlmGateway.Mode.REPLAY, Path.of(replayDir));
            default -> { /* off：直连真实网关 */ }
        }
        // p10-w1-04: 注入 pricing + 成本累计，按 taskType 模型真实计价
        return new ObservedLlmGateway(gateway, properties, costBudgetMonitor);
    }
}
