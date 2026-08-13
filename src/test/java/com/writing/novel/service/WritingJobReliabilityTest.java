package com.writing.novel.service;

import com.writing.novel.domain.Project;
import com.writing.novel.domain.WritingPreferences;
import com.writing.novel.domain.WritingTask;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.LlmTaskType;
import com.writing.novel.llm.client.LlmClient;
import com.writing.novel.llm.client.RateLimitException;
import com.writing.novel.llm.config.LlmProperties;
import com.writing.novel.llm.config.ModelConfig;
import com.writing.novel.llm.gateway.DefaultLlmGateway;
import com.writing.novel.llm.gateway.ObservedLlmGateway;
import com.writing.novel.orchestrator.NovelOrchestrator;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.telemetry.CostBudgetMonitor;
import com.writing.novel.telemetry.EventContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * p10-w1-04: 成本预算熔断 + 真实计价。
 * 复现 GAP#18: 无成本预算控制，估算硬编码单一费率。
 */
class WritingJobReliabilityTest {

    @TempDir
    Path tempDir;
    private SqliteNovelRepository repo;
    private String pid;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("cost.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        Project p = new Project("成本书", "测试", "玄幻");
        repo.saveProject(p);
        pid = p.getId();
    }

    @Test
    void costBudgetMonitor累计与超预算判定() {
        CostBudgetMonitor monitor = new CostBudgetMonitor();
        EventContext.setProjectId(pid);
        monitor.add(pid, 0.03);
        monitor.add(pid, 0.02);
        assertEquals(0.05, monitor.total(pid), 0.0001);
        assertFalse(monitor.isOverBudget(pid, 0.10), "0.05 < 0.10 不应超");
        assertTrue(monitor.isOverBudget(pid, 0.05), "0.05 >= 0.05 应超");
        assertFalse(monitor.isOverBudget(pid, 0), "budget<=0 不限制");
        EventContext.clear();
    }

    @Test
    void observedLlmGateway按模型pricing计价并累计到monitor() {
        // 配置：CHAPTER_WRITING 输入 0.002/输出 0.004；GENERAL 输入 0.001/输出 0.002
        LlmProperties props = mock(LlmProperties.class);
        ModelConfig chapterCfg = new ModelConfig();
        chapterCfg.setInputPriceYuanPerKTokens(0.002);
        chapterCfg.setOutputPriceYuanPerKTokens(0.004);
        ModelConfig generalCfg = new ModelConfig();
        generalCfg.setInputPriceYuanPerKTokens(0.001);
        generalCfg.setOutputPriceYuanPerKTokens(0.002);
        when(props.forTaskType("CHAPTER_WRITING")).thenReturn(chapterCfg);
        when(props.forTaskType("GENERAL")).thenReturn(generalCfg);

        LlmGateway delegate = mock(LlmGateway.class);
        when(delegate.complete(any())).thenAnswer(inv -> {
            LlmRequest req = inv.getArgument(0);
            // 给不同 taskType 不同的 token 用量
            return switch (req.getTaskType()) {
                case CHAPTER_WRITING -> new LlmResponse("正文", 10000, 5000);
                default -> new LlmResponse("ok", 2000, 500);
            };
        });

        CostBudgetMonitor monitor = new CostBudgetMonitor();
        ObservedLlmGateway observed = new ObservedLlmGateway(delegate, props, monitor);
        EventContext.setProjectId(pid);

        observed.complete(LlmRequest.builder().taskType(LlmTaskType.CHAPTER_WRITING).build());
        // CHAPTER_WRITING: 10000*0.002/1000 + 5000*0.004/1000 = 0.02 + 0.02 = 0.04
        assertEquals(0.04, monitor.total(pid), 0.0001, "应按 CHAPTER_WRITING 模型 pricing 计价");

        observed.complete(LlmRequest.builder().taskType(LlmTaskType.GENERAL).build());
        // GENERAL: 2000*0.001/1000 + 500*0.002/1000 = 0.002 + 0.001 = 0.003；累计 0.043
        assertEquals(0.043, monitor.total(pid), 0.0001, "不同 taskType 应按各自模型 pricing 计价");

        EventContext.clear();
    }

