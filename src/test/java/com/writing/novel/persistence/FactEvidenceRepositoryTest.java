package com.writing.novel.persistence;

import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.StoryFactRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** p39-02：事实证据仓储——按事实查询/时点筛选/知情者。 */
class FactEvidenceRepositoryTest {

    @TempDir
    Path tempDir;
    private FactEvidenceRepository repo;

    @BeforeEach
    void setUp() {
        var cp = new ConnectionProvider(tempDir.resolve("fer.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        var storyRepo = new StoryFactRepository(cp);
        repo = new FactEvidenceRepository(storyRepo);
        storyRepo.runInTransaction(conn -> {
            try {
            var saveEv = conn.prepareStatement(
                    "INSERT INTO fact_evidence (id, fact_id, project_id, chapter_index, chapter_version, " +
                            "content_hash, quote, paragraph_index, anchor_hash, valid_from_chapter, " +
                            "valid_to_chapter, created_at) VALUES " +
                            "('e1','f1','p1',3,1,'h1','引文A',0,'a1',3,NULL,datetime('now'))," +
                            "('e2','f1','p1',7,1,'h7','引文B',0,'a7',7,NULL,datetime('now'))");
            saveEv.executeUpdate();
            saveEv.close();
            var saveObs = conn.prepareStatement(
                    "INSERT INTO fact_evidence_observers (id, evidence_id, character_id, " +
                            "learned_at_chapter, chapter_version, quote) VALUES " +
                            "('o1','e1','charA',3,1,NULL),('o2','e1','charB',5,1,NULL)");
            saveObs.executeUpdate();
            saveObs.close();
            } catch (java.sql.SQLException e) { throw new RuntimeException(e); }
            return null;
        });
    }

    @Test
    void findByFact返回全部证据() {
        assertEquals(2, repo.findByFact("p1", "f1").size());
    }

    @Test
    void findKnownAt时点筛选() {
        assertEquals(1, repo.findKnownAt("p1", "f1", 4).size(), "第4章只有e1");
        assertEquals(2, repo.findKnownAt("p1", "f1", 7).size(), "第7章两条都有效");
    }

    @Test
    void findObservers按人查() {
        assertEquals(2, repo.findObservers("p1", "e1").size());
    }
}
