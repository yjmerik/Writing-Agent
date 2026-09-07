package com.writing.novel.llm;

import com.writing.novel.llm.client.*;
import com.writing.novel.llm.config.ModelConfig;
import com.writing.novel.llm.gateway.DefaultLlmGateway;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.concurrent.CancellationException;
import static org.junit.jupiter.api.Assertions.*;

class LlmStreamingFallbackTest {
    final List<String> events=new ArrayList<>(), attempts=new ArrayList<>();
    StreamHandler handler=new StreamHandler() {
        public void onToken(String s){events.add(s);}
        public void onComplete(){events.add("complete");}
        public void onError(Throwable e){events.add("error:"+e.getClass().getSimpleName());}
    };
    DefaultLlmGateway gateway(BiConsumer<ModelConfig,StreamHandler> action) {
        return new DefaultLlmGateway(LlmProviderFallbackTest.properties(),new LlmClient(){
            public LlmResponse complete(ModelConfig c,LlmRequest r){throw new UnsupportedOperationException();}
            public void streamComplete(ModelConfig c,LlmRequest r,StreamHandler h){attempts.add(c.getModel());action.accept(c,h);}
        });
    }
    @Test void failureBeforeTokenFallsBackAndOnlySuccessfulTerminalEscapes() {
        var g=gateway((c,h)->{if(c.getModel().equals("model-a")){h.onError(LlmProviderFallbackTest.down());return;}
            h.onToken("backup");h.onComplete();h.onComplete();h.onError(LlmProviderFallbackTest.down());h.onToken("late");});
        g.streamComplete(LlmProviderFallbackTest.REQUEST,handler);
        assertEquals(List.of("model-a","model-b"),attempts);assertEquals(List.of("backup","complete"),events);
    }
    @Test void partialFailureDoesNotAppendNewProviderButCoolsTheFailedOne() {
        var g=gateway((c,h)->{h.onToken(c.getModel());if(c.getModel().equals("model-a"))h.onError(LlmProviderFallbackTest.down());else h.onComplete();});
        g.streamComplete(LlmProviderFallbackTest.REQUEST,handler);
        assertEquals(List.of("model-a"),attempts);assertEquals(List.of("model-a","error:LlmUnavailableException"),events);
        g.streamComplete(LlmProviderFallbackTest.REQUEST,handler);
        assertEquals(List.of("model-a","model-b"),attempts);
    }
    @Test void thrownTransportFailureBeforeTokensCanSwitch() {
        var g=gateway((c,h)->{if(c.getModel().equals("model-a"))throw LlmProviderFallbackTest.down();h.onToken("ok");h.onComplete();});
        g.streamComplete(LlmProviderFallbackTest.REQUEST,handler);assertEquals(List.of("ok","complete"),events);
    }
    @Test void filteredAndCancelledAndProgrammingFailuresDoNotSwitch() {
        for(RuntimeException e:List.of(new ContentFilterException("filter"),new CancellationException(),new IllegalArgumentException("input"))) {
            attempts.clear();events.clear();gateway((c,h)->h.onError(e)).streamComplete(LlmProviderFallbackTest.REQUEST,handler);
            assertEquals(List.of("model-a"),attempts);assertEquals(List.of("error:"+e.getClass().getSimpleName()),events);
        }
    }
    @Test void allFailedEmitsOneTerminalAndAllCoolingMakesNoNewAttempt() {
        var g=gateway((c,h)->h.onError(LlmProviderFallbackTest.down()));
        g.streamComplete(LlmProviderFallbackTest.REQUEST,handler);assertEquals(1,events.size());assertEquals(2,attempts.size());
        g.streamComplete(LlmProviderFallbackTest.REQUEST,handler);assertEquals(2,events.size());assertEquals(2,attempts.size());
    }
    @Test void consumerExceptionIsRethrownAndDoesNotCoolProviderEvenIfClientCatchesIt() {
        var bad=new IllegalStateException("consumer");
        var g=gateway((c,h)->{try{h.onToken("token");h.onComplete();}catch(RuntimeException e){h.onError(e);}});
        var throwing=new StreamHandler(){
            public void onToken(String s){throw bad;}
            public void onComplete(){fail("complete");}
            public void onError(Throwable e){fail("error");}
        };
        assertSame(bad,assertThrows(IllegalStateException.class,()->g.streamComplete(LlmProviderFallbackTest.REQUEST,throwing)));
        g.streamComplete(LlmProviderFallbackTest.REQUEST,handler);assertEquals(List.of("model-a","model-a"),attempts);
    }
    @Test void interruptedThreadEmitsCancellationWithoutCallingClient() {
        try {Thread.currentThread().interrupt();
            gateway((c,h)->fail("unexpected call")).streamComplete(LlmProviderFallbackTest.REQUEST,handler);
            assertEquals(List.of("error:CancellationException"),events);assertTrue(attempts.isEmpty());
        }finally{Thread.interrupted();}
    }
    @Test void missingTerminalIsFailureAndNeverSilentlySucceeds() {
        gateway((c,h)->{}).streamComplete(LlmProviderFallbackTest.REQUEST,handler);
        assertEquals(1,events.size());assertTrue(events.get(0).startsWith("error:"));assertEquals(2,attempts.size());
    }
}
