package com.writing.novel.llm;

import com.fasterxml.jackson.databind.*;
import com.writing.novel.llm.client.*;
import com.writing.novel.llm.config.ModelConfig;
import com.writing.novel.llm.gateway.DefaultLlmGateway;
import com.writing.novel.telemetry.EventLog;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LlmFallbackTelemetryTest {
    @TempDir Path tmp;
    @BeforeEach void setup(){System.setProperty(EventLog.PATH_PROPERTY,tmp.resolve("events.jsonl").toString());}
    @AfterEach void cleanup(){System.clearProperty(EventLog.PATH_PROPERTY);}
    final ObjectMapper mapper=new ObjectMapper();
    DefaultLlmGateway gateway() {
        var p=LlmProviderFallbackTest.properties();
        p.getProviders().get("b").setInputPriceYuanPerKTokens(2);
        p.getProviders().get("b").setOutputPriceYuanPerKTokens(4);
        return new DefaultLlmGateway(p,new LlmClient(){
            public LlmResponse complete(ModelConfig c,LlmRequest r){
                if(c.getModel().equals("model-a"))throw LlmProviderFallbackTest.down();
                return new LlmResponse("backup",1000,500);
            }
            public void streamComplete(ModelConfig c,LlmRequest r,StreamHandler h){
                if(c.getModel().equals("model-a")){h.onError(LlmProviderFallbackTest.down());return;}
                h.onToken("backup");h.onComplete(new LlmResponse("backup",1000,500));
            }
        });
    }
    void verify(LlmResponse response) {
        JsonNode tree=mapper.valueToTree(response);
        assertEquals("b",tree.path("execution").path("providerId").asText());
        assertEquals("model-b",tree.path("execution").path("model").asText());
        assertEquals(2,tree.path("execution").path("inputPrice").asDouble());
        assertEquals(4,tree.path("execution").path("outputPrice").asDouble());
        assertEquals(1000,response.getPromptTokens());assertEquals(500,response.getCompletionTokens());
    }
    @Test void completeReturnsActualProviderPriceWithoutCredentials() throws Exception {
        var response=gateway().complete(LlmProviderFallbackTest.REQUEST);verify(response);
        com.writing.novel.telemetry.EventLog.awaitFlush();
        String events=Files.readString(tmp.resolve("events.jsonl"));
        assertTrue(events.contains("LLM_FALLBACK"));assertTrue(events.contains("LLM_PROVIDER_UNAVAILABLE"));
        assertTrue(events.contains("\"from\":\"a\"") && events.contains("\"to\":\"b\""));
        assertFalse(events.contains("test-key"));assertFalse(mapper.writeValueAsString(response).contains("test-key"));
    }
    @Test void streamReturnsActualProviderUsageThroughCompletion() {
        var responses=new ArrayList<LlmResponse>();
        gateway().streamComplete(LlmProviderFallbackTest.REQUEST,new StreamHandler(){
            public void onToken(String token){}
            public void onComplete(){}
            public void onComplete(LlmResponse r){responses.add(r);}
            public void onError(Throwable e){fail(e);}
        });
        assertEquals(1,responses.size());verify(responses.get(0));
    }
}
