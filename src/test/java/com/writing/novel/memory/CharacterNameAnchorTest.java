package com.writing.novel.memory;

import com.writing.novel.domain.Character;
import com.writing.novel.domain.KnowledgeEntry;
import com.writing.novel.domain.Project;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.agent.EntityExtractorAgent;
import com.writing.novel.service.EntityGraphService;
import com.writing.novel.spi.EmbeddingProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * cn-03: ChapterWriter 主角名锚点 + ContinuityChecker 姓名漂移检测测试。
 */
class CharacterNameAnchorTest {

    @TempDir
    Path tempDir;
    private SqliteNovelRepository repo;
    private ContextAssembler assembler;
    private KnowledgeBaseService kbSvc;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("cna.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        EmbeddingProvider emb = new EmbeddingProvider() {
            @Override public float[] embed(String text) { return new float[]{1.0f}; }
            @Override public int dimension() { return 1; }
        };
        var store = new com.writing.novel.persistence.embedding.EmbeddedVectorStore(tempDir.resolve("cna.idx").toString());
        VectorRetrievalService vectorSvc = new VectorRetrievalService(store, emb);
        kbSvc = new KnowledgeBaseService(repo, vectorSvc);
        SummaryService summarySvc = new SummaryService(repo);
        EntityGraphService entityGraphSvc = new EntityGraphService(repo, null) {
            @Override public String recallConsistencySection(String projectId, int currentChapter) { return ""; }
            @Override public java.util.List<String> getRecentEntityNames(String projectId, int currentChapter) { return java.util.List.of(); }
            @Override public String resolveCanonical(String projectId, String rawName) { return rawName; }
            @Override public EntityExtractorAgent.ExtractionResult extractFromChapter(String projectId, com.writing.novel.domain.Chapter chapter) {
                return new EntityExtractorAgent.ExtractionResult(0, 0, 0, null);
            }
        };
        assembler = new ContextAssembler(repo, kbSvc, summarySvc, vectorSvc, entityGraphSvc,
                new com.writing.novel.service.ClimaxDiversityTracker(), null, null, null);
    }

    @Test
    void 有主角卡时写作约束含主角名锚点() {
        Project p = new Project("测名", "故事", "玄幻");
        repo.saveProject(p);
        Character mc = new Character(p.getId(), "沈砚", "男主角");
        kbSvc.saveCharacter(mc);

        WritingContext ctx = assembler.assemble(p.getId(), 1, "第1章", "开局", null);
        assertTrue(ctx.getWritingConstraints().contains("主角名锚点"), "应注入主角名锚点约束");
        assertTrue(ctx.getWritingConstraints().contains("沈砚"), "约束应含主角名 沈砚");
    }

    @Test
    void 无主角卡时不注入名锚点() {
        Project p = new Project("无主角", "故事", "玄幻");
        repo.saveProject(p);
        WritingContext ctx = assembler.assemble(p.getId(), 1, "第1章", "开局", null);
        assertFalse(ctx.getWritingConstraints().contains("主角名锚点"), "无主角卡不应注入");
    }

    @Test
    void continuityChecker姓名漂移检测注入() {
        // 用真实 repo 让 ContinuityCheckerAgent 能查到主角
        Project p = new Project("漂移测", "故事", "玄幻");
        repo.saveProject(p);
        Character mc = new Character(p.getId(), "顾蘅秋", "主角");
        repo.saveCharacter(mc);

        final com.writing.novel.llm.LlmRequest[] captured = new com.writing.novel.llm.LlmRequest[1];
        com.writing.novel.llm.LlmGateway gw = new com.writing.novel.llm.LlmGateway() {
            @Override public com.writing.novel.llm.LlmResponse complete(com.writing.novel.llm.LlmRequest req) {
                captured[0] = req;
                return new com.writing.novel.llm.LlmResponse("未发现问题。", 50, 50);
            }
            @Override public void streamComplete(com.writing.novel.llm.LlmRequest req, com.writing.novel.llm.client.StreamHandler h) {}
        };
        com.writing.novel.agent.ContinuityCheckerAgent agent =
                new com.writing.novel.agent.ContinuityCheckerAgent(gw, repo, com.writing.novel.agent.TestPromptHelper.create());
        agent.check(p.getId(), 1, "正文", "设定");

        String msg = captured[0].getUserMessage();
        assertTrue(msg.contains("主角姓名一致性专项检查"), "应注入姓名漂移检测段");
        assertTrue(msg.contains("顾蘅秋"));
        assertTrue(msg.contains("【人物|高】"));
    }
}
