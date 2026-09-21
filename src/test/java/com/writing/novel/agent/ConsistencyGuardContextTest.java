package com.writing.novel.agent;

import com.writing.novel.domain.Chapter;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.persistence.sqlite.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConsistencyGuardContextTest {
    @TempDir Path dir;

    @Test
    void 检查请求包含前章取证且不泄漏当前或未来稿件() {
        var cp = new ConnectionProvider(dir.resolve("context.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        var repo = new SqliteNovelRepository(cp);
        String evidence = "裴照微用竹钩挑起船板缝里半张湿会票。夹层里是盐霜。旧册纸页又翻，露出半枚画指。";
        for (int i = 1; i <= 4; i++) {
            var chapter = new Chapter("p", i, "章节" + i);
            chapter.setContent(i == 2 ? evidence : i == 1 ? "上一章已经取得会票" : "不得注入的数据库稿件" + i);
            repo.saveChapter(chapter);
        }
        var gateway = mock(LlmGateway.class);
        when(gateway.complete(any())).thenAnswer(call -> {
            var request = (com.writing.novel.llm.LlmRequest) call.getArgument(0);
            assertTrue(request.getSystemPrompt().contains(evidence), "检查器必须收到前章实际取证场景");
            assertTrue(request.getSystemPrompt().contains("上一章已经取得会票"));
            assertFalse(request.getSystemPrompt().contains("不得注入的数据库稿件"));
            return new LlmResponse("{\"abilityViolations\":[],\"evidenceProblems\":[],\"anachronisms\":[]}", 1, 1);
        });
        var guard = new ConsistencyGuard(gateway, repo, TestPromptHelper.create());
        assertTrue(guard.check("p", 3, "主角出示会票，回想此前的取证。".repeat(20)).isEmpty());
    }

    @Test
    void 前文有界且超长章保留结尾证据() {
        var cp = new ConnectionProvider(dir.resolve("bounded.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        var repo = new SqliteNovelRepository(cp);
        for (int i = 1; i <= 4; i++) {
            var chapter = new Chapter("p", i, "章节" + i);
            chapter.setContent(i < 3 ? "过旧正文不应全量注入" : "章首证据" + "中段".repeat(10000) + "章末取得关键密信");
            repo.saveChapter(chapter);
        }
        var gateway = mock(LlmGateway.class);
        when(gateway.complete(any())).thenAnswer(call -> {
            var request = (com.writing.novel.llm.LlmRequest) call.getArgument(0);
            assertTrue(request.getSystemPrompt().contains("章末取得关键密信"));
            assertTrue(request.getSystemPrompt().contains("章首证据"));
            assertFalse(request.getSystemPrompt().contains("过旧正文不应全量注入"));
            assertTrue(request.getSystemPrompt().length() < 14000, "不能随小说长度无界注入前文");
            return new LlmResponse("{\"abilityViolations\":[],\"evidenceProblems\":[],\"anachronisms\":[]}", 1, 1);
        });
        new ConsistencyGuard(gateway, repo, TestPromptHelper.create()).check("p", 5, "检查正文".repeat(60));
    }
}
