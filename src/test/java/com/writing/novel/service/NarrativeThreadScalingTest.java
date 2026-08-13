package com.writing.novel.service;

import com.writing.novel.domain.NarrativeThread;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * p10-w3-07: 叙事线索状态机修正——长线不因一章未提及即 SUSPENDED（GAP#4）。
 * 未提及改 markPending（保持 ACTIVE + 计数），isOverdue 阈值放宽 3→15，getThreadSection cap 10。
 */
class NarrativeThreadScalingTest {

    @TempDir
    Path tempDir;
    private SqliteNovelRepository repo;
    private NarrativeThreadTracker tracker;
    private String pid;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("thread.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        tracker = new NarrativeThreadTracker(repo);
        pid = "proj-1";
    }

    @Test
    void 未提及保持ACTIVE仅累积悬挂计数_不立即SUSPENDED() {
        tracker.registerThread(pid, "宿敌阴谋", 1, "主线");
        // 第 2-5 章未提及该线索
        for (int ch = 2; ch <= 5; ch++) {
            tracker.updateThreads(pid, ch, List.of(), List.of());
        }
        var t = repo.findAllNarrativeThreads(pid).get(0);
        assertEquals(com.writing.novel.domain.ThreadStatus.ACTIVE, t.getStatus(),
                "长线未提及应保持 ACTIVE（不因一章未提及即 SUSPENDED）");
        assertEquals(4, t.getSuspenseChapters(), "悬挂计数应累积 4");
    }

    @Test
    void 阈值15章内不告警_超15章才overdue() {
        NarrativeThread t = new NarrativeThread(pid, "长线", 1, "desc");
        repo.saveNarrativeThread(t);
        // 累积 10 章未提及
        for (int ch = 2; ch <= 11; ch++) tracker.updateThreads(pid, ch, List.of(), List.of());
        String section = tracker.getThreadSection(pid);
        assertFalse(section.contains("悬挂过久"), "10 章未达阈值 15，不应告警 overdue");

        // 累积到 16 章
        for (int ch = 12; ch <= 17; ch++) tracker.updateThreads(pid, ch, List.of(), List.of());
        section = tracker.getThreadSection(pid);
        assertTrue(section.contains("悬挂过久"), "16 章超阈值 15，应告警 overdue");
    }

    @Test
    void 提及时重置悬挂计数() {
        tracker.registerThread(pid, "线索", 1, "desc");
        tracker.updateThreads(pid, 2, List.of(), List.of()); // 未提及 +1
        tracker.updateThreads(pid, 3, List.of("线索"), List.of()); // 提及 reset
        var t = repo.findAllNarrativeThreads(pid).get(0);
        assertEquals(0, t.getSuspenseChapters(), "提及时应 reset 悬挂计数");
    }

    @Test
    void getThreadSection受cap10限制() {
        for (int i = 0; i < 15; i++) tracker.registerThread(pid, "线索" + i, 1, "desc");
        String section = tracker.getThreadSection(pid);
        // cap 10 → 至多 10 条活跃线索
        long count = section.lines().filter(l -> l.startsWith("  - ")).count();
        assertTrue(count <= 10, "应受 cap 10 限制，实际 " + count);
    }
}
