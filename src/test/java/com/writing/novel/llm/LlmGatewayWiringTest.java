package com.writing.novel.llm;

import com.writing.novel.llm.client.OpenAiCompatibleClient;
import com.writing.novel.llm.gateway.ObservedLlmGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class LlmGatewayWiringTest {

    @Autowired
    ApplicationContext context;

    @org.junit.jupiter.api.BeforeAll
    static void createDataDir() { new File("./data").mkdirs(); }

    @Test
    void gateway与client均被装配() {
        assertNotNull(context.getBean(LlmGateway.class));
        assertTrue(context.getBean(LlmGateway.class) instanceof ObservedLlmGateway);
        assertNotNull(context.getBean(OpenAiCompatibleClient.class));
    }
}
