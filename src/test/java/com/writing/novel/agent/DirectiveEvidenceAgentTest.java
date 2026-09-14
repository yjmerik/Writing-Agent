package com.writing.novel.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmTaskType;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.prompt.PromptTemplateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * p37-15：生成后作者要求核对器契约。
 * 固定 Stub 覆盖：全三项落实/遗漏/伪造引文/空返回；核对器失败保持未验证而非已落实；
 * 未知/重复 ID 不采信；模板固定变量 chapterJson/directivesJson；只读不写正文。
 */
class DirectiveEvidenceAgentTest {

    private static final String CHAPTER_JSON =
            "{\"chapterIndex\":4,\"title\":\"第四章\",\"content\":\"雨夜里，他把旧发夹放回袖中，与她对峙。\\n第二段无对话。\"}";
    private static final String DIRECTIVES_JSON =
            "[{\"directiveId\":\"D1\",\"revision\":2,\"kind\":\"HARD\",\"fromChapter\":null,\"toChapter\":null,\"content\":\"每章一场雨夜对话\"}," +
             "{\"directiveId\":\"D2\",\"revision\":1,\"kind\":\"ONCE\",\"fromChapter\":4,\"toChapter\":4,\"content\":\"埋旧发夹线索\"}," +
             "{\"directiveId\":\"D3\",\"revision\":1,\"kind\":\"PREFERENCE\",\"fromChapter\":4,\"toChapter\":null,\"content\":\"多用短句\"}]";

    private PromptTemplateService promptService;
    private StubGateway gateway;

    static class StubGateway implements LlmGateway {
        String response;
        LlmRequest lastRequest;

        @Override
        public LlmResponse complete(LlmRequest request) {
            this.lastRequest = request;
            return new LlmResponse(response, 10, 10);
        }

        @Override
        public void streamComplete(LlmRequest request, StreamHandler handler) {
            // no-op
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        gateway = new StubGateway();
        promptService = new PromptTemplateService();
        promptService.loadTemplates();
    }

    private DirectiveEvidenceAgent agent() {
        return new DirectiveEvidenceAgent(gateway, promptService);
    }

    @Test
    void 模板渲染固定变量且逐字quote采信为已落实() {
        gateway.response = "[{\"directiveId\":\"D1\",\"revision\":2,\"status\":\"APPLIED_SYSTEM\"," +
                "\"quote\":\"雨夜里，他把旧发夹放回袖中\",\"anchor\":\"1\"}," +
                "{\"directiveId\":\"D2\",\"revision\":1,\"status\":\"APPLIED_SYSTEM\"," +
                "\"quote\":\"旧发夹放回袖中\",\"anchor\":\"1\"}," +
                "{\"directiveId\":\"D3\",\"revision\":1,\"status\":\"UNMET\",\"reason\":\"通篇非短句\"}]";
        List<com.writing.novel.domain.AuthorDirective.Evidence> result =
                agent().evaluate("p1", 4, 3, CHAPTER_JSON, DIRECTIVES_JSON);

        // 模板变量固定
        String prompt = gateway.lastRequest.getSystemPrompt();
        assertTrue(prompt.contains("第四章") && prompt.contains("每章一场雨夜对话"),
                "chapterJson/directivesJson 必须注入");
        assertTrue(prompt.contains("{{") == false || !prompt.contains("{{chapterJson}}"),
                "变量必须完成插值");
        assertEquals(LlmTaskType.GENERAL, gateway.lastRequest.getTaskType());

        assertEquals(3, result.size(), "恰好覆盖三个意见");
        assertEquals(com.writing.novel.domain.AuthorDirective.EvidenceStatus.APPLIED_SYSTEM,
                result.get(0).status());
        assertEquals(com.writing.novel.domain.AuthorDirective.EvidenceStatus.APPLIED_SYSTEM,
                result.get(1).status());
        assertEquals(com.writing.novel.domain.AuthorDirective.EvidenceStatus.UNMET,
                result.get(2).status());
    }

