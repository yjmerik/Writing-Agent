package com.writing.novel.llm;

import com.writing.novel.llm.client.*;
import com.writing.novel.llm.config.*;
import com.writing.novel.llm.gateway.DefaultLlmGateway;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiFunction;
import static org.junit.jupiter.api.Assertions.*;

class LlmProviderFallbackTest {
    static final LlmRequest REQUEST=LlmRequest.builder().userMessage("test").maxTokens(1000).temperature(0.2).build();
    static LlmProperties properties() {
        var p=LlmProviderConfigurationTest.bind(LlmProviderConfigurationTest.config());
        p.getProviders().get("a").setStyleGuide("style-a");
        p.getProviders().get("a").setThinkingEnabled(false);
        p.getProviders().get("a").setMaxTokens(8000);
        p.getProviders().get("a").setInputPriceYuanPerKTokens(3);
        return p;
    }
    static LlmUnavailableException down() { return new LlmUnavailableException(LlmUnavailableException.Reason.SERVER,503); }
    static LlmClient client(BiFunction<ModelConfig,LlmRequest,LlmResponse> action) {
        return new LlmClient() {
            public LlmResponse complete(ModelConfig c,LlmRequest r) { return action.apply(c,r); }
            public void streamComplete(ModelConfig c,LlmRequest r,StreamHandler h) { throw new UnsupportedOperationException(); }
        };
    }
    @Test void failureFallsBackAndNextRequestSkipsCoolingPrimary() {
        var attempts=new ArrayList<String>();var p=properties();
        var g=new DefaultLlmGateway(p,client((c,r)->{
            attempts.add(c.getModel());if(c.getModel().equals("model-a"))throw down();
            return new LlmResponse("backup",1,2);
        }));
        assertEquals("backup",g.complete(REQUEST).getContent());
        assertEquals("backup",g.complete(REQUEST).getContent());
        assertEquals(List.of("model-a","model-b","model-b"),attempts);
    }
    @Test void threeProvidersAreTriedInOrderAndAllCoolingFailFast() {
        var p=properties();p.getProviders().put("c",p.getProviders().get("b"));
        p.getRouting().setDefaultProviders(List.of("a","b","c"));var attempts=new ArrayList<String>();
        var g=new DefaultLlmGateway(p,client((c,r)->{attempts.add(c.getModel());throw down();}));
        var e=assertThrows(RuntimeException.class,()->g.complete(REQUEST));
        assertTrue(e.getMessage().contains("a"));assertTrue(e.getMessage().contains("SERVER"));
        assertFalse(e.toString().contains("test-key"));assertEquals(3,attempts.size());
        assertThrows(RuntimeException.class,()->g.complete(REQUEST));assertEquals(3,attempts.size());
    }
    @Test void requestResolutionPreservesFullConfigAndDoesNotMutateSharedDefaults() {
        var p=properties();var g=new DefaultLlmGateway(p,client((c,r)->{
            assertEquals("style-a",c.getStyleGuide());assertFalse(c.isThinkingEnabled());
            assertEquals(64000,c.getContextWindow());assertEquals(3,c.getInputPriceYuanPerKTokens());
            assertEquals(8000,c.getMaxTokens());assertEquals(0.2,c.getTemperature());assertTrue(c.isSingleAttempt());
            return new LlmResponse("ok",1,1);
        }));
        assertEquals("ok",g.complete(REQUEST).getContent());
        assertEquals(0.7,p.getProviders().get("a").getTemperature());assertFalse(p.getProviders().get("a").isSingleAttempt());
    }
    @Test void legacyConfigCopyAlsoPreservesThinkingStyleAndWindow() {
        var p=properties();p.getRouting().setDefaultProviders(List.of());p.setDefaultModel(p.getProviders().get("a"));
        var g=new DefaultLlmGateway(p,client((c,r)->{
            assertEquals("style-a",c.getStyleGuide());assertFalse(c.isThinkingEnabled());assertEquals(64000,c.getContextWindow());
            assertFalse(c.isSingleAttempt());return new LlmResponse("ok",0,0);
        }));g.complete(REQUEST);
    }
    /** NCS-140（旅程三 F-6）：单次 complete() 逻辑调用有墙钟上限——
     *  超限后停止尝试剩余 provider（实测曾单逻辑调用挂 49 分钟拖住整个项目工作权）。 */
    @Test void logicalCallDeadlineStopsTryingRemainingProviders() {
        var p=properties();
        p.getRouting().setDefaultProviders(List.of("a","b","c"));
        p.getProviders().put("c",p.getProviders().get("b"));
        var clock=new MutableClock();
        long[] base={0L};
        var g=new DefaultLlmGateway(p,client((c,r)->{
            base[0]+=40L*60*1000; // 每次尝试消耗 40 分钟
            clock.set(base[0]);
            throw down();
        }),clock);
        var e=assertThrows(RuntimeException.class,()->g.complete(REQUEST));
        assertTrue(e.getMessage().contains("logical-call-deadline"),
                "超限后必须在异常中注明deadline: "+e.getMessage());
    }

