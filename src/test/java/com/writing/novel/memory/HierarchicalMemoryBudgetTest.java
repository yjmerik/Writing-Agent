package com.writing.novel.memory;

import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * p10-w2-06: 分层记忆 + recentSummary 进入 token 预算裁剪。
 * 复现 GAP#7: recentSummarySection 此前不参与 applyTokenBudget，随章节无界增长挤占关键设定空间。
 */
class HierarchicalMemoryBudgetTest {

    @TempDir
    Path tempDir;
    private ContextAssembler assembler;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("budget.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        SqliteNovelRepository repo = new SqliteNovelRepository(cp);
        VectorRetrievalService vr = mock(VectorRetrievalService.class);
        // 依赖可空——applyTokenBudget 只用 WritingContext 自身
        assembler = new ContextAssembler(repo, new KnowledgeBaseService(repo, vr),
                mock(SummaryService.class), vr,
                null, null, null, null, null, null);
    }

    @Test
    void recentSummarySection超预算时被裁剪() throws Exception {
        WritingContext ctx = new WritingContext();
        // 构造超长前情摘要（模拟 400 章历史累积）
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 2000; i++) huge.append("卷摘要内容累积"); // ~10000 字
        ctx.setRecentSummarySection(huge.toString());
        ctx.setWorldbuildingSection("关键世界观设定，必须保留");
        ctx.setVolumeOutlineSection("本卷大纲");
        String originalSummary = ctx.getRecentSummarySection();

        // 反射调 private applyTokenBudget(ctx, 很小的 budget 触发裁剪)
        Method m = ContextAssembler.class.getDeclaredMethod("applyTokenBudget", WritingContext.class, int.class);
        m.setAccessible(true);
        m.invoke(assembler, ctx, 1500); // 小预算强制裁剪

        assertNotNull(ctx.getRecentSummarySection());
        assertTrue(ctx.getRecentSummarySection().length() < originalSummary.length(),
                "recentSummary 超预算应被裁剪，原=" + originalSummary.length()
                        + " 裁后=" + ctx.getRecentSummarySection().length());
    }

    @Test
    void recentSummarySection未超预算时保留全文() throws Exception {
        WritingContext ctx = new WritingContext();
        ctx.setRecentSummarySection("简短摘要");
        ctx.setWorldbuildingSection("设定");

        Method m = ContextAssembler.class.getDeclaredMethod("applyTokenBudget", WritingContext.class, int.class);
        m.setAccessible(true);
        m.invoke(assembler, ctx, 100000); // 大预算，不裁剪

        assertEquals("简短摘要", ctx.getRecentSummarySection(), "未超预算应保留全文");
    }
}
