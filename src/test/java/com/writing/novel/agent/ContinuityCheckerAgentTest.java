package com.writing.novel.agent;

import com.writing.novel.domain.ContinuityIssue;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.client.StreamHandler;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContinuityCheckerAgentTest {

    @Test
    void 检测到矛盾则生成issue() {
        LlmGateway mockGateway = new LlmGateway() {
            @Override
            public LlmResponse complete(LlmRequest req) {
                return new LlmResponse("""
                    发现以下连续性问题：
                    【人物|高】张三的佩剑名前后不一致：第3章叫断水，第12章叫霜寒。
                    【世界观|中】第8章提到的九重境界与设定中的灵气体系描述矛盾。
                    """, 50, 100);
            }
            @Override
            public void streamComplete(LlmRequest req, StreamHandler handler) {}
        };

        ContinuityCheckerAgent agent = new ContinuityCheckerAgent(mockGateway, null, TestPromptHelper.create());
        List<ContinuityIssue> issues = agent.check("proj-1", 12,
                "第12章正文……", "人物设定：张三用断水剑");

        assertEquals(2, issues.size());
        assertEquals(ContinuityIssue.Severity.HIGH, issues.get(0).getSeverity());
        assertEquals(ContinuityIssue.Type.CHARACTER, issues.get(0).getType());
        assertTrue(issues.get(0).getDescription().contains("佩剑"));
    }

    @Test
    void 无矛盾则返回空列表() {
        LlmGateway mockGateway = new LlmGateway() {
            @Override
            public LlmResponse complete(LlmRequest req) {
                return new LlmResponse("未发现连续性问题。", 10, 10);
            }
            @Override
            public void streamComplete(LlmRequest req, StreamHandler handler) {}
        };
        ContinuityCheckerAgent agent = new ContinuityCheckerAgent(mockGateway, null, TestPromptHelper.create());
        List<ContinuityIssue> issues = agent.check("p1", 5, "正文", "设定");
        assertTrue(issues.isEmpty());
    }
}