    @Test
    void 超成本预算的写作任务应置PAUSED而非FAILED() throws Exception {
        // WritingPreferences 设很小预算 0.01 元
        WritingPreferences prefs = new WritingPreferences(pid);
        prefs.setCostBudgetYuan(0.01);
        repo.saveWritingPreferences(prefs);
        // CostBudgetMonitor 累计已超预算
        CostBudgetMonitor monitor = new CostBudgetMonitor();
        monitor.add(pid, 0.05);

        // mock orchestrator.runWritingLoop 返回 written(2) < maxChapters(10)
        NovelOrchestrator orch = mock(NovelOrchestrator.class);
        when(orch.runWritingLoop(eq(pid), eq(10), any())).thenReturn(2);

        WritingTaskExecutor executor = new WritingTaskExecutor(orch, repo, monitor);
        WritingTask task = new WritingTask(pid, 10);
        repo.saveWritingTask(task);

        // 反射调 private executeWriting（避免异步）
        Method m = WritingTaskExecutor.class.getDeclaredMethod("executeWriting", WritingTask.class, int.class);
        m.setAccessible(true);
        m.invoke(executor, task, 10);

        WritingTask reloaded = repo.findWritingTask(task.getId()).orElseThrow();
        assertEquals(WritingTask.Status.PAUSED, reloaded.getStatus(),
                "超预算应置 PAUSED（非 FAILED），实际=" + reloaded.getStatus());
        assertNotNull(reloaded.getErrorMessage(), "应记录预算超限信息");
    }

    @Test
    void 未超预算且写满应COMPLETED_成本逻辑不误伤正常完成() throws Exception {
        WritingPreferences prefs = new WritingPreferences(pid);
        prefs.setCostBudgetYuan(100.0); // 足够大
        repo.saveWritingPreferences(prefs);
        CostBudgetMonitor monitor = new CostBudgetMonitor();
        monitor.add(pid, 0.01); // 远低于预算

        NovelOrchestrator orch = mock(NovelOrchestrator.class);
        when(orch.runWritingLoop(eq(pid), eq(10), any())).thenReturn(10); // 写满

        WritingTaskExecutor executor = new WritingTaskExecutor(orch, repo, monitor);
        WritingTask task = new WritingTask(pid, 10);
        repo.saveWritingTask(task);

        Method m = WritingTaskExecutor.class.getDeclaredMethod("executeWriting", WritingTask.class, int.class);
        m.setAccessible(true);
        m.invoke(executor, task, 10);

        WritingTask reloaded = repo.findWritingTask(task.getId()).orElseThrow();
        assertEquals(WritingTask.Status.COMPLETED, reloaded.getStatus(),
                "未超预算且写满应 COMPLETED，成本逻辑不应误伤，实际=" + reloaded.getStatus());
    }

    // ====== p10-w1-05: 分类型退避 + 限流模型降级 ======

    @Test
    void 限流时网关降级到fallback模型重试() {
        // 主模型 CHAPTER_WRITING；fallback defaultModel 是不同端点
        LlmProperties props = mock(LlmProperties.class);
        ModelConfig primary = new ModelConfig();
        primary.setBaseUrl("https://main.api/v1");
        primary.setModel("deepseek-v4");
        ModelConfig fallback = new ModelConfig();
        fallback.setBaseUrl("http://localhost:8000/v1");
        fallback.setModel("qwen3-4b-wuxia");
        when(props.forTaskType("CHAPTER_WRITING")).thenReturn(primary);
        when(props.getDefaultModel()).thenReturn(fallback);

        // client：主模型抛 RateLimitException，第二次（fallback）成功（连续 stub）
        LlmClient client = mock(LlmClient.class);
        when(client.complete(any(), any()))
                .thenThrow(new RateLimitException("429"))
                .thenReturn(new LlmResponse("降级后正文", 100, 200));

        DefaultLlmGateway gateway = new DefaultLlmGateway(props, client);
        LlmResponse resp = gateway.complete(LlmRequest.builder()
                .taskType(LlmTaskType.CHAPTER_WRITING).temperature(0.8).maxTokens(2000).build());

        assertEquals("降级后正文", resp.getContent(), "主模型限流应降级到 fallback");
        verify(client, times(2)).complete(any(), any());
    }

    @Test
    void 无fallback时限流异常向上抛() {
        LlmProperties props = mock(LlmProperties.class);
        ModelConfig primary = new ModelConfig();
        primary.setBaseUrl("https://main.api/v1");
        primary.setModel("deepseek-v4");
        when(props.forTaskType("CHAPTER_WRITING")).thenReturn(primary);
        // defaultModel 与主模型相同 → 无可用 fallback
        when(props.getDefaultModel()).thenReturn(primary);

        LlmClient client = mock(LlmClient.class);
        when(client.complete(any(), any())).thenThrow(new RateLimitException("429"));

        DefaultLlmGateway gateway = new DefaultLlmGateway(props, client);
        assertThrows(RateLimitException.class, () ->
                gateway.complete(LlmRequest.builder().taskType(LlmTaskType.CHAPTER_WRITING).build()),
                "无 fallback 时限流异常应向上抛，供写作循环做分类型退避");
    }
}
