package com.writing.novel.agent;

import com.writing.novel.llm.*;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.spi.NovelRepository;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class QualityResponseValidityTest {
    private LlmGateway returning(String response) {
        return new LlmGateway() {
            public LlmResponse complete(LlmRequest request) { return new LlmResponse(response, 10, 10); }
            public void streamComplete(LlmRequest request, StreamHandler handler) {}
        };
    }
    @ParameterizedTest
    @ValueSource(strings={"{}", "null", "[]", "{\"overall\":10,\"summary\":\"通过\"}",
        "{\"scores\":{\"engagement\":8,\"coherence\":8,\"character\":8,\"hook\":8},\"overall\":99,\"summary\":\"通过\"}"})
    void malformedReaderScoresMustNotBeAccepted(String raw) {
        var review = new ReaderReviewAgent(returning(raw), TestPromptHelper.create()).review(1, "正文");
        assertEquals("检验解析失败", review.getSummary());
    }
    @ParameterizedTest
    @ValueSource(strings={"{}", "null", "[]", "不输出JSON",
        "{\"abilityViolations\":[],\"evidenceProblems\":[],\"anachronisms\":{}}",
        "{\"abilityViolations\":[{}],\"evidenceProblems\":[],\"anachronisms\":[]}"})
    void malformedGuardOutputMustNotMeanNoIssues(String raw) {
        var guard = new ConsistencyGuard(returning(raw), mock(NovelRepository.class), TestPromptHelper.create());
        assertThrows(IllegalStateException.class, () -> guard.check("p", 1, "正文".repeat(200)));
    }
}
