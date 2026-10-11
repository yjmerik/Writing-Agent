package com.writing.novel.service;

import com.writing.novel.domain.Foreshadowing;
import com.writing.novel.domain.Project;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-184: 回报预算器——连续零兑付缺口 → PLOT HIGH；开篇豁免；兑付重置缺口；
 * 读者评审钩子摘要（前 N 章兑付/埋钩）。
 */
class PayoffLedgerTest {

    @TempDir
    Path tempDir;
    private SqliteNovelRepository repo;
    private PayoffLedger ledger;
    private String pid;

    @BeforeEach
    void setUp() {
        var cp = new ConnectionProvider(tempDir.resolve("payoff.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        ledger = new PayoffLedger(repo, 3, 1, 3); // 缺口上限 3、豁免 1、摘要窗 3（测试口径）
        var p = new Project("权谋", "庙堂", "历史");
        repo.saveProject(p);
        pid = p.getId();
    }

    private void plant(int chapter, String desc) {
        var f = new Foreshadowing(pid, desc, "对白", chapter);
        repo.saveForeshadowing(f);
    }

    private void resolve(int plantedChapter, int atChapter) {
        repo.findForeshadowingsByProject(pid).stream()
                .filter(f -> f.getPlantedAtChapter() == plantedChapter)
                .findFirst()
                .ifPresent(f -> {
                    f.resolve(atChapter);
                    repo.saveForeshadowing(f);
                });
    }

    @Test
    void 连续零兑付超限产生PLOT_HIGH且列明缺口() {
        plant(1, "钩子甲");
        resolve(1, 2); // 唯一兑付在第 2 章
        // 假设当前写到第 6 章：gap = 6 - 2 = 4 ≥ 3
        var issue = ledger.checkChapterPayoff(pid, 6);
        assertTrue(issue.isPresent(), "缺口超限应产生 PLOT HIGH");
        assertEquals(com.writing.novel.domain.ContinuityIssue.Type.PLOT, issue.get().getType());
        assertEquals(com.writing.novel.domain.ContinuityIssue.Severity.HIGH, issue.get().getSeverity());
        assertTrue(issue.get().getDescription().contains("连续 4 章"), "缺口应量化: " + issue.get().getDescription());
        assertTrue(issue.get().getDescription().contains("兑付"), "应指明兑付义务");
    }

    @Test
    void 兑付重置缺口_开篇豁免() {
        // 无任何兑付，但章 ≤ 豁免期 → 不产生 issue
        assertTrue(ledger.checkChapterPayoff(pid, 1).isEmpty(), "开篇豁免");
        // 有近期兑付 → 缺口未超限
        var f = new Foreshadowing(pid, "钩子A", "对白", 1);
        repo.saveForeshadowing(f);
        resolve(1, 5);
        assertTrue(ledger.checkChapterPayoff(pid, 6).isEmpty(), "上一章刚兑付，缺口 1");
    }

    @Test
    void 钩子摘要列出窗口内兑付与埋钩() {
        plant(1, "钩子甲");
        plant(2, "巷尾的黑轿");
        resolve(1, 2);
        plant(3, "账册缺页");
        String summary = ledger.recentHooksSummary(pid, 5);
        assertTrue(summary.contains("第 2 章"), "窗口起点章应出现: " + summary);
        assertTrue(summary.contains("[兑付]"), "兑付应标注");
        assertTrue(summary.contains("[埋钩] 巷尾的黑轿"), "埋钩应标注");
        // 窗口 = [5-3, 5) = 2,3,4 → 第3章在窗内
        assertTrue(summary.contains("[埋钩] 账册缺页"), "窗内埋钩应列出");
    }
}
