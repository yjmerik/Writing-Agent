package com.writing.novel.agent;

import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.prompt.PromptTemplateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * p37-11：候选模型逐项落实契约。
 * 三意见只实现两项→第三项必须 UNMET+原因；重复/未知 ID/无证据不能 APPLIED_SYSTEM；
 * 无要求时结果为空（兼容旧调用）；现有五字段契约保持；模板变量名不改。
 */
class RevisionEvidenceContractTest {

    private static final String CURRENT_JSON = "{\"id\":\"ch-1\",\"title\":\"第四章\",\"content\":\"正文\"}";

    private StubGateway gateway;
    private ContentRevisionAgent agent;

    static class StubGateway implements LlmGateway {
        String response;

        @Override
        public LlmResponse complete(LlmRequest request) {
            return new LlmResponse(response, 10, 10);
        }

        @Override
        public void streamComplete(LlmRequest request, com.writing.novel.llm.client.StreamHandler handler) {
            // no-op
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        gateway = new StubGateway();
        PromptTemplateService promptService = new PromptTemplateService();
        promptService.loadTemplates();
        agent = new ContentRevisionAgent(gateway, promptService);
    }

    private ContentRevisionAgent.RevisionCandidate preview(String llmJson, String instructions) {
        gateway.response = llmJson;
        return agent.preview(ContentRevisionAgent.TargetType.CHAPTER, CURRENT_JSON, instructions);
    }

    private static final String CANDIDATE_HEAD =
            "{\"targetType\":\"CHAPTER\",\"title\":\"修订章\",\"content\":\"修订正文\",\"" +
                    "structuredJson\":\"{\\\"id\\\":\\\"ch-1\\\"}\",\"summary\":\"改了\",\"directiveResults\":";

    @Test
    void 三意见只实现两项时第三项必须UNMET带原因() {
        String instructions = """
                正常指令。
                【作者要求-1】[D1 rev2] 每章一场雨夜对话
                【作者要求-2】[D2 rev1] 埋旧发夹线索
                【作者要求-3】[D3 rev1] 多用短句""";
        gateway.response = CANDIDATE_HEAD + "[" +
                "{\"directiveId\":\"D1\",\"directiveRevision\":2,\"status\":\"APPLIED_SYSTEM\",\"quote\":\"雨夜对话原文\",\"anchor\":\"1\"}," +
                "{\"directiveId\":\"D2\",\"directiveRevision\":1,\"status\":\"APPLIED_SYSTEM\",\"quote\":\"旧发夹\",\"anchor\":\"2\"}," +
                "{\"directiveId\":\"D3\",\"directiveRevision\":1,\"status\":\"UNMET\",\"reason\":\"句子长度未调整\"}]}";
        ContentRevisionAgent.RevisionCandidate candidate = preview(gateway.response, instructions);

        assertEquals(3, candidate.directiveResults().size());
        assertEquals("UNMET", candidate.directiveResults().get(2).status());
        assertEquals("句子长度未调整", candidate.directiveResults().get(2).reason());
        // 旧五字段保持
        assertEquals("修订章", candidate.title());
        assertEquals("CHAPTER", candidate.targetType().name());
    }

    @Test
    void 遗漏意见_重复ID_未知ID一律拒绝() {
        String instructions = """
                指令。
                【作者要求-1】[D-1 rev1] 要求甲
                【作者要求-2】[D2 rev1] 要求乙""";
        // 遗漏 D2
        gateway.response = CANDIDATE_HEAD + "[" +
                "{\"directiveId\":\"D1\",\"directiveRevision\":1,\"status\":\"APPLIED_SYSTEM\",\"quote\":\"q\"}]}";
        assertThrows(IllegalStateException.class, () -> preview(gateway.response, instructions));
        // 重复 ID
        gateway.response = CANDIDATE_HEAD + "[" +
                "{\"directiveId\":\"D1\",\"directiveRevision\":1,\"status\":\"UNMET\",\"reason\":\"r\"}," +
                "{\"directiveId\":\"D1\",\"directiveRevision\":1,\"status\":\"UNMET\",\"reason\":\"r\"}]}";
        assertThrows(IllegalStateException.class, () -> preview(gateway.response, instructions));
        // 未知 ID
        gateway.response = CANDIDATE_HEAD + "[" +
                "{\"directiveId\":\"DX\",\"directiveRevision\":1,\"status\":\"UNMET\",\"reason\":\"r\"}]}";
        assertThrows(IllegalStateException.class, () -> preview(gateway.response, instructions));
        // revision 不匹配
        gateway.response = CANDIDATE_HEAD + "[" +
                "{\"directiveId\":\"D1\",\"directiveRevision\":9,\"status\":\"UNMET\",\"reason\":\"r\"}]}";
        assertThrows(IllegalStateException.class, () -> preview(gateway.response, instructions));
    }

    @Test
    void APPLIED无quote或UNMET无reason拒绝() {
        String instructions = "指令。【作者要求-1】[D-1 rev1] 要求甲";
        gateway.response = CANDIDATE_HEAD + "[" +
                "{\"directiveId\":\"D1\",\"directiveRevision\":1,\"status\":\"APPLIED_SYSTEM\",\"quote\":\"\"}]}";
        assertThrows(IllegalStateException.class, () -> preview(gateway.response, instructions),
                "APPLIED_SYSTEM 无 quote 拒绝");
        gateway.response = CANDIDATE_HEAD + "[" +
                "{\"directiveId\":\"D1\",\"directiveRevision\":1,\"status\":\"UNMET\",\"reason\":null}]}";
        assertThrows(IllegalStateException.class, () -> preview(gateway.response, instructions),
                "UNMET 空原因拒绝");
    }

    @Test
    void 无要求时结果清空且现有契约保持() throws Exception {
        // 旧调用：instructions 无编号行 → LLM 返回了 results 也清空
        gateway.response = "{\"targetType\":\"CHAPTER\",\"title\":\"t\",\"content\":\"c\"," +
                "\"structuredJson\":\"{}\",\"summary\":\"s\",\"directiveResults\":" +
                "[{\"directiveId\":\"DX\",\"directiveRevision\":1,\"status\":\"UNMET\",\"reason\":\"r\"}]}";
        ContentRevisionAgent.RevisionCandidate candidate = preview(gateway.response, "普通指令");
        assertTrue(candidate.directiveResults().isEmpty(), "无要求结果为空");
        assertEquals("t", candidate.title());
        // 现有 ContentRevisionAgent 对应测试（同款 stub 契约）仍然通过——由全量回归覆盖
        // 显式传入已知集合的路径：
        gateway.response = CANDIDATE_HEAD + "[" +
                "{\"directiveId\":\"D1\",\"directiveRevision\":3,\"status\":\"APPLIED_SYSTEM\",\"quote\":\"q\",\"anchor\":\"2\"}]}";
        ContentRevisionAgent.RevisionCandidate explicit = agent.preview(
                ContentRevisionAgent.TargetType.CHAPTER, CURRENT_JSON, "指令",
                List.of(new ContentRevisionAgent.KnownDirective("D1", 3)));
        assertEquals(1, explicit.directiveResults().size());
        assertEquals("q", explicit.directiveResults().get(0).quote());
    }
}
