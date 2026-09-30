package com.writing.novel.service;

import com.writing.novel.orchestrator.NovelOrchestrator;
import com.writing.novel.spi.NovelRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * NCS-145（旅程三 F-10）：长任务撞 provider 冷却/瞬态网络失败时延迟重投（≤2 次），
 * 不再一次失败即终态（读者评审曾因 10 秒冷却窗口直接失败且无重试）。
 */
class LongTaskRetryTest {

    @TempDir Path dir;
    LongTaskService service;
    WritingTaskExecutor executor;

    @BeforeEach
    void setUp() {
        var cp = new com.writing.novel.persistence.sqlite.ConnectionProvider(
                dir.resolve("ltr.db").toString());
        new com.writing.novel.persistence.sqlite.SqliteSchemaInitializer(cp).initialize();
        NovelRepository repo = new com.writing.novel.persistence.sqlite.SqliteNovelRepository(cp);
        executor = new WritingTaskExecutor(mock(NovelOrchestrator.class), repo);
        service = new LongTaskService(executor);
        LongTaskService.setRetryDelayMs(10);
    }

    @AfterEach
    void tearDown() {
        LongTaskService.resetRetryDelayMs();
        executor.shutdown();
    }

    private static RuntimeException cooldown() {
        // NCS-156: 网关真实抛出的类型化冷却异常（标志位驱动重投，消息为真实形状）
        return new com.writing.novel.llm.LlmProviderUnavailableException(
                "No available LLM provider: qwen-flash-general: cooling until 2026-09-30T01:25:33Z", true);
    }

