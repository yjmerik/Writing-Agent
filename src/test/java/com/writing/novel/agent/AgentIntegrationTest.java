package com.writing.novel.agent;

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
        "persistence.sqlite.db-path=./data/test-agent.db",
        "persistence.vector-store.index-path=./data/test-agent-vecs.idx"
})
class AgentIntegrationTest {

    @Autowired ApplicationContext context;

    @org.junit.jupiter.api.BeforeAll
    static void createDataDir() { new File("./data").mkdirs(); }

    @Test
    void 七个agent均被装配() {
        assertNotNull(context.getBean(WorldBuilderAgent.class));
        assertNotNull(context.getBean(CharacterDesignerAgent.class));
        assertNotNull(context.getBean(PlotterAgent.class));
        assertNotNull(context.getBean(ChapterWriterAgent.class));
        assertNotNull(context.getBean(ContinuityCheckerAgent.class));
        assertNotNull(context.getBean(StyleGuardianAgent.class));
        assertNotNull(context.getBean(MemoryManagerAgent.class));
    }
}
