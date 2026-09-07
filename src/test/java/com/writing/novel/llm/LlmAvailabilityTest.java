package com.writing.novel.llm;

import com.writing.novel.llm.client.*;
import com.writing.novel.llm.config.ModelConfig;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class LlmAvailabilityTest {
    MockWebServer server;
    OpenAiCompatibleClient client = new OpenAiCompatibleClient();
    LlmRequest request = LlmRequest.builder().systemPrompt("system").userMessage("user").build();
    @BeforeEach void start() throws Exception { server = new MockWebServer(); server.start(); }
    @AfterEach void stop() throws Exception { Thread.interrupted(); server.shutdown(); }
    ModelConfig config(boolean stream) {
        var c=new ModelConfig();c.setBaseUrl(server.url("/v1").toString());c.setApiKey("secret-key");
        c.setModel("test-model");c.setStreaming(stream);c.setSingleAttempt(true);c.setRequestTimeoutSeconds(1);return c;
    }
    void unavailable(Runnable call) {
        var e=assertThrows(RuntimeException.class,call::run);
        assertEquals("LlmUnavailableException",e.getClass().getSimpleName());
        assertFalse(e.toString().contains("secret-key"));
        assertFalse(e.toString().contains("private-body"));
    }
    @ParameterizedTest @ValueSource(ints={401,402,403,404,408,429,500,502,503})
    void serviceStatusesFailOnceAndAreTypedForBothCompleteModes(int status) {
        for(boolean stream:List.of(false,true)) {
            server.enqueue(new MockResponse().setResponseCode(status).setBody("private-body secret-key"));
            unavailable(()->client.complete(config(stream),request));
        }
        assertEquals(2,server.getRequestCount());
    }
    @Test void normalBadRequestDoesNotBecomeUnavailableOrExposeBody() {
        server.enqueue(new MockResponse().setResponseCode(400).setBody("private-body secret-key"));
        var e=assertThrows(RuntimeException.class,()->client.complete(config(false),request));
        assertNotEquals("LlmUnavailableException",e.getClass().getSimpleName());
        assertFalse(e.getMessage().contains("private-body"));
    }
    @Test void contentFilterTakesPrecedenceOver403AndSseError() {
        for(boolean stream:List.of(false,true)) {
            server.enqueue(new MockResponse().setResponseCode(403).setBody("{\"error\":{\"code\":\"content_filter\"}}"));
            assertThrows(ContentFilterException.class,()->client.complete(config(stream),request));
        }
        server.enqueue(new MockResponse().setBody("data: {\"error\":{\"code\":\"content_filter\"}}\n\n"));
        assertThrows(ContentFilterException.class,()->client.complete(config(true),request));
    }
    @Test void sseErrorAndAbruptEndAndEmptySuccessAreUnavailable() {
        for(String body:List.of("data: {\"error\":{\"code\":\"insufficient_quota\"}}\n\n", "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n", "data: [DONE]\n\n")) {
            server.enqueue(new MockResponse().setBody(body));unavailable(()->client.complete(config(true),request));
        }
        server.enqueue(new MockResponse().setBody("{\"choices\":[{\"message\":{\"content\":\" \"}}]}"));
        unavailable(()->client.complete(config(false),request));
    }
    @Test void sseSupportsDataWithoutSpaceAndFinishReasonAndUsage() {
        server.enqueue(new MockResponse().setBody("data:{\"choices\":[{\"delta\":{\"content\":\"你好\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":2}}\n\n"));
        var r=client.complete(config(true),request);assertEquals("你好",r.getContent());assertEquals(7,r.getPromptTokens());
    }
    @Test void timeoutIsBoundedAndDoesNotRetry() {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(3),()->unavailable(()->client.complete(config(false),request)));
        assertEquals(1,server.getRequestCount());
    }
    @Test void interruptionStopsBeforeNetworkAndKeepsFlag() {
        Thread.currentThread().interrupt();
        assertThrows(CancellationException.class,()->client.complete(config(false),request));
        assertTrue(Thread.currentThread().isInterrupted());assertEquals(0,server.getRequestCount());
    }
    @Test void callbackErrorIsNotTreatedAsProviderFailure() {
        server.enqueue(new MockResponse().setBody("data: {\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}\n\ndata: [DONE]\n\n"));
        var problem=new IllegalStateException("consumer");
        var events=new ArrayList<String>();
        var result=assertThrows(IllegalStateException.class,()->client.streamComplete(config(true),request,new StreamHandler(){
            public void onToken(String s){throw problem;}
            public void onComplete(){events.add("complete");}
            public void onError(Throwable e){events.add("error");}
        }));
        assertSame(problem,result);assertTrue(events.isEmpty());
    }
    @Test void callbackTransportErrorIsTypedAndTerminalOnce() {
        server.enqueue(new MockResponse().setResponseCode(503));
        var errors=new ArrayList<Throwable>();var tokens=new ArrayList<String>();
        client.streamComplete(config(true),request,new StreamHandler(){
            public void onToken(String s){tokens.add(s);}
            public void onComplete(){fail("unexpected complete");}
            public void onError(Throwable e){errors.add(e);}
        });
        assertEquals(1,errors.size());assertEquals("LlmUnavailableException",errors.get(0).getClass().getSimpleName());assertTrue(tokens.isEmpty());
    }
    @Test void streamingCompletionCarriesActualUsageWithoutBreakingLegacyCallbacks() {
        server.enqueue(new MockResponse().setBody("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}],\"usage\":{\"prompt_tokens\":1000,\"completion_tokens\":500}}\n\ndata: [DONE]\n\n"));
        var received=new ArrayList<LlmResponse>();
        client.streamComplete(config(true),request,new StreamHandler(){
            public void onToken(String token){}
            public void onComplete(){}
            public void onComplete(LlmResponse response){received.add(response);}
            public void onError(Throwable e){fail(e);}
        });
        assertEquals(1,received.size());assertEquals("ok",received.get(0).getContent());
        assertEquals(1000,received.get(0).getPromptTokens());assertEquals(500,received.get(0).getCompletionTokens());
    }

    @Test void authAndQuotaSignalsTakePrecedenceOverGenericInvalidRequestType() {
        for(int status:List.of(401,403,429,400)) {
            String code=status==400 ? "insufficient_quota" : "invalid_api_key";
            server.enqueue(new MockResponse().setResponseCode(status).setBody("{\"error\":{\"type\":\"invalid_request_error\",\"code\":\""+code+"\"}}"));
            unavailable(()->client.complete(config(false),request));
        }
    }
    @Test void retryAfterZeroMustNotCauseAnInvisibleSecondHttpAttempt() {
        server.enqueue(new MockResponse().setResponseCode(503).setHeader("Retry-After","0"));
        server.enqueue(new MockResponse().setBody("{\"choices\":[{\"message\":{\"content\":\"unexpected retry\"}}]}"));
        unavailable(()->client.complete(config(false),request));
        assertEquals(1,server.getRequestCount());
    }

}