    /** 时钟桩：可手动设定，供 deadline 语义测试 */
    static final class MutableClock extends java.time.Clock {
        private long millis;
        void set(long ms) { millis = ms; }
        @Override public long millis() { return millis; }
        @Override public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
        @Override public java.time.Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public java.time.Instant instant() { return java.time.Instant.ofEpochMilli(millis); }
    }
    @Test void requestAndPolicyAndCancellationFailuresDoNotTryBackupOrPoisonHealth() {
        for(RuntimeException failure:List.of(new IllegalArgumentException("invalid"),new ContentFilterException("filtered"),new CancellationException("cancel"),new IllegalStateException("bug"))) {
            var attempts=new ArrayList<String>();var g=new DefaultLlmGateway(properties(),client((c,r)->{attempts.add(c.getModel());throw failure;}));
            assertSame(failure,assertThrows(RuntimeException.class,()->g.complete(REQUEST)));
            assertSame(failure,assertThrows(RuntimeException.class,()->g.complete(REQUEST)));
            assertEquals(List.of("model-a","model-a"),attempts);
        }
    }
    @Test void taskOverrideSelectsItsOwnPrimary() {
        var p=properties();p.getRouting().getTasks().put("GENERAL",List.of("b"));
        var g=new DefaultLlmGateway(p,client((c,r)->new LlmResponse(c.getModel(),0,0)));
        assertEquals("model-b",g.complete(REQUEST).getContent());
    }
    @Test void interruptedThreadNeverContactsProvider() {
        try {Thread.currentThread().interrupt();
            var g=new DefaultLlmGateway(properties(),client((c,r)->{fail("called after interrupt");return null;}));
            assertThrows(CancellationException.class,()->g.complete(REQUEST));assertTrue(Thread.currentThread().isInterrupted());
        } finally {Thread.interrupted();}
    }
    static class TestClock extends java.time.Clock {
        final java.util.concurrent.atomic.AtomicLong seconds = new java.util.concurrent.atomic.AtomicLong();
        public java.time.ZoneId getZone(){return java.time.ZoneOffset.UTC;}
        public java.time.Clock withZone(java.time.ZoneId z){return this;}
        public java.time.Instant instant(){return java.time.Instant.ofEpochSecond(seconds.get());}
    }
    DefaultLlmGateway clockGateway(LlmProperties p, LlmClient client, TestClock clock) {
        return assertDoesNotThrow(() -> DefaultLlmGateway.class
                .getConstructor(LlmProperties.class,LlmClient.class,java.time.Clock.class).newInstance(p,client,clock));
    }
    @Test void primaryRecoversAfterCooldownAndFailureRestartsCooldown() {
        var clock=new TestClock();var calls=new java.util.concurrent.atomic.AtomicInteger();
        var g=clockGateway(properties(),client((c,r)->{
            if(c.getModel().equals("model-a") && calls.incrementAndGet()<=2)throw down();
            return new LlmResponse(c.getModel(),0,0);
        }),clock);
        assertEquals("model-b",g.complete(REQUEST).getContent());
        clock.seconds.set(59);assertEquals("model-b",g.complete(REQUEST).getContent());assertEquals(1,calls.get());
        clock.seconds.set(60);assertEquals("model-b",g.complete(REQUEST).getContent());assertEquals(2,calls.get());
        clock.seconds.set(119);assertEquals("model-b",g.complete(REQUEST).getContent());assertEquals(2,calls.get());
        clock.seconds.set(120);assertEquals("model-a",g.complete(REQUEST).getContent());
        assertEquals("model-a",g.complete(REQUEST).getContent());assertEquals(4,calls.get());
    }
    @Test void onlyOneConcurrentRecoveryProbeIsAllowed() throws Exception {
        var clock=new TestClock();var calls=new java.util.concurrent.atomic.AtomicInteger();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var g=clockGateway(properties(),client((c,r)->{
            if(c.getModel().equals("model-a")) {
                if(calls.incrementAndGet()==1)throw down();
                entered.countDown();try {assertTrue(release.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}
            }
            return new LlmResponse(c.getModel(),0,0);
        }),clock);
        assertEquals("model-b",g.complete(REQUEST).getContent());clock.seconds.set(60);
        var executor=Executors.newSingleThreadExecutor();
        try {
            var probe=executor.submit(()->g.complete(REQUEST));assertTrue(entered.await(3,TimeUnit.SECONDS));
            assertEquals("model-b",g.complete(REQUEST).getContent());assertEquals(2,calls.get());
            release.countDown();assertEquals("model-a",probe.get(3,TimeUnit.SECONDS).getContent());
        } finally {release.countDown();executor.shutdownNow();}
    }

}
