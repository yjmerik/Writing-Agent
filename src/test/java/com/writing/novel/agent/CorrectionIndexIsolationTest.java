package com.writing.novel.agent;

import com.writing.novel.domain.Chapter;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.memory.VectorRetrievalService;
import com.writing.novel.spi.NovelRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * NCS-039: 拒收候选不改变当前检索——纠偏重写生成期不建索引；
 * 索引在调用方接受（正文落库）后按接受稿重建。
 */
class CorrectionIndexIsolationTest {

    private com.writing.novel.memory.ContextAssembler stubAssembler() {
        var ctx = new com.writing.novel.memory.WritingContext();
        return new com.writing.novel.memory.ContextAssembler(
                null, null, null, null, null, null, null, null, null) {
            @Override public com.writing.novel.memory.WritingContext assemble(
                    String pid, int ci, String ct, String cs, String wn) { return ctx; }
            @Override public com.writing.novel.memory.WritingContext assemble(
                    String pid, int ci, String ct, String cs, String wn, int budget) { return ctx; }
        };
    }

    @Test
    void 重写生成期不动向量索引_接受时重建(@TempDir Path tempDir) {
        var cp = new com.writing.novel.persistence.sqlite.ConnectionProvider(
                tempDir.resolve("cii.db").toString());
        new com.writing.novel.persistence.sqlite.SqliteSchemaInitializer(cp).initialize();
        var repo = new com.writing.novel.persistence.sqlite.SqliteNovelRepository(cp);
        var p = new com.writing.novel.domain.Project("权谋", "构思", "历史");
        repo.saveProject(p);
        repo.saveWritingPreferences(new com.writing.novel.domain.WritingPreferences(p.getId()));
        var original = "第1章初稿正文，账房与残账的线索。".repeat(150);
        Chapter ch = new Chapter(p.getId(), 1, "第一章 残账");
        ch.setContent(original);
        repo.saveChapter(ch);

        VectorRetrievalService vectorRetrieval = mock(VectorRetrievalService.class);
        LlmGateway gateway = new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest req) {
                return new LlmResponse("重写稿正文，修正了时间线矛盾并补足铺垫。".repeat(160), 200, 900);
            }
            @Override public void streamComplete(LlmRequest req, StreamHandler h) {}
        };
        ChapterWriterAgent agent = new ChapterWriterAgent(gateway, stubAssembler(), repo, vectorRetrieval, TestPromptHelper.create(), null, null, null);

        // 生成候选：索引不得被触碰
        String candidate = agent.rewriteForCorrection(p.getId(), 1, "第一章 残账",
                "本章梗概", null, "修正时间线");
        assertNotNull(candidate);
        verify(vectorRetrieval, never()).indexBatch(anyList());
        verify(vectorRetrieval, never()).replaceByPrefixAtomic(anyString(), anyList());
        verify(vectorRetrieval, never()).removeByIdPrefix(anyString());

        // 接受（落库后按接受稿重建索引）
        agent.reindexChapter(p.getId(), 1, candidate);
        verify(vectorRetrieval, times(1)).replaceByPrefixAtomic(anyString(), anyList());
    }

    @Test
    void 拒收路径无需恢复_原索引天然完整(@TempDir Path tempDir) {
        var cp = new com.writing.novel.persistence.sqlite.ConnectionProvider(
                tempDir.resolve("cii2.db").toString());
        new com.writing.novel.persistence.sqlite.SqliteSchemaInitializer(cp).initialize();
        var repo = new com.writing.novel.persistence.sqlite.SqliteNovelRepository(cp);
        var p = new com.writing.novel.domain.Project("权谋", "构思", "历史");
        repo.saveProject(p);
        repo.saveWritingPreferences(new com.writing.novel.domain.WritingPreferences(p.getId()));
        Chapter ch = new Chapter(p.getId(), 1, "第一章 残账");
        ch.setContent("初稿正文。".repeat(400));
        repo.saveChapter(ch);

        VectorRetrievalService vectorRetrieval = mock(VectorRetrievalService.class);
        // 重写稿过短 → 调用方长度保护拒收
        LlmGateway gateway = new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest req) {
                return new LlmResponse("太短。", 10, 5);
            }
            @Override public void streamComplete(LlmRequest req, StreamHandler h) {}
        };
        ChapterWriterAgent agent = new ChapterWriterAgent(gateway, stubAssembler(), repo, vectorRetrieval, TestPromptHelper.create(), null, null, null);
        String candidate = agent.rewriteForCorrection(p.getId(), 1, "第一章 残账", "梗概", null, "修正");
        verify(vectorRetrieval, never()).replaceByPrefixAtomic(anyString(), anyList());
        verify(vectorRetrieval, never()).indexBatch(anyList());
    }
}
