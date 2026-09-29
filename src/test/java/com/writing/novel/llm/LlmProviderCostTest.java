package com.writing.novel.llm;

import com.fasterxml.jackson.databind.*;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.llm.gateway.ObservedLlmGateway;
import com.writing.novel.telemetry.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LlmProviderCostTest {
    @TempDir Path tmp;
    @BeforeEach void setup(){System.setProperty(EventLog.PATH_PROPERTY,tmp.resolve("events.jsonl").toString());}
    @AfterEach void cleanup(){System.clearProperty(EventLog.PATH_PROPERTY);}
    @Test void actualProviderPriceIsUsedForCompleteAndStream() throws Exception {
        var response=new LlmResponse("backup",1000,500,new LlmResponse.Execution("b","model-b",2,4));
        var delegate=new LlmGateway(){
            public LlmResponse complete(LlmRequest r){return response;}
            public void streamComplete(LlmRequest r,StreamHandler h){h.onToken("backup");h.onComplete(response);}
        };
        var g=new ObservedLlmGateway(delegate,LlmProviderFallbackTest.properties(),new CostBudgetMonitor());
        assertSame(response,g.complete(LlmProviderFallbackTest.REQUEST));
        var received=new ArrayList<LlmResponse>();
        g.streamComplete(LlmProviderFallbackTest.REQUEST,new StreamHandler(){
            public void onToken(String s){}
            public void onComplete(){}
            public void onComplete(LlmResponse r){received.add(r);}
            public void onError(Throwable e){fail(e);}
        });
        assertEquals(List.of(response),received);
        com.writing.novel.telemetry.EventLog.awaitFlush();
        var lines=Files.readAllLines(tmp.resolve("events.jsonl"));assertEquals(2,lines.size());
        for(String line:lines){
            JsonNode event=new ObjectMapper().readTree(line);
            assertEquals(4,event.path("estimatedCostCny").asDouble());
            assertEquals("b",event.path("provider").asText());assertEquals("model-b",event.path("model").asText());
            assertEquals(1000,event.path("promptTokens").asInt());assertEquals(500,event.path("completionTokens").asInt());
        }
    }
}
