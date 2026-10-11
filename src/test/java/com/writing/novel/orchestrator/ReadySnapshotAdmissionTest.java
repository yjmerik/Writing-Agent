package com.writing.novel.orchestrator;

import com.writing.novel.domain.Project;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.PostProcessLedgerRepository;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-044: 派生就绪准入——正文领先派生投影时阻断受影响续写，
 * 阻断消息携带同源身份（章号+来源稿版本）与原因（未就绪阶段）。
 */
class ReadySnapshotAdmissionTest {
    private String pid;

    @TempDir
    Path tempDir;
    private NovelOrchestrator orch;

    @BeforeEach
    void setUp() {
        var cp = new ConnectionProvider(tempDir.resolve("rs.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        var repo = new SqliteNovelRepository(cp);
        var ledger = new PostProcessLedgerRepository(cp);
        orch = new NovelOrchestrator(repo, new com.writing.novel.orchestrator.ProjectService(repo),
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                new com.writing.novel.orchestrator.OrchestratorPolicies(null, null, null, null,
                        null, null, null, null, null, null, ledger));
        var p = new Project("t", "c", "tag");
        repo.saveProject(p);
        pid = p.getId();
        var ch = new com.writing.novel.domain.Chapter(p.getId(), 1, "第一章");
        ch.setContent("正文。");
        repo.saveChapter(ch);
        // 待执行投影入账（来源稿 v2）
        ledger.ensureStage(p.getId(), 1, ChapterPostProcessor.Stage.ENTITY_GRAPH, 2);
        ledger.ensureStage(p.getId(), 1, ChapterPostProcessor.Stage.NARRATIVE_THREADS, 2);
    }

    @Test
    void 正文领先投影时阻断并携带同源身份与原因() {
        var blocker = orch.derivedReadinessBlocker(pid, 2);
        assertTrue(blocker.isPresent(), "投影未就绪应阻断");
        String msg = blocker.get();
        assertTrue(msg.contains("第 1 章"), "同源章号: " + msg);
        assertTrue(msg.contains("v2"), "来源稿版本: " + msg);
        assertTrue(msg.contains("ENTITY_GRAPH") && msg.contains("NARRATIVE_THREADS"), "未就绪阶段: " + msg);
        assertTrue(msg.contains("续写"), "阻断原因应说明影响");
    }

    @Test
    void 投影就绪时不阻断() {
        var ledger = new PostProcessLedgerRepository(new ConnectionProvider(
                tempDir.resolve("rs.db").toString()));
        ledger.markCompletedForVersion(pid, 1,
                ChapterPostProcessor.Stage.ENTITY_GRAPH, 2);
        ledger.markCompletedForVersion(pid, 1,
                ChapterPostProcessor.Stage.NARRATIVE_THREADS, 2);
        assertTrue(orch.derivedReadinessBlocker(pid, 2).isEmpty(), "全部就绪不阻断");
    }

    private SqliteNovelRepository repo() {
        // 测试内直接读同一库（orchestrator 构造时的 repo 与本方法共享 tempDir 文件）
        return new SqliteNovelRepository(new ConnectionProvider(tempDir.resolve("rs.db").toString()));
    }
}
