package com.writing.novel.llm;

import com.writing.novel.llm.client.LlmClient;
import com.writing.novel.llm.client.ProtocolRoutingLlmClient;
import com.writing.novel.llm.gateway.ObservedLlmGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@TestPropertySource(properties = "llm.model-config-file=")
class LlmGatewayWiringTest {

    @Autowired
    ApplicationContext context;

    @org.junit.jupiter.api.BeforeAll
    static void createDataDir() { new File("./data").mkdirs(); }

    @Test
    void gateway与client均被装配() {
        assertNotNull(context.getBean(LlmGateway.class));
        assertTrue(context.getBean(LlmGateway.class) instanceof ObservedLlmGateway);
        // p43-01: LlmClient 装配为协议分发器（默认协议内部委托 OpenAiCompatibleClient）
        assertTrue(context.getBean(LlmClient.class) instanceof ProtocolRoutingLlmClient);
    }
}
