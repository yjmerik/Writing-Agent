package com.writing.novel.service;

import com.writing.novel.domain.RevisionProposal;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/** p40-01：候选重启恢复——候选在 DB 中持久化，新连接可恢复。 */
class ProposalRestartRecoveryTest {

    @TempDir
    Path tempDir;

    @Test
    void proposalSurvivesRestart() {
        var dbPath = tempDir.resolve("restart.db").toString();
        var cp1 = new ConnectionProvider(dbPath);
        new com.writing.novel.persistence.sqlite.SqliteSchemaInitializer(cp1).initialize();
        var repo1 = new com.writing.novel.persistence.sqlite.SqliteNovelRepository(cp1);
        var proposal = new RevisionProposal("prop-r", "p1",
                RevisionProposal.TargetType.CHAPTER, "ch-1", 1, null,
                2, RevisionProposal.chapterSourceHash("t", "c"), "指令",
                "{}", "{}", null, RevisionProposal.Status.READY, "key-r", null,
                Instant.now(), Instant.now());
        repo1.saveProposal(proposal);

        // Simulate restart: new connection + new repository
        var cp2 = new ConnectionProvider(dbPath);
        var repo2 = new com.writing.novel.persistence.sqlite.SqliteNovelRepository(cp2);
        assertTrue(repo2.findProposal("p1", "prop-r").isPresent(), "候选重启后可恢复");
        assertEquals(RevisionProposal.Status.READY, repo2.findProposal("p1", "prop-r").orElseThrow().getStatus());
    }
}
