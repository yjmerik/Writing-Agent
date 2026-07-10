package com.writing.novel.orchestrator;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@TestPropertySource(properties = {
        "persistence.embedding=stub",
        "persistence.sqlite.db-path=./data/test-orch.db",
        "persistence.vector-store.index-path=./data/test-orch-vecs.idx"
})
class OrchestratorIntegrationTest {

    @Autowired ApplicationContext context;

    @org.junit.jupiter.api.BeforeAll
    static void createDataDir() { new File("./data").mkdirs(); }

    @Test
    void orchestrator与projectService被装配() {
        assertNotNull(context.getBean(NovelOrchestrator.class));
        assertNotNull(context.getBean(ProjectService.class));
    }
}
