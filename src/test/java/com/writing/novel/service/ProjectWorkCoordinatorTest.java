package com.writing.novel.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * p20-05: ProjectWorkCoordinator owner-token 语义。
 *
 * 验收（全部用闩锁/栅栏，不用 sleep）：
 * 1. 并发 acquire 同一项目只产生一个 owner；
 * 2. 当前 owner 未释放时再次 acquire 得到冲突（不强制接管）；
 * 3. stale owner 的重复 release 不能清除新 owner；
 * 4. 释放后新 worker 可接管。
 */
class ProjectWorkCoordinatorTest {

    private final ProjectWorkCoordinator coordinator = new ProjectWorkCoordinator();

    @Test
    void 并发acquire只产生一个owner() throws Exception {
        int threads = 16;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final String workId = "w" + i;
            futures.add(pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return coordinator.tryAcquire("p1", workId).isPresent();
            }));
        }
        long winners = futures.stream().filter(f -> {
            try { return f.get(5, TimeUnit.SECONDS); }
            catch (Exception e) { return false; }
        }).count();
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(1, winners, "并发竞争必须恰好产生一个 owner");
        assertEquals("w", coordinator.currentOwner("p1").orElse("").substring(0, 1));
    }

    @Test
    void owner未释放时重复acquire得到冲突() {
        var token = coordinator.tryAcquire("p1", "writer-1");
        assertTrue(token.isPresent());
        assertTrue(coordinator.tryAcquire("p1", "writer-2").isEmpty(),
                "已有 live owner 时再次 acquire 必须冲突（不强制接管）");
        assertTrue(coordinator.isOwner("p1", "writer-1"));
    }

    @Test
    void staleOwner的release不能清除新owner() {
        var first = coordinator.tryAcquire("p1", "old-worker");
        assertTrue(first.isPresent());
        coordinator.release(first.get());

        var second = coordinator.tryAcquire("p1", "new-worker");
        assertTrue(second.isPresent(), "释放后新 worker 可接管");

        // stale owner 迟到的重复 release 不得清掉新 owner
        coordinator.release(first.get());
        assertTrue(coordinator.isOwner("p1", "new-worker"), "stale release 不能清除新 owner");
        assertFalse(coordinator.tryAcquire("p1", "third").isPresent());
    }

    @Test
    void 不同项目互不干扰() {
        assertTrue(coordinator.tryAcquire("p1", "w1").isPresent());
        assertTrue(coordinator.tryAcquire("p2", "w2").isPresent());
        assertTrue(coordinator.isOwner("p1", "w1"));
        assertTrue(coordinator.isOwner("p2", "w2"));
    }

    @Test
    void 释放后可重新获取且释放是条件操作() throws Exception {
        var latch = new CountDownLatch(1);
        var token = coordinator.tryAcquire("p1", "w1").orElseThrow();
        Thread worker = new Thread(() -> {
            try { latch.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { return; }
            coordinator.release(token);
        });
        worker.start();
        assertTrue(coordinator.tryAcquire("p1", "w2").isEmpty(), "worker 未释放前不可获取");
        latch.countDown();
        worker.join(5000);
        assertTrue(coordinator.tryAcquire("p1", "w2").isPresent(), "worker 释放后可重新获取");
    }
}
