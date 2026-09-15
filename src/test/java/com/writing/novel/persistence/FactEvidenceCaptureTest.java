package com.writing.novel.persistence;

import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.persistence.sqlite.StoryFactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** p39-03：收割实际来源与人审补证据——saveFactEvidence 连接内写入+查询。 */
class FactEvidenceCaptureTest {

    @TempDir
    Path tempDir;
    private StoryFactRepository factRepo;

    @BeforeEach
    void setUp() {
        var cp = new ConnectionProvider(tempDir.resolve("fc.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        factRepo = new StoryFactRepository(cp);
    }

    @Test
    void harvest捕获来源并查询() {
        factRepo.runInTransaction(conn -> {
            factRepo.saveFactEvidence(conn, "ev-1", "f-1", "p1", 3, 1, "hash-1",
                    "主角拔剑", 0, "a-1", 3, null);
            return null;
        });
        var repo = new FactEvidenceRepository(factRepo);
        assertEquals(1, repo.findByFact("p1", "f-1").size());
        assertEquals("主角拔剑", repo.findByFact("p1", "f-1").get(0).get("quote"));
    }

    @Test
    void 人审补证据_同事务多来源() {
        factRepo.runInTransaction(conn -> {
            factRepo.saveFactEvidence(conn, "ev-2", "f-2", "p1", 4, 2, "h2", "人审引文", 1, "a2", 4, null);
            factRepo.saveFactEvidence(conn, "ev-3", "f-2", "p1", 5, 1, "h5", "另一章引文", 2, "a5", 5, null);
            return null;
        });
        var repo = new FactEvidenceRepository(factRepo);
        assertEquals(2, repo.findByFact("p1", "f-2").size(), "两章证据并存");
    }
}
