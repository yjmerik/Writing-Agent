package com.writing.novel.agent;

import com.writing.novel.agent.ContentRevisionAgent.RevisionCandidate;
import com.writing.novel.agent.ContentRevisionAgent.TargetType;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.spi.NovelRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentRevisionAgentTest {

    @ParameterizedTest
    @EnumSource(TargetType.class)
    void previewsAllTargetTypesWithScopedPrompt(TargetType targetType) {
        String currentJson = "{\"id\":\"target-1\",\"title\":\"旧标题\"}";
        String instructions = "只调整节奏，保留目标 ID";
        RecordingGateway gateway = new RecordingGateway(validResponse(targetType));
        ContentRevisionAgent agent = new ContentRevisionAgent(gateway, TestPromptHelper.create());

        RevisionCandidate candidate = agent.preview(targetType, currentJson, instructions);

        assertEquals(targetType, candidate.targetType());
        assertEquals("修改候选", candidate.title());
        assertEquals("更新后的正文", candidate.content());
        assertEquals("{\"id\":\"target-1\",\"title\":\"新标题\"}", candidate.structuredJson());
        assertEquals("调整节奏并保留身份字段", candidate.summary());
        assertEquals(targetType.name(), gateway.request.getSystemPrompt().contains(targetType.name())
                ? targetType.name() : "missing");
        assertTrue(gateway.request.getSystemPrompt().contains(currentJson));
        assertTrue(gateway.request.getSystemPrompt().contains(instructions));
        assertTrue(gateway.request.getSystemPrompt().contains("只修改当前选中的对象"));
        assertTrue(gateway.request.getSystemPrompt().contains("保留所有 ID、卷号、章号和段落索引"));
        assertTrue(gateway.request.getSystemPrompt().contains("不要使用 Markdown 代码块"));
    }

    @Test
    void rejectsTruncatedResponseWithActionableGuidance() {
        // NCS-093: finish_reason=length 的失控长输出必须 fail-fast 并给出行动指引，
        // 而不是落进 JSON 解析失败的泛化错误（第二轮旅程 52k tokens/9.4min 案例）
        LlmGateway truncatedGateway = new LlmGateway() {
            @Override
            public LlmResponse complete(LlmRequest request) {
                return new LlmResponse("{\"targetType\":\"CHAPTER\",\"title\":\"候选", 10, 8190, "length", null);
            }

            @Override
            public void streamComplete(LlmRequest request, StreamHandler handler) {
            }
        };
        ContentRevisionAgent agent = new ContentRevisionAgent(truncatedGateway, TestPromptHelper.create());

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> agent.preview(TargetType.CHAPTER, "{\"id\":\"chapter-1\",\"title\":\"旧标题\"}", "修复矛盾"));
        assertTrue(e.getMessage().contains("截断"), e.getMessage());
        assertTrue(e.getMessage().contains("分段修改") || e.getMessage().contains("缩小修改范围"), e.getMessage());
    }

    @Test
    void rejectsBlankResponseAndBlankRequiredContent() {
        assertInvalidResponse("   ");
        assertInvalidResponse("""
                {"targetType":"WORLD","title":"候选","content":"  ",
                 "structuredJson":"{\\"id\\":\\"world-1\\"}","summary":"摘要"}
                """);
    }

    @Test
    void rejectsMalformedWrappedOrTrailingResponse() {
        assertInvalidResponse("not-json");
        assertInvalidResponse("""
                ```json
                {"targetType":"WORLD","title":"候选","content":"正文",
                 "structuredJson":"{\\"id\\":\\"world-1\\"}","summary":"摘要"}
                ```
                """);
        assertInvalidResponse(validResponse(TargetType.WORLD) + " {} ");
    }

    @Test
    void rejectsWrongTargetTypeAndInvalidStructuredJson() {
        RecordingGateway wrongType = new RecordingGateway(validResponse(TargetType.CHAPTER));
        ContentRevisionAgent wrongTypeAgent = new ContentRevisionAgent(wrongType, TestPromptHelper.create());
        assertThrows(IllegalStateException.class,
                () -> wrongTypeAgent.preview(TargetType.WORLD, "{\"id\":\"world-1\"}", "重写"));

        assertInvalidResponse("""
                {"targetType":"WORLD","title":"候选","content":"正文",
                 "structuredJson":"not-json","summary":"摘要"}
                """);
    }

    @Test
    void rejectsInvalidInputsBeforeCallingGateway() {
        RecordingGateway gateway = new RecordingGateway(validResponse(TargetType.WORLD));
        ContentRevisionAgent agent = new ContentRevisionAgent(gateway, TestPromptHelper.create());

        assertThrows(IllegalArgumentException.class, () -> agent.preview(null, "{}", "修改"));
        assertThrows(IllegalArgumentException.class, () -> agent.preview(TargetType.WORLD, " ", "修改"));
        assertThrows(IllegalArgumentException.class,
                () -> agent.preview(TargetType.WORLD, "not-json", "修改"));
        assertThrows(IllegalArgumentException.class, () -> agent.preview(TargetType.WORLD, "{}", " "));
        assertEquals(0, gateway.calls);
    }

    @Test
    void hasNoRepositoryDependencyOrMutationCapability() {
        assertFalse(java.util.Arrays.stream(ContentRevisionAgent.class.getDeclaredFields())
                .map(Field::getType)
                .anyMatch(NovelRepository.class::isAssignableFrom));
    }

    private static void assertInvalidResponse(String response) {
        ContentRevisionAgent agent = new ContentRevisionAgent(
                new RecordingGateway(response), TestPromptHelper.create());
        assertThrows(IllegalStateException.class,
                () -> agent.preview(TargetType.WORLD, "{\"id\":\"world-1\"}", "重写"));
    }

    private static String validResponse(TargetType targetType) {
        return """
                {"targetType":"%s","title":"修改候选","content":"更新后的正文",
                 "structuredJson":"{\\"id\\":\\"target-1\\",\\"title\\":\\"新标题\\"}",
                 "summary":"调整节奏并保留身份字段"}
                """.formatted(targetType.name());
    }

    private static final class RecordingGateway implements LlmGateway {
        private final String response;
        private LlmRequest request;
        private int calls;

        private RecordingGateway(String response) {
            this.response = response;
        }

        @Override
        public LlmResponse complete(LlmRequest request) {
            calls++;
            this.request = request;
            return new LlmResponse(response, 10, 10);
        }

        @Override
        public void streamComplete(LlmRequest request, StreamHandler handler) {
        }
    }
}
