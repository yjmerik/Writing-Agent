package com.writing.novel.memory;

import com.writing.novel.domain.Chapter;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * mc-p2-03: Token 预算按模型窗口推导 + 组装后估计 token ≤ 窗口 90%。
 * 复现 M3：超长记忆导致 prompt 溢出模型窗口。
 */
class TokenBudgetSafetyTest {

    @TempDir
    Path tempDir;
    private ContextAssembler assembler;
    private SqliteNovelRepository repo;
    private KnowledgeBaseService kbService;
    private SummaryService summaryService;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("budget.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);

        EmbeddingProvider emb = new EmbeddingProvider() {
            @Override public float[] embed(String text) { return new float[]{1.0f}; }
            @Override public int dimension() { return 1; }
        };
        var store = new com.writing.novel.persistence.embedding.EmbeddedVectorStore(
                tempDir.resolve("budget.idx").toString());
        VectorRetrievalService vectorSvc = new VectorRetrievalService(store, emb);
        kbService = new KnowledgeBaseService(repo, vectorSvc);
        summaryService = new SummaryService(repo);
        EntityGraphService entityGraphService = new EntityGraphService(repo, null) {
            @Override public String recallConsistencySection(String projectId, int currentChapter) { return ""; }
            @Override public java.util.List<String> getRecentEntityNames(String projectId, int currentChapter) { return java.util.List.of(); }
            @Override public String resolveCanonical(String projectId, String rawName) { return rawName; }
            @Override public EntityExtractorAgent.ExtractionResult extractFromChapter(String projectId, com.writing.novel.domain.Chapter chapter) {
                return new EntityExtractorAgent.ExtractionResult(0, 0, 0, null);
            }
        };
        assembler = new ContextAssembler(repo, kbService, summaryService, vectorSvc, entityGraphService,
                new com.writing.novel.service.ClimaxDiversityTracker(), null, null, null);
    }

    @Test
    void 预算按模型窗口90百分比推导() {
        assertEquals(57600, TokenBudget.budgetForModel(64000));   // deepseek-v4-flash 档
        assertEquals(29491, TokenBudget.budgetForModel(32768));   // 本地 qwen3-4b 档
        assertEquals(TokenBudget.DEFAULT_BUDGET, TokenBudget.budgetForModel(0)); // 未配置回退
    }

    @Test
    void 组装后估计token不超过模型窗口90百分比() {
        // 大量世界观条目 + 历史章节，模拟 50 章后的长程上下文
        Project p = new Project("预算测试", "测试", "玄幻");
        repo.saveProject(p);
        for (int i = 0; i < 40; i++) {
            KnowledgeEntry e = new KnowledgeEntry(p.getId(), "POWER_SYSTEM",
                    "设定条目" + i, "这是一段很长很长的世界观设定内容，用于撑大上下文占用 token 预算。");
            repo.saveKnowledgeEntry(e);
        }
        for (int i = 1; i <= 30; i++) {
            Chapter c = new Chapter(p.getId(), i, "第" + i + "章");
            c.setContent("第" + i + "章的正文内容，描述主角修炼突破的情节推进，"
                    + "包含大量对话与场景描写以占据 token 预算。".repeat(30));
            repo.saveChapter(c);
        }

        // 用本地小窗口模型（32768 → 预算 29491）组装
        int budget = TokenBudget.budgetForModel(32768);
        WritingContext ctx = assembler.assemble(p.getId(), 31, "第31章", "主角突破境界", "保持节奏", budget);

        int estimated = TokenBudget.estimateTokens(ctx.toSystemPrompt())
                + TokenBudget.estimateTokens(ctx.toUserPrompt());
        assertTrue(estimated <= budget,
                "组装后估计 token " + estimated + " 应 ≤ 模型窗口 90% (" + budget + ")");
    }

    @Test
    void 未传预算时回退默认档() {
        Project p = new Project("默认预算", "测试", "玄幻");
        repo.saveProject(p);
        WritingContext ctx = assembler.assemble(p.getId(), 1, "第1章", "开局", null);
        assertNotNull(ctx.toSystemPrompt());
    }
}