    @Test
    void 伪造引文降级UNMET且遗漏意见补UNMET() {
        gateway.response = "[{\"directiveId\":\"D1\",\"revision\":2,\"status\":\"APPLIED_SYSTEM\"," +
                "\"quote\":\"正文中不存在的句子\"}," +
                "{\"directiveId\":\"D2\",\"revision\":1,\"status\":\"APPLIED_SYSTEM\"," +
                "\"quote\":\"旧发夹放回袖中\"}]";
        List<com.writing.novel.domain.AuthorDirective.Evidence> result =
                agent().evaluate("p1", 4, 3, CHAPTER_JSON, DIRECTIVES_JSON);

        assertEquals(3, result.size(), "遗漏的 D3 必须补 UNMET");
        assertEquals(com.writing.novel.domain.AuthorDirective.EvidenceStatus.UNMET,
                result.get(0).status(), "伪造引文降级 UNMET");
        assertTrue(result.get(0).reason().contains("证据不足") || result.get(0).reason().contains("引用"));
        assertEquals(com.writing.novel.domain.AuthorDirective.EvidenceStatus.APPLIED_SYSTEM,
                result.get(1).status());
        assertEquals(com.writing.novel.domain.AuthorDirective.EvidenceStatus.UNMET,
                result.get(2).status());
        assertEquals("核对器未覆盖该要求", result.get(2).reason());
    }

    @Test
    void 未知与重复ID不采信且空返回失败保持未验证() {
        // 空/非 JSON 返回：核对器失败——抛出而非当作已落实
        gateway.response = "";
        assertThrows(IllegalStateException.class,
                () -> agent().evaluate("p1", 4, 3, CHAPTER_JSON, DIRECTIVES_JSON),
                "空返回必须失败（保持未验证）");
        gateway.response = "我核对了一下，都没问题。";
        assertThrows(IllegalStateException.class,
                () -> agent().evaluate("p1", 4, 3, CHAPTER_JSON, DIRECTIVES_JSON),
                "非 JSON 返回必须失败");

        // 未知与重复 ID 不采信，遗漏补 UNMET
        gateway.response = "[{\"directiveId\":\"DX\",\"revision\":1,\"status\":\"APPLIED_SYSTEM\",\"quote\":\"雨夜\"}," +
                "{\"directiveId\":\"D1\",\"revision\":2,\"status\":\"APPLIED_SYSTEM\",\"quote\":\"雨夜\"}," +
                "{\"directiveId\":\"D1\",\"revision\":2,\"status\":\"APPLIED_SYSTEM\",\"quote\":\"雨夜\"}]";
        List<com.writing.novel.domain.AuthorDirective.Evidence> result =
                agent().evaluate("p1", 4, 3, CHAPTER_JSON, DIRECTIVES_JSON);
        assertEquals(3, result.size());
        assertTrue(result.stream().noneMatch(e -> e.directiveId().equals("DX")), "未知 ID 不采信");
        assertEquals(1, result.stream().filter(e -> e.directiveId().equals("D1")).count(), "重复 ID 去重");
        assertTrue(result.stream().filter(e -> e.directiveId().equals("D2"))
                .allMatch(e -> e.status() == com.writing.novel.domain.AuthorDirective.EvidenceStatus.UNMET),
                "被挤占的意见补 UNMET");
    }

    @Test
    void 空意见列表直接返回空且核对器不写正文() {
        List<com.writing.novel.domain.AuthorDirective.Evidence> result =
                agent().evaluate("p1", 4, 3, CHAPTER_JSON, "[]");
        assertTrue(result.isEmpty());
        // 输入非法拒绝
        assertThrows(IllegalArgumentException.class,
                () -> agent().evaluate("p1", 4, 3, null, DIRECTIVES_JSON));
        assertThrows(IllegalArgumentException.class,
                () -> agent().evaluate("p1", 4, 3, CHAPTER_JSON, "{\"not\":\"array\"}"));
    }
}
