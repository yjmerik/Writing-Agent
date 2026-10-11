package com.writing.novel.agent;

import com.writing.novel.domain.AuthorIntent;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.service.AuthorIntentContextService;
import com.writing.novel.spi.NovelRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-034: 世界/人物规划与全书卷计划请求带「精确契约版本及内容」——
 * 非空契约时请求注入【创作约定·第 N 版】段（含契约内容），空契约零注入；
 * 所用修订号经 AUTHOR_INTENT_USED 事件审计。
 */
class AuthorIntentPlanningTest {

    private static final String INTENT_JSON = """
            {"premise":"庙堂之高与江湖之远互为镜像","genreExpectation":"历史权谋，读者期待信息差博弈",
             "themes":"清望与实利的张力","pov":"第三人称限制","lengthRange":"每章 3000-5000 字",
             "autonomy":"大纲与人物可自主细化；主线转折必须留给作者","unwantedPatterns":"系统流、金手指"}""";

    private record Capturing(List<LlmRequest> requests) implements LlmGateway {
        @Override
        public LlmResponse complete(LlmRequest req) {
            requests.add(req);
            return new LlmResponse("【地理】测试大陆。", 10, 10);
        }
        @Override
        public void streamComplete(LlmRequest req, StreamHandler handler) {}
    }

    @Test
    void 世界人物与卷计划请求带契约版本及内容(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) {
        var cp = new com.writing.novel.persistence.sqlite.ConnectionProvider(
                tempDir.resolve("ap.db").toString());
        new com.writing.novel.persistence.sqlite.SqliteSchemaInitializer(cp).initialize();
        var repo = new com.writing.novel.persistence.sqlite.SqliteNovelRepository(cp);
        var p = new com.writing.novel.domain.Project("权谋", "庙堂", "历史");
        repo.saveProject(p);
        var intentRepo = new com.writing.novel.persistence.sqlite.AuthorIntentRepository(cp);
        var intent = new AuthorIntent();
        intent.setProjectId(p.getId());
        intent.setRevision(3);
        intent.setPremise("庙堂之高与江湖之远互为镜像");
        intent.setAutonomy("大纲与人物可自主细化；主线转折必须留给作者");
        intent.setUnwantedPatterns("系统流、金手指");
        intentRepo.append(p.getId(), intent);

        var service = new AuthorIntentContextService(repo, TestPromptHelper.create());
        org.junit.jupiter.api.Assertions.assertFalse(service.render(p.getId(), 2000).isBlank(), "render 应产出约定段");
        org.junit.jupiter.api.Assertions.assertEquals(1, service.currentRevision(p.getId()), "append 归一化修订号");
        var requests = new ArrayList<LlmRequest>();
        Capturing gateway = new Capturing(requests);

        // 世界规划（saveEntry 打桩——本测试只验证 prompt）
        var stubKb = new com.writing.novel.memory.KnowledgeBaseService(null, null) {
            @Override public void saveEntry(com.writing.novel.domain.KnowledgeEntry e) {}
        };
        new WorldBuilderAgent(gateway, stubKb, TestPromptHelper.create()) {{
            setAuthorIntentContextService(service);
        }}.build(p.getId(), "构思文本", "历史", "");
        // 人物规划
        new CharacterDesignerAgent(gateway, null, TestPromptHelper.create()) {{
            setAuthorIntentContextService(service);
        }}.design(p.getId(), "需要新的对手", java.util.List.of());
        // 卷计划
        PlotterAgent plotter = new PlotterAgent(gateway, repo, TestPromptHelper.create());
        plotter.setAuthorIntentContextService(service);
        plotter.planVolume("总纲", "前文", "卷2", "梗概", 9, 16, "角色A", "主角甲",
                com.writing.novel.domain.GenreProfile.HISTORICAL, p.getId());

        assertTrue(requests.size() >= 3, "三类规划请求都应发出（部分 Agent 可能附加调用），实际 " + requests.size());
        String expectedVersion = "【创作约定·第" + service.currentRevision(p.getId()) + "版】";
        for (int i = 0; i < requests.size(); i++) {
            LlmRequest req = requests.get(i);
            String prompt = req.getSystemPrompt();
            if (!prompt.contains(expectedVersion)) {
                fail("请求 #" + i + " (" + req.getTaskType() + ") 缺契约段，前 300 字: "
                        + prompt.substring(0, Math.min(300, prompt.length())));
            }
            assertTrue(prompt.contains(expectedVersion), "请求应带精确契约版本");
            assertTrue(prompt.contains("庙堂之高与江湖之远互为镜像"), "请求应带契约内容");
            assertTrue(prompt.contains("自主程度仅描述创作偏好"), "委托边界条款应在场（防自由文本提权）");
        }
    }

    @Test
    void 空契约时规划请求零注入且不审计(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) {
        var cp = new com.writing.novel.persistence.sqlite.ConnectionProvider(
                tempDir.resolve("ap2.db").toString());
        new com.writing.novel.persistence.sqlite.SqliteSchemaInitializer(cp).initialize();
        var repo = new com.writing.novel.persistence.sqlite.SqliteNovelRepository(cp);
        var p = new com.writing.novel.domain.Project("权谋", "庙堂", "历史");
        repo.saveProject(p);

        var service = new AuthorIntentContextService(repo, TestPromptHelper.create());
        assertEquals(-1, service.currentRevision(p.getId()), "空契约修订号 -1");
        var requests = new ArrayList<LlmRequest>();
        Capturing gateway = new Capturing(requests);

        var stubKb2 = new com.writing.novel.memory.KnowledgeBaseService(null, null) {
            @Override public void saveEntry(com.writing.novel.domain.KnowledgeEntry e) {}
        };
        new WorldBuilderAgent(gateway, stubKb2, TestPromptHelper.create()) {{
            setAuthorIntentContextService(service);
        }}.build(p.getId(), "构思文本", "历史", "");

        assertFalse(requests.get(0).getSystemPrompt().contains("创作约定"), "空契约零注入");
    }
}
