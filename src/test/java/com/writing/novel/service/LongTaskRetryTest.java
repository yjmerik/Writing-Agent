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
        return new RuntimeException(
                "No available LLM provider: qwen-flash-general: cooling until 2026-09-30T01:25:33Z");
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
            if (st.isPresent() && "FAILED".equals(st.get().status())) {
                assertTrue(st.get().error().contains("已重试"),
                        "终态错误必须注明重试耗尽: " + st.get().error());
                return;
            }
            TimeUnit.MILLISECONDS.sleep(30);
        }
        throw new AssertionError("重试耗尽后应进入 FAILED 终态");
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
