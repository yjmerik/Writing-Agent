package com.writing.novel.memory;

import com.writing.novel.domain.AuthorDirective;
import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.ChapterSynopsis;
import com.writing.novel.domain.Outline;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.Volume;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.service.DirectiveContextService;
import com.writing.novel.spi.NovelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * p37-10：生成上下文实际消费有效作者要求。
 * 本章/全书/未来范围/撤销分别出现或不出现；HARD 超预算明确阻塞（请求不发送）；
 * 无意见时 authorDirectiveSection 为空（旧回放逐字不变）。
 */
class DirectiveContextInjectionTest {

    @TempDir
    Path tempDir;

    private SqliteNovelRepository repo;
    private ContextAssembler assembler;
    private DirectiveContextService selector;
    private String projectId;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(
                tempDir.resolve("injection.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        selector = new DirectiveContextService(repo);

        // 依赖 mock/stub（与既有 ContextAssembler 测试同款接线）
        com.writing.novel.memory.VectorRetrievalService vectorRetrieval =
                org.mockito.Mockito.mock(com.writing.novel.memory.VectorRetrievalService.class);
        com.writing.novel.memory.MemoryRevision revision = new com.writing.novel.memory.MemoryRevision();
        com.writing.novel.memory.KnowledgeBaseService knowledgeBase =
                new com.writing.novel.memory.KnowledgeBaseService(repo, vectorRetrieval, revision);
        com.writing.novel.memory.SummaryService summaryService =
                org.mockito.Mockito.mock(com.writing.novel.memory.SummaryService.class);
        com.writing.novel.memory.VectorRetrievalService vectorForAssembler =
                org.mockito.Mockito.mock(com.writing.novel.memory.VectorRetrievalService.class);
        org.mockito.Mockito.when(vectorForAssembler.search(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(java.util.List.of());
        com.writing.novel.service.EntityGraphService entityGraph =
                org.mockito.Mockito.mock(com.writing.novel.service.EntityGraphService.class);
        org.mockito.Mockito.when(entityGraph.recallConsistencySection(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyInt())).thenReturn("");
        com.writing.novel.service.ClimaxDiversityTracker climax =
                org.mockito.Mockito.mock(com.writing.novel.service.ClimaxDiversityTracker.class);
        org.mockito.Mockito.when(climax.generateRotationConstraint()).thenReturn("");
        com.writing.novel.service.NarrativeThreadTracker threads =
                org.mockito.Mockito.mock(com.writing.novel.service.NarrativeThreadTracker.class);
        org.mockito.Mockito.when(threads.getThreadSection(org.mockito.ArgumentMatchers.anyString())).thenReturn("");
        com.writing.novel.service.DiversityTracker diversity =
                org.mockito.Mockito.mock(com.writing.novel.service.DiversityTracker.class);
        org.mockito.Mockito.when(diversity.generateAllConstraints()).thenReturn("");
        com.writing.novel.service.ClicheDetector cliche =
                org.mockito.Mockito.mock(com.writing.novel.service.ClicheDetector.class);
        org.mockito.Mockito.when(cliche.generateForbiddenList(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyInt())).thenReturn("");

        assembler = new ContextAssembler(repo, knowledgeBase, summaryService, vectorForAssembler,
                entityGraph, climax, threads, diversity, cliche, revision);
        assembler.setDirectiveContextService(selector);

        Project project = new Project("注入项目", "概念", "武侠");
        repo.saveProject(project);
        projectId = project.getId();
        // 最小大纲（assemble 依赖）
        Outline outline = new Outline(projectId, "总纲");
        Volume volume = new Volume(projectId, 1, "第一卷", "卷摘要");
        for (int i = 1; i <= 6; i++) {
            volume.addSynopsis(new ChapterSynopsis(projectId, i, "第" + i + "章", "梗概" + i));
        }
        outline.addVolume(volume);
        repo.saveOutline(outline);
    }

    private AuthorDirective directive(AuthorDirective.Kind kind, Integer from, Integer to, String content) {
        AuthorDirective d = new AuthorDirective(projectId, kind, null, content);
        d.setScope(from, to);
        return d;
    }

    private String systemPromptAt(int chapterIndex) {
        WritingContext ctx = assembler.assemble(projectId, chapterIndex, "标题", "梗概", null);
        AtomicReference<String> prompt = new AtomicReference<>(ctx.toSystemPrompt());
        return prompt.get();
    }

    @Test
    void 本章_全书_未来_撤销分别出现或不出现() {
        repo.saveAuthorDirective(directive(AuthorDirective.Kind.HARD, 5, 7, "未来范围要求"));
        repo.saveAuthorDirective(directive(AuthorDirective.Kind.ONCE, 2, 2, "本章范围要求"));
        repo.saveAuthorDirective(directive(AuthorDirective.Kind.PREFERENCE, null, null, "全书要求"));
        AuthorDirective revoked = directive(AuthorDirective.Kind.HARD, null, null, "已撤销要求");
        repo.saveAuthorDirective(revoked);
        repo.revokeDirective(projectId, revoked.getId(), "作者撤销", 1);

        String promptAt4 = systemPromptAt(4);
        assertTrue(promptAt4.contains("全书要求"), "全书要求出现在任意章");
        assertTrue(promptAt4.contains("本章范围要求") == false, "本章范围（第2章）不出现在第4章");
        assertTrue(promptAt4.contains("未来范围要求") == false, "未来范围（5-7）不出现在第4章");
        assertTrue(promptAt4.contains("已撤销要求") == false, "撤销后不出现");

        String promptAt6 = systemPromptAt(6);
        assertTrue(promptAt6.contains("未来范围要求"), "第6章落在 5-7 范围内出现");
        assertTrue(promptAt6.contains("本章范围要求") == false);
    }

    @Test
    void HARD超预算明确阻塞且不产出可发送上下文() {
        for (int i = 0; i < 5; i++) {
            repo.saveAuthorDirective(directive(AuthorDirective.Kind.HARD, null, null,
                    ("硬性要求" + i + "：").repeat(400)));
        }
        // 极小预算（200 字符）→ HARD 截不下 → 服务报告阻塞 ID，不静默截断
        var selected = selector.select(projectId, 1, 200);
        assertEquals(5, selected.blockedHardIds().size(), "HARD 全部列入阻塞清单");
        assertTrue(selected.isEmpty(), "无可发送节");
        // 经 assemble（小预算）：阻塞转为明确异常，即“请求不发送”
        assertThrows(IllegalStateException.class,
                () -> assembler.assemble(projectId, 1, "标题", "梗概", null, 500));
    }

    @Test
    void 无意见时节为空_与旧上下文逐字一致() {
        WritingContext ctx = assembler.assemble(projectId, 1, "标题", "梗概", null);
        assertNull(ctx.getAuthorDirectiveSection(), "无意见时节为空");
        String prompt = ctx.toSystemPrompt();
        assertFalse(prompt.contains("【作者要求】"), "旧 prompt 不新增节");

        // 服务层：无意见 section 为 null
        assertTrue(selector.select(projectId, 1, 0).isEmpty());
    }

    @Test
    void 软意见超预算可截断并返回遗漏ID() {
        repo.saveAuthorDirective(directive(AuthorDirective.Kind.HARD, null, null, "硬性：每章雨夜"));
        repo.saveAuthorDirective(directive(AuthorDirective.Kind.PREFERENCE, null, null, "偏好：".repeat(400)));
        repo.saveAuthorDirective(directive(AuthorDirective.Kind.PREFERENCE, null, null, "偏好二：".repeat(400)));

        var selected = selector.select(projectId, 1, 2000);
        assertFalse(selected.isEmpty());
        assertTrue(selected.section().contains("硬性：每章雨夜"), "HARD 永不截断");
        assertTrue(selected.section().contains("偏好："), "预算内软意见保留");
        assertEquals(1, selected.omittedSoftIds().size(), "预算外软意见截断并报告遗漏 ID");
        assertTrue(selected.blockedHardIds().isEmpty());
        // 未注入服务的旧版装配不产生该节（无意见场景逐字一致由上一用例覆盖）
    }
}
