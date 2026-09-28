package com.writing.novel.agent;

import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.StoryFact;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.persistence.sqlite.SqliteStoryCanon;
import com.writing.novel.persistence.sqlite.StoryFactRepository;
import com.writing.novel.spi.StoryCanon;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-099: 重写派生数据按稿回滚——旧稿收割的事实随重写被撤回（RETIRED），
 * 新稿事实带 sourceChapterVersion 落库 ACTIVE 唯一；人工补丁与同稿重复收割不受影响。
 */
class RewriteDerivedRollbackTest {

    @TempDir
    Path tempDir;
    private StoryFactRepository factRepo;
    private StoryCanon canon;
    private final AtomicReference<String> nextResponse = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("rollback.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        new SqliteNovelRepository(cp).saveProject(new Project("回滚测试", "构思", "wuxia"));
        factRepo = new StoryFactRepository(cp);
        canon = new SqliteStoryCanon(factRepo);
    }

    private FactHarvesterAgent agent() {
        LlmGateway gateway = new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest req) {
                return new LlmResponse(nextResponse.get(), 10, 100);
            }
            @Override public void streamComplete(LlmRequest req, StreamHandler h) {}
        };
        return new FactHarvesterAgent(gateway, canon, TestPromptHelper.create());
    }

    private Chapter chapter(int version) {
        Chapter ch = new Chapter("p-rollback", 3, "第三章");
        // 对齐生产路径：ctor v1 + setContent 自增 → v2；重写一次 → v3
        ch.setContent("正文".repeat(1200));
        while (ch.getVersion() < version) ch.setContent("正文".repeat(1200));
        return ch;
    }

    @Test
    void 旧稿事实随重写被撤回_新稿事实ACTIVE唯一() {
        // v2 稿：林凡获得宝剑
        nextResponse.set("""
                [{"type":"OBJECT_STATE","subject":"林凡","assertion":"获得寒霜古剑"},
                 {"type":"CHARACTER_STATE","subject":"林凡","assertion":"修为炼气三层"}]
                """);
        agent().harvest("p-rollback", chapter(2));
        assertTrue(canon.recall("p-rollback").stream()
                        .anyMatch(f -> f.getAssertion().contains("寒霜古剑")),
                "v2 事实应 ACTIVE");
        assertEquals(2, canon.recall("p-rollback").stream()
                        .filter(f -> f.getAssertion().contains("寒霜古剑")).findFirst().orElseThrow()
                        .getSourceChapterVersion(),
                "新事实应带稿版本号（此前恒 0）");

        // 重写到 v3：宝剑情节被删除，只剩修为推进
        nextResponse.set("""
                [{"type":"CHARACTER_STATE","subject":"林凡","assertion":"修为炼气四层"}]
                """);
        agent().harvest("p-rollback", chapter(3));

        var active = canon.recall("p-rollback");
        assertTrue(active.stream().noneMatch(f -> f.getAssertion().contains("寒霜古剑")),
                "旧稿（v2）事实应被撤回，不得继续 ACTIVE 污染长程记忆");
        assertTrue(active.stream().anyMatch(f -> f.getAssertion().contains("炼气四层")),
                "新稿事实 ACTIVE");
        assertEquals(3, active.stream().filter(f -> f.getAssertion().contains("炼气四层"))
                        .findFirst().orElseThrow().getSourceChapterVersion());
        // 审计不删除：RETIRED 保留在 findAll
        assertTrue(canon.findAll("p-rollback").stream()
                .anyMatch(f -> f.getAssertion().contains("寒霜古剑")
                        && f.getStatus() == StoryFact.Status.RETIRED));
    }

    @Test
    void 人工补丁不受撤回影响_同稿重复收割幂等() {
        canon.applyHumanPatch("p-rollback", StoryFact.FactType.WORLD_RULE, "寒霜古剑",
                "古剑认主不可转授", 3, 5);

        nextResponse.set("""
                [{"type":"CHARACTER_STATE","subject":"林凡","assertion":"抵达渡口"}]
                """);
        agent().harvest("p-rollback", chapter(2));
        assertTrue(canon.recall("p-rollback").stream()
                .anyMatch(f -> f.getAssertion().contains("古剑认主不可转授")),
                "人工补丁不得被撤回");

        // 同稿（v2）重复收割：不撤回自己版本的事实
        agent().harvest("p-rollback", chapter(2));
        assertTrue(canon.recall("p-rollback").stream()
                .anyMatch(f -> f.getAssertion().contains("抵达渡口")
                        && f.getSourceChapterVersion() == 2));
    }
}
