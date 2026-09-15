package com.writing.novel.persistence;

import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** p39-09：设定影响预览接口——验证影响预览的基础设施可用。 */
class CanonImpactApiTest {

    @TempDir
    Path tempDir;

    @Test
    void factEvidenceRepository可查询() {
        var cp = new ConnectionProvider(tempDir.resolve("cia.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        var storyRepo = new com.writing.novel.persistence.sqlite.StoryFactRepository(cp);
        var factEvidenceRepo = new FactEvidenceRepository(storyRepo);
        assertTrue(factEvidenceRepo.findByFact("p-nonexistent", "f-nonexistent").isEmpty(),
                "空项目返回空列表");
    }
}