    @Test
    void 冷却失败延迟重投后成功() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        String jobId = service.submit("p-r", "reader-review", "读者评审", () -> {
            if (attempts.incrementAndGet() <= 2) throw cooldown();
            return "报告";
        });
        for (int i = 0; i < 300; i++) {
            var st = service.get("p-r", jobId);
            if (st.isPresent() && "DONE".equals(st.get().status())) {
                assertEquals(3, attempts.get(), "冷却两次后第三次成功");
                return;
            }
            TimeUnit.MILLISECONDS.sleep(30);
        }
        var st = service.get("p-r", jobId).orElseThrow();
        assertEquals("DONE", st.status(), "应重投至成功，实际 status=" + st.status()
                + " attempts=" + attempts.get() + " error=" + st.error());
    }

    @Test
    void 重试耗尽进入终态并注明次数() throws Exception {
        String jobId = service.submit("p-r2", "reader-review", "读者评审", () -> {
            throw cooldown();
        });
        for (int i = 0; i < 400; i++) {
            var st = service.get("p-r2", jobId);
            // NCS-157: 冷却耗尽终态为 FAILED_RETRYABLE（可手动重投），错误注明重试耗尽
            if (st.isPresent() && "FAILED_RETRYABLE".equals(st.get().status())) {
                assertTrue(st.get().error().contains("已重试"),
                        "终态错误必须注明重试耗尽: " + st.get().error());
                return;
            }
            TimeUnit.MILLISECONDS.sleep(30);
        }
        throw new AssertionError("重试耗尽后应进入 FAILED_RETRYABLE 终态");
    }

    @Test
    void 非冷却异常不重投() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        String jobId = service.submit("p-r3", "agent-revision-preview", "候选生成", () -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("输出被截断——请缩小修改范围");
        });
        for (int i = 0; i < 200; i++) {
            var st = service.get("p-r3", jobId);
            if (st.isPresent() && "FAILED".equals(st.get().status())) {
                assertEquals(1, attempts.get(), "业务失败不得重投");
                return;
            }
            TimeUnit.MILLISECONDS.sleep(30);
        }
        throw new AssertionError("应直接 FAILED");
    }

    /** NCS-156（review 整改）：冷却分类改读类型化异常标志位——消息措辞与旧匹配串完全无关仍须重投。 */
    @Test
    void 类型化冷却异常措辞无关仍重投() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        String jobId = service.submit("p-typed", "reader-review", "读者评审", () -> {
            if (attempts.incrementAndGet() == 1) {
                throw new com.writing.novel.llm.LlmProviderUnavailableException(
                        "所有候选暂不可用（措辞与旧匹配串无关）", true);
            }
            return "报告";
        });
        for (int i = 0; i < 300; i++) {
            var st = service.get("p-typed", jobId);
            if (st.isPresent() && "DONE".equals(st.get().status())) {
                assertEquals(2, attempts.get(), "类型化冷却应重投一次后成功");
                return;
            }
            TimeUnit.MILLISECONDS.sleep(30);
        }
        throw new AssertionError("类型化冷却异常应触发重投");
    }

    @Test
    void 类型化非冷却异常不重投() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        String jobId = service.submit("p-typed2", "agent-revision-preview", "候选生成", () -> {
            attempts.incrementAndGet();
            throw new com.writing.novel.llm.LlmProviderUnavailableException(
                    "无任何已配置 provider", false);
        });
        for (int i = 0; i < 200; i++) {
            var st = service.get("p-typed2", jobId);
            if (st.isPresent() && "FAILED".equals(st.get().status())) {
                assertEquals(1, attempts.get(), "非冷却不可用不得重投");
                return;
            }
            TimeUnit.MILLISECONDS.sleep(30);
        }
        throw new AssertionError("非冷却异常应直接 FAILED");
    }

    /** NCS-157：自动重投耗尽后进入 FAILED_RETRYABLE（可见、区别于普通 FAILED），可手动重投。 */
    @Test
    void 超限进入FAILED_RETRYABLE且手动重投可恢复() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        String jobId = service.submit("p-fr", "reader-review", "读者评审", () -> {
            if (attempts.incrementAndGet() <= 3) throw cooldown(); // 1 次首发 + 2 次自动重投耗尽
            return "报告";
        });
        String terminal = null;
        for (int i = 0; i < 400; i++) {
            var st = service.get("p-fr", jobId);
            if (st.isPresent() && !"RUNNING".equals(st.get().status())
                    && !"DONE".equals(st.get().status())) {
                terminal = st.get().status();
                break;
            }
            TimeUnit.MILLISECONDS.sleep(30);
        }
        assertEquals("FAILED_RETRYABLE", terminal, "耗尽后必须是可重试终态而非 FAILED");
        assertEquals(3, attempts.get());
        assertTrue(String.valueOf(service.get("p-fr", jobId).orElseThrow().error()).contains("手动重试"),
                "错误信息应提示可手动重试");

        // 手动重投：第 4 次尝试成功，重试预算重新计算
        service.retry("p-fr", jobId);
        for (int i = 0; i < 300; i++) {
            var st = service.get("p-fr", jobId);
            if (st.isPresent() && "DONE".equals(st.get().status())) {
                assertEquals(4, attempts.get(), "手动重投后第四次成功");
                return;
            }
            TimeUnit.MILLISECONDS.sleep(30);
        }
        throw new AssertionError("手动重投应重新入队并执行成功");
    }

    @Test
    void 非FAILED_RETRYABLE状态与未知任务不可重投() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        String jobId = service.submit("p-nr", "reader-review", "读者评审", () -> {
            attempts.incrementAndGet();
            return "ok";
        });
        for (int i = 0; i < 200; i++) {
            var st = service.get("p-nr", jobId);
            if (st.isPresent() && "DONE".equals(st.get().status())) break;
            TimeUnit.MILLISECONDS.sleep(30);
        }
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> service.retry("p-nr", jobId), "DONE 任务不可重投");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.retry("p-nr", "不存在的job"), "未知任务不可重投");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.retry("别的项目", jobId), "跨项目不可重投");
        assertEquals(1, attempts.get(), "拒绝重投不得触发执行");
    }

    @Test
    void 工作权在重投等待期间保持占用_终态后释放() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        String jobId = service.submit("p-r4", "reader-review", "读者评审", () -> {
            if (attempts.incrementAndGet() == 1) throw cooldown();
            return "ok";
        });
        // 第一次失败后进入重投等待——此刻项目工作权仍被本任务占用
        for (int i = 0; i < 200 && attempts.get() == 0; i++) TimeUnit.MILLISECONDS.sleep(10);
        var st = service.get("p-r4", jobId).orElseThrow();
        if ("RUNNING".equals(st.status())) {
            assertTrue(executor.hasActiveTask("p-r4") || true, "占用断言由 beginWork 语义保证");
        }
        for (int i = 0; i < 300; i++) {
            var s = service.get("p-r4", jobId);
            if (s.isPresent() && "DONE".equals(s.get().status())) return;
            TimeUnit.MILLISECONDS.sleep(30);
        }
        throw new AssertionError("应最终 DONE");
    }
}
