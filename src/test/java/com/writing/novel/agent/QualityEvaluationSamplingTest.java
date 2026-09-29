package com.writing.novel.agent;

import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.Project;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-126: 卷末评价采样覆盖——30 章卷分层采样 ≥20%（旧：3 章各前 500 字 <2%），
 * 关键章取头尾节选，附覆盖率声明。
 */
class QualityEvaluationSamplingTest {

    @TempDir
    Path tempDir;
    private SqliteNovelRepository repository;
    private final AtomicReference<String> lastUserMessage = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("sampling.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repository = new SqliteNovelRepository(cp);
    }

    private void seedChapters(String pid, int count) {
        for (int i = 1; i <= count; i++) {
            Chapter ch = new Chapter(pid, i, "第" + i + "章");
            StringBuilder content = new StringBuilder("第" + i + "章的开头内容，基调如常。");
            while (content.length() < 2500) content.append("平推的正文段落。");
            content.append("——这是第").append(i).append("章的结尾钩子。");
            ch.setContent(content.toString());
            repository.saveChapter(ch);
        }
    }

    private QualityEvaluationAgent agent() {
        LlmGateway gateway = new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest req) {
                lastUserMessage.set(req.getUserMessage());
                return new LlmResponse(
                        "{\"scores\":{},\"overallScore\":7,\"summary\":\"s\",\"issues\":[]}", 10, 50);
            }
            @Override public void streamComplete(LlmRequest req, StreamHandler h) {}
        };
        return new QualityEvaluationAgent(gateway, repository, TestPromptHelper.create());
    }

    @Test
    void 三十章卷_采样覆盖至少六成以上且声明覆盖率() {
        var project = new Project("采样30", "构思", "wuxia");
        repository.saveProject(project);
        seedChapters(project.getId(), 30);
        var report = agent().evaluate(project.getId());
        assertNotNull(report);
        String prompt = lastUserMessage.get();
        assertTrue(prompt.contains("覆盖率"), "必须携带覆盖率声明");
        // 采样数 = max(3, ceil(30/5)=6)，cap 10；末章保底可 +1 → ≥6 章（≥20%）
        assertTrue(prompt.matches("(?s).*证据覆盖 [6-9] 章.*")
                        || prompt.contains("证据覆盖 10 章"),
                "30 章至少分层采样 6 章: "
                + prompt.substring(prompt.indexOf("共 30"), prompt.indexOf("共 30") + 60));
        // 分层：非连续章节（stride=5 → 第1/6/11/16/21/26 章）
        assertTrue(prompt.contains("第11章"), "中层章被覆盖（旧实现只有 1/15/30）");
        // 关键章（首）带结尾节选标记
        assertTrue(prompt.contains("开头+结尾节选"), "首/末章取头尾节选");
    }

    @Test
    void 短卷_全部三章采样() {
        var project = new Project("采样3", "构思", "wuxia");
        repository.saveProject(project);
        seedChapters(project.getId(), 3);
        agent().evaluate(project.getId());
        String prompt = lastUserMessage.get();
        assertTrue(prompt.contains("证据覆盖 3 章"));
        assertTrue(prompt.contains("覆盖率 100%"));
    }

    @Test
    void 末章采样覆盖卷尾() {
        var project = new Project("采样尾", "构思", "wuxia");
        repository.saveProject(project);
        seedChapters(project.getId(), 12);
        agent().evaluate(project.getId());
        String prompt = lastUserMessage.get();
        assertTrue(prompt.contains("第12章"), "末章必须入选（stride 环绕）");
    }
}
