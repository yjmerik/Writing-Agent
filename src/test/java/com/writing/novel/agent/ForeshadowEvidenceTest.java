package com.writing.novel.agent;

import com.writing.novel.domain.Foreshadowing;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * p32-08（体验报告 #4/P1）：伏笔回收必须携带证据。
 * 复现：铜牌日期差第10章尚未解释，detector 把"再次出现七·十五"当回收置 resolved
 * （reason 字段被解析后完全丢弃）。修复后：detector 输出的 reason 作为回收证据
 * 落库（resolve_evidence）；无证据（reason 空白）不得置 resolved。
 */
class ForeshadowEvidenceTest {

    @TempDir
    Path tempDir;
    private SqliteNovelRepository repo;
    private String pid;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("fsev.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        Project p = new Project("伏笔证据", "概念", "悬疑");
        repo.saveProject(p);
        pid = p.getId();
    }

    private Foreshadowing plant(String description, int atChapter) {
        Foreshadowing f = new Foreshadowing(pid, description, "埋设上下文", atChapter);
        repo.saveForeshadowing(f);
        return f;
    }

    private ForeshadowingResolutionDetector detectorReturning(String json) {
        LlmGateway gw = new LlmGateway() {
            @Override
            public LlmResponse complete(LlmRequest req) {
                return new LlmResponse(json, 50, 50);
            }

            @Override
            public void streamComplete(LlmRequest req, StreamHandler h) {
            }
        };
        return new ForeshadowingResolutionDetector(gw, repo, TestPromptHelper.create());
    }

    private Foreshadowing reload(Foreshadowing f) {
        return repo.findForeshadowingsByProject(pid).stream()
                .filter(x -> x.getId().equals(f.getId())).findFirst().orElseThrow();
    }

    @Test
    void 带证据的回收判定落库resolve_evidence() {
        Foreshadowing f = plant("铜牌上的日期与档案差一天", 10);
        int resolved = detectorReturning(
                "{\"resolved\":[{\"description\":\"铜牌上的日期与档案差一天\","
                        + "\"reason\":\"第12章林一舟对照电报原件，揭示日期差源于电报误译，谜团揭晓\"}]}")
                .detectAndResolve(pid, 12, "正文：电报原件证实误译……");

        assertEquals(1, resolved);
        Foreshadowing stored = reload(f);
        assertTrue(stored.isResolved());
        assertNotNull(stored.getResolveEvidence(), "回收证据必须落库可追溯");
        assertTrue(stored.getResolveEvidence().contains("电报误译"),
                "证据内容应为 detector 给出的 reason 文本，实际: " + stored.getResolveEvidence());
    }

    @Test
    void 无证据的回收判定不得置resolved() {
        // 复现报告场景：只是再次出现"七·十五"，没有解释——旧逻辑 reason 缺失也置 resolved
        Foreshadowing f = plant("七·十五事件的真正原因", 10);
        int resolved = detectorReturning(
                "{\"resolved\":[{\"description\":\"七·十五事件的真正原因\",\"reason\":\"\"}]}")
                .detectAndResolve(pid, 22, "正文：墙上的日期依然是七·十五。");

        assertEquals(0, resolved, "无证据不得判定回收");
        assertNull(reload(f).getResolveEvidence());
        assertTrue(!reload(f).isResolved(), "伏笔必须保持未回收");

        // 缺 reason 字段同样拒绝
        int resolved2 = detectorReturning(
                "{\"resolved\":[{\"description\":\"七·十五事件的真正原因\"}]}")
                .detectAndResolve(pid, 23, "正文：他又看了一眼那张日期纸。");
        assertEquals(0, resolved2);
        assertTrue(!reload(f).isResolved());
    }

    @Test
    void 人工回收保留既有用法并标注来源() {
        Foreshadowing f = plant("红回形针的含义", 1);
        f.resolve(5);
        repo.saveForeshadowing(f);
        assertTrue(reload(f).isResolved());
        assertNotNull(reload(f).getResolveEvidence(), "人工 resolve 也应带来源标注");
        assertTrue(reload(f).getResolveEvidence().contains("人工"));
    }
}
