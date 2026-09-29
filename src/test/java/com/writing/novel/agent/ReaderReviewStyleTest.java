package com.writing.novel.agent;

import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.client.StreamHandler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-125: reader-review 风格维度——style 评分进报告；显著漂移（<6）拉低 overall。
 */
class ReaderReviewStyleTest {

    private static LlmGateway returning(String json) {
        return new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest req) { return new LlmResponse(json, 10, 50); }
            @Override public void streamComplete(LlmRequest req, StreamHandler h) {}
        };
    }

    @Test
    void style评分进报告_正常不影响overall() {
        var agent = new ReaderReviewAgent(returning("""
                {"scores":{"engagement":8,"coherence":8,"character":8,"hook":7,"style":8},
                "overall":8,"summary":"可以","issues":[],"improvement":""}
                """), TestPromptHelper.create());
        var report = agent.review(1, "正文内容");
        assertEquals(8, report.getStyle());
        assertEquals(8, report.getScore(), "style 及格不拉低 overall");
    }

    @Test
    void style显著漂移_拉低overall() {
        var agent = new ReaderReviewAgent(returning("""
                {"scores":{"engagement":9,"coherence":9,"character":9,"hook":9,"style":3},
                "overall":9,"summary":"像换了作者","issues":["文风突变"],"improvement":""}
                """), TestPromptHelper.create());
        var report = agent.review(1, "正文内容");
        assertEquals(3, report.getStyle());
        assertTrue(report.getScore() < 9, "style=3 时 overall 必须被拉低（解析侧兜底）");
        assertTrue(report.getScore() >= 3);
    }

    @Test
    void 缺style维度_旧格式兼容_不影响overall() {
        var agent = new ReaderReviewAgent(returning(
                "{\"scores\":{\"engagement\":8,\"coherence\":8,\"character\":8,\"hook\":7},"
                        + "\"overall\":8,\"summary\":\"s\",\"issues\":[],\"improvement\":\"\"}"),
                TestPromptHelper.create());
        var report = agent.review(1, "正文内容");
        assertEquals(0, report.getStyle(), "缺失=未评");
        assertEquals(8, report.getScore(), "未评不拉低 overall（旧格式兼容）");
    }
}
