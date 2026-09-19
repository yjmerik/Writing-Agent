package com.writing.novel.llm.config;

import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.client.LlmClient;
import com.writing.novel.llm.client.ProtocolRoutingLlmClient;
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
        // p43-01: 协议分发——OPENAI_CHAT_COMPLETIONS（默认）仍走 OpenAiCompatibleClient，存量配置零行为变化
        return new ProtocolRoutingLlmClient();
    }

    @Bean
    public LlmGateway llmGateway(LlmProperties properties, LlmModelConfigStore modelConfigStore, LlmClient client,
                                 @Value("${llm.replay.mode:off}") String replayMode,
                                 @Value("${llm.replay.dir:data/fixtures/llm}") String replayDir,
                                 @Value("${llm.scenario.enabled:false}") boolean scenarioEnabled,
                                 @Value("${llm.scenario.harsh:false}") boolean scenarioHarsh,
                                 CostBudgetMonitor costBudgetMonitor) {
        // modelConfigStore 作为依赖注入：其构造函数先执行页面覆盖层合并，
        // 保证下方 validateKeys() 校验的是 yml 基线 + 覆盖层合并后的最终配置
        // p23-01/p23-03: 确定性场景网关——零网络零成本（soak/可靠性验证专用），无需任何 API key
        if (scenarioEnabled) {
            return new ObservedLlmGateway(new com.writing.novel.llm.ScenarioLlmGateway(scenarioHarsh),
                    properties, costBudgetMonitor);
        }
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
