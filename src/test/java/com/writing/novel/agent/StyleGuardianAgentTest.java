package com.writing.novel.agent;

import com.writing.novel.domain.StyleProfile;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.client.StreamHandler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StyleGuardianAgentTest {

    @Test
    void 从样章生成风格基线() {
        LlmGateway mockGateway = new LlmGateway() {
            @Override
            public LlmResponse complete(LlmRequest req) {
                return new LlmResponse("""
                    【基调】热血、紧凑、节奏明快
                    【叙事】第三人称，多动作描写，少心理独白
                    【注意事项】多用短句推进节奏；避免大段说明性文字；战斗场景要有画面感
                    """, 50, 100);
            }
            @Override
            public void streamComplete(LlmRequest req, StreamHandler handler) {}
        };

        StyleGuardianAgent agent = new StyleGuardianAgent(mockGateway, null);
        StyleProfile profile = agent.establishProfile("proj-1", "样章正文……");

        assertNotNull(profile);
        assertEquals("热血、紧凑、节奏明快", profile.getTone());
        assertTrue(profile.getDosAndDonts().contains("短句"));
    }

    @Test
    void 检测风格一致性() {
        LlmGateway mockGateway = new LlmGateway() {
            @Override
            public LlmResponse complete(LlmRequest req) {
                return new LlmResponse("风格一致性评分：85/100。整体符合热血风格，但第3段节奏偏慢。", 10, 30);
            }
            @Override
            public void streamComplete(LlmRequest req, StreamHandler handler) {}
        };
        StyleGuardianAgent agent = new StyleGuardianAgent(mockGateway, null);
        String report = agent.checkConsistency("proj-1", "新章节正文", "热血风格");
        assertTrue(report.contains("85"));
    }
}
