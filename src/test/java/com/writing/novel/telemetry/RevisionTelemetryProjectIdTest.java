package com.writing.novel.telemetry;

import com.writing.novel.agent.ContentRevisionAgent;
import com.writing.novel.domain.Character;
import com.writing.novel.domain.Project;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.llm.gateway.ObservedLlmGateway;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.service.AgentRevisionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * NCS-086（听雪刀 P2-4）：修订管线的 LLM 调用必须携带 projectId。
 * 此前 EventContext 只在 runConceiving/runWritingOneChapter 设置——
 * 「让 Agent 修改」预览/应用、修改意见重写等路径的 LLM_CALL 事件缺 projectId，
 * 事件日志无法按项目归集成本。
 */
class RevisionTelemetryProjectIdTest {

    @TempDir
    Path tempDir;
    private SqliteNovelRepository repo;
    private String pid;
    private Character character;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("revision-telemetry.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        Project p = new Project("遥测修订", "构思", "武侠");
        repo.saveProject(p);
        pid = p.getId();
        character = new Character(pid, "李慕白", "主角");
        character.setPersonality("外冷内热");
        repo.saveCharacter(character);
    }

    @Test
    void 让Agent修改预览的LLM调用事件携带projectId() {
        List<Map<String, Object>> events = new CopyOnWriteArrayList<>();
        EventLog.Listener listener = events::add;
        EventLog.subscribe(listener);
        try {
            String structured = "{\"id\":\"" + character.getId() + "\",\"projectId\":\"" + pid
                    + "\",\"name\":\"李慕白\",\"role\":\"主角\",\"personality\":\"更内敛\"}";
            LlmGateway stub = new LlmGateway() {
                @Override
                public LlmResponse complete(LlmRequest req) {
                    return new LlmResponse("{\"targetType\":\"CHARACTER\",\"title\":\"李慕白\","
                            + "\"content\":\"更内敛的性格描写。\",\"structuredJson\":"
                            + com.fasterxml.jackson.databind.node.TextNode
                                .valueOf(structured).toString()
                            + ",\"summary\":\"性格微调\",\"directiveResults\":null}", 20, 40);
                }

                @Override
                public void streamComplete(LlmRequest req, StreamHandler handler) {
                }
            };
            ObservedLlmGateway observed = new ObservedLlmGateway(stub);
            var promptService = mock(com.writing.novel.llm.prompt.PromptTemplateService.class);
            when(promptService.render(anyString(), any())).thenReturn("prompt");
            ContentRevisionAgent agent = new ContentRevisionAgent(observed, promptService);
            AgentRevisionService service = new AgentRevisionService(repo, agent,
                    null, new com.writing.novel.service.WritingTaskExecutor(bareOrchestrator(), repo),
                    bareOrchestrator(), new ObjectMapper(), null);

            service.preview(pid, new AgentRevisionService.RevisionRequest(
                    ContentRevisionAgent.TargetType.CHARACTER, character.getId(),
                    null, null, "性格更内敛", "telemetry-key-1"));

            assertTrue(events.stream().anyMatch(e -> "LLM_CALL".equals(e.get("event"))
                            && pid.equals(e.get("projectId"))),
                    "修订管线（让 Agent 修改）的 LLM_CALL 事件必须携带 projectId，实际: " + events);
        } finally {
            EventLog.unsubscribe(listener);
        }
    }

    @Test
    void 预览完成后EventContext被清理不串项目() {
        // 先在 EventContext 里放一个别的项目，确保 preview 后不残留（ThreadLocal 卫生）
        String structured = "{\"id\":\"" + character.getId() + "\",\"projectId\":\"" + pid
                + "\",\"name\":\"李慕白\",\"role\":\"主角\"}";
        LlmGateway stub = new LlmGateway() {
            @Override
            public LlmResponse complete(LlmRequest req) {
                return new LlmResponse("{\"targetType\":\"CHARACTER\",\"title\":\"李慕白\","
                        + "\"content\":\"更内敛的性格描写。\",\"structuredJson\":"
                        + com.fasterxml.jackson.databind.node.TextNode.valueOf(structured).toString()
                        + ",\"summary\":\"微调\",\"directiveResults\":null}", 20, 40);
            }

            @Override
            public void streamComplete(LlmRequest req, StreamHandler handler) {
            }
        };
        var promptService = mock(com.writing.novel.llm.prompt.PromptTemplateService.class);
        when(promptService.render(anyString(), any())).thenReturn("prompt");
        AgentRevisionService service = new AgentRevisionService(repo,
                new ContentRevisionAgent(new ObservedLlmGateway(stub), promptService),
                null, new com.writing.novel.service.WritingTaskExecutor(bareOrchestrator(), repo),
                bareOrchestrator(), new ObjectMapper(), null);

        service.preview(pid, new AgentRevisionService.RevisionRequest(
                ContentRevisionAgent.TargetType.CHARACTER, character.getId(),
                null, null, "微调", "telemetry-key-2"));

        assertNull(EventContext.projectId(), "预览完成后 EventContext 必须清理（不得污染同线程后续事件）");
    }

    /** 构造仅含仓库的编排器（refreshChapterAfterRevisionAsync 方法引用需要非 null 接收者）。 */
    private com.writing.novel.orchestrator.NovelOrchestrator bareOrchestrator() {
        return new com.writing.novel.orchestrator.NovelOrchestrator(repo,
                new com.writing.novel.orchestrator.ProjectService(repo),
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
