package com.writing.novel.memory;

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

class ForeshadowActiveSetTest {

    @TempDir Path tempDir;
    private SqliteNovelRepository repo;
    private ContextAssembler assembler;
    private String pid;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("fs.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        var emb = new com.writing.novel.spi.EmbeddingProvider() {
            @Override public float[] embed(String text) { return new float[]{1f}; }
            @Override public int dimension() { return 1; }
        };
        var store = new com.writing.novel.persistence.embedding.EmbeddedVectorStore(
                tempDir.resolve("fs.idx").toString());
        var vector = new VectorRetrievalService(store, emb);
        assembler = new ContextAssembler(repo, new KnowledgeBaseService(repo, vector),
                new SummaryService(repo), vector, null,
                new com.writing.novel.service.ClimaxDiversityTracker(),
                null,
                new com.writing.novel.service.DiversityTracker(),
                new com.writing.novel.service.ClicheDetector(repo));
        Project p = new Project("伏笔", "构思", "玄幻");
        repo.saveProject(p);
        pid = p.getId();
    }

    @Test
    void 两百条未回收时上下文最多十二条且mustResolve优先() {
        for (int i = 0; i < 200; i++) {
            Foreshadowing f = new Foreshadowing(pid, "闲笔" + i, "ctx", 1);
            f.setPriority(Foreshadowing.Priority.LOW);
            repo.saveForeshadowing(f);
        }
        Foreshadowing must = new Foreshadowing(pid, "主线钥匙必须回收", "ctx", 2);
        must.setMustResolve(true);
        must.setPriority(Foreshadowing.Priority.HIGH);
        repo.saveForeshadowing(must);

        var ctx = assembler.assemble(pid, 50, "章", "梗概", "笔记");
        String section = ctx.getRelevantForeshadowings();
        assertNotNull(section);
        assertTrue(section.contains("主线钥匙必须回收"));
        long listed = section.lines().filter(l -> l.startsWith("- ") || l.contains("★")).count();
        assertTrue(listed <= 12, "活跃集应 ≤12，实际 " + listed);
    }
}
