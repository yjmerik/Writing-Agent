package com.writing.novel.llm;

import com.writing.novel.llm.client.*;
import com.writing.novel.llm.gateway.*;
import com.writing.novel.telemetry.EventLog;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Real client + named routing + observability, using only local HTTP servers. */
class LlmProviderIntegrationTest {
    @TempDir Path tmp;
    @AfterEach void cleanup(){System.clearProperty(EventLog.PATH_PROPERTY);}
    @Test void http402SwitchesToBackupAndSubsequentStreamSkipsPrimary() throws Exception {
        System.setProperty(EventLog.PATH_PROPERTY,tmp.resolve("events.jsonl").toString());
        try(var primary=new MockWebServer();var backup=new MockWebServer()) {
            primary.start();backup.start();
            var map=LlmProviderConfigurationTest.config();
            map.put("llm.providers.a.base-url",primary.url("/v1").toString());
            map.put("llm.providers.b.base-url",backup.url("/v1").toString());
            map.put("llm.providers.a.streaming",false);map.put("llm.providers.b.streaming",false);
            var p=LlmProviderConfigurationTest.bind(map);p.validateKeys();
            primary.enqueue(new MockResponse().setResponseCode(402).setBody("{\"error\":{\"code\":\"insufficient_quota\"}}"));
            backup.enqueue(new MockResponse().setBody("{\"choices\":[{\"message\":{\"content\":\"backup full\"}}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}"));
            backup.enqueue(new MockResponse().setBody("data: {\"choices\":[{\"delta\":{\"content\":\"backup stream\"}}],\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":6}}\n\ndata: [DONE]\n\n"));
            var g=new ObservedLlmGateway(new DefaultLlmGateway(p,new OpenAiCompatibleClient()),p,null);
            var response=g.complete(LlmProviderFallbackTest.REQUEST);
            assertEquals("backup full",response.getContent());assertEquals("b",response.getExecution().providerId());
            var tokens=new StringBuilder();var completed=new ArrayList<LlmResponse>();
            g.streamComplete(LlmProviderFallbackTest.REQUEST,new StreamHandler(){
                public void onToken(String token){tokens.append(token);}
                public void onComplete(){fail("missing detailed completion");}
                public void onComplete(LlmResponse r){completed.add(r);}
                public void onError(Throwable error){fail(error);}
            });
            assertEquals("backup stream",tokens.toString());assertEquals(1,completed.size());
            assertEquals("b",completed.get(0).getExecution().providerId());assertEquals(11,completed.get(0).getPromptTokens());
            assertEquals(1,primary.getRequestCount());assertEquals(2,backup.getRequestCount());
            assertEquals("Bearer test-key-a",primary.takeRequest(1,TimeUnit.SECONDS).getHeader("Authorization"));
            var backupRequest=backup.takeRequest(1,TimeUnit.SECONDS);
            assertEquals("Bearer test-key-b",backupRequest.getHeader("Authorization"));
            assertEquals("/v1/chat/completions",backupRequest.getPath());
        }
    }
    @Test void aggregatedSseDiscardsPartialFailedAttemptAndRetriesWholeRequestOnBackup() throws Exception {
        try(var primary=new MockWebServer();var backup=new MockWebServer()) {
            primary.start();backup.start();
            var map=LlmProviderConfigurationTest.config();
            map.put("llm.providers.a.base-url",primary.url("/v1").toString());
            map.put("llm.providers.b.base-url",backup.url("/v1").toString());
            var p=LlmProviderConfigurationTest.bind(map);p.validateKeys();
            primary.enqueue(new MockResponse().setBody("data: {\"choices\":[{\"delta\":{\"content\":\"discard partial\"}}]}\n\n"));
            backup.enqueue(new MockResponse().setBody("data: {\"choices\":[{\"delta\":{\"content\":\"complete backup\"}}]}\n\ndata: [DONE]\n\n"));
            var r=new DefaultLlmGateway(p,new OpenAiCompatibleClient()).complete(LlmProviderFallbackTest.REQUEST);
            assertEquals("complete backup",r.getContent());assertEquals("b",r.getExecution().providerId());
            assertEquals(1,primary.getRequestCount());assertEquals(1,backup.getRequestCount());
        }
    }
}
