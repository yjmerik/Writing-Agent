package com.writing.novel.service;

import com.writing.novel.domain.ContinuityIssue;
import com.writing.novel.domain.Foreshadowing;
import com.writing.novel.domain.Project;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * p32-09（体验报告 #5/P2）：伏笔悬置提醒每伏笔只保留一条未关闭 issue。
 * 复现：22 章首批 41 项中 35 项、23 章 36 项中 35 项都是同批旧伏笔的逐章重复提醒，
 * 淹没当章阻断项——同一伏笔在每章各写一条新 issue。
 * 修复：写入前对同一伏笔（描述前缀匹配）去重，已存在未关闭提醒则跳过。
 */
class ForeshadowIssueDedupTest {

    @TempDir
    Path tempDir;
    private SqliteNovelRepository repo;
    private String pid;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("dedup.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        Project p = new Project("提醒去重", "概念", "悬疑");
        repo.saveProject(p);
        pid = p.getId();
    }

    private void plant(String description, int atChapter) {
        Foreshadowing f = new Foreshadowing(pid, description, "上下文", atChapter);
        f.setPriority(Foreshadowing.Priority.HIGH);
        repo.saveForeshadowing(f);
    }

    @Test
    void 同一伏笔跨章只保留一条未关闭提醒() {
        plant("铜牌上的日期差", 1);
        plant("撕角编组单", 2);

        int first = SuspenseReminderPolicy.refresh(repo, pid, 10, 8);
        assertEquals(2, first, "两个悬置超阈值的伏笔应各写一条提醒");

        // 下一章：不应重复追加（旧 bug：每章 ×2 条）
        int second = SuspenseReminderPolicy.refresh(repo, pid, 11, 8);
        assertEquals(0, second, "同一伏笔已有未关闭提醒时不得重复写");
        assertEquals(2, unresolvedSuspenseHints(), "提醒总数保持一条/伏笔");

        // 第三条伏笔悬置后才新增
        plant("车库编号", 4);
        int third = SuspenseReminderPolicy.refresh(repo, pid, 13, 8);
        assertEquals(1, third, "仅新达到阈值的伏笔追加提醒");
        assertEquals(3, unresolvedSuspenseHints());
    }

    @Test
    void 提醒被关闭后同伏笔可重新提醒一次随后继续去重() {
        plant("铭文被磨去的字", 1);
        assertEquals(1, SuspenseReminderPolicy.refresh(repo, pid, 10, 8));
        ContinuityIssue hint = repo.findUnresolvedIssues(pid).stream()
                .filter(i -> i.getType() == ContinuityIssue.Type.FORESHADOWING).findFirst().orElseThrow();
        hint.resolve();
        repo.saveContinuityIssue(hint);

        // 伏笔仍悬置而提醒已关闭 → 重新提醒一次；再下一章已有未关闭提醒 → 去重
        assertEquals(1, SuspenseReminderPolicy.refresh(repo, pid, 11, 8));
        assertEquals(0, SuspenseReminderPolicy.refresh(repo, pid, 12, 8));
        assertEquals(1, unresolvedSuspenseHints());
    }

    private int unresolvedSuspenseHints() {
        return (int) repo.findUnresolvedIssues(pid).stream()
                .filter(i -> i.getType() == ContinuityIssue.Type.FORESHADOWING).count();
    }
}
