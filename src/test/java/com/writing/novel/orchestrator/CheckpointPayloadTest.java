package com.writing.novel.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.domain.*;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * p18-02: checkpoint 审批质量 payload。
 * 原状：Checkpoint 仅 description 一句话，审批信息与内容分离在不同 tab。
 */
class CheckpointPayloadTest {

    @TempDir
    Path tempDir;
    private SqliteNovelRepository repo;
    private NovelOrchestrator orchestrator;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("cp.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        orchestrator = new NovelOrchestrator(repo, new ProjectService(repo),
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void checkpointPayload构建含字数预览标题() throws Exception {
        Project p = new Project("payload", "测试", "武侠");
        repo.saveProject(p);
        Chapter ch = new Chapter(p.getId(), 7, "第7章·夜雨");
        ch.setContent("雨打芭蕉。".repeat(60)); // 300 字

        String json = orchestrator.buildChapterCheckpointPayload(p.getId(), ch);
        JsonNode node = MAPPER.readTree(json);
        assertEquals(7, node.get("chapterIndex").asInt());
        assertEquals("第7章·夜雨", node.get("title").asText());
        assertTrue(node.get("wordCount").asInt() >= 200, "wordCount 应为正数字数");
        assertTrue(node.get("preview").asText().length() <= 250, "预览应截断");
        assertTrue(node.get("preview").asText().startsWith("雨打芭蕉"));
    }

    @Test
    void checkpointPayload持久化往返() {
        Project p = new Project("roundtrip", "测试", "武侠");
        repo.saveProject(p);
        Checkpoint cp = new Checkpoint(p.getId(), Checkpoint.Type.AFTER_CHAPTER, "第1章完成");
        cp.setPayload("{\"chapterIndex\":1,\"score\":8}");
        repo.saveCheckpoint(cp);

        var loaded = repo.findCheckpoint(cp.getId()).orElseThrow();
        assertNotNull(loaded.getPayload(), "payload 应持久化往返");
        assertTrue(loaded.getPayload().contains("\"score\":8"));
    }

    @Test
    void 读者评审后富集pending的checkpoint_payload含评分与未解决问题数() throws Exception {
        Project p = new Project("enrich", "测试", "武侠");
        repo.saveProject(p);
        Checkpoint cp = new Checkpoint(p.getId(), Checkpoint.Type.AFTER_CHAPTER, "第3章完成");
        cp.setPayload(orchestrator.buildChapterCheckpointPayload(p.getId(),
                new Chapter(p.getId(), 3, "第3章")));
        repo.saveCheckpoint(cp);
        // 一条本章 HIGH 未解决问题
        repo.saveContinuityIssue(new ContinuityIssue(p.getId(), 3,
                ContinuityIssue.Type.TIMELINE, ContinuityIssue.Severity.HIGH, "时间线矛盾"));
        // 本章读者评审 8 分
        ChapterReview review = new ChapterReview(p.getId(), 3);
        review.setOverallScore(8);
        repo.saveChapterReview(review);

        orchestrator.enrichChapterCheckpointPayload(p.getId(), 3);

        var loaded = repo.findCheckpoint(cp.getId()).orElseThrow();
        JsonNode node = MAPPER.readTree(loaded.getPayload());
        assertEquals(8, node.get("score").asInt(), "评审后 payload 应含评分");
        assertTrue(node.has("unresolvedHigh"), "payload 应含未解决 HIGH 数");
        assertEquals(1, node.get("unresolvedHigh").asInt());
    }
}
