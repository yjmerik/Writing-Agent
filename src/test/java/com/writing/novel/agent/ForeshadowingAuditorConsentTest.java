package com.writing.novel.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.domain.ChapterSynopsis;
import com.writing.novel.domain.Foreshadowing;
import com.writing.novel.domain.Outline;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.Volume;
import com.writing.novel.domain.WritingPreferences;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.prompt.PromptTemplateService;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * NCS-082（听雪刀 P1-4）：伏笔审计口径收紧。
 * - 升级为 HIGH 时 mustResolve 缺省不再默认 true（此前 LLM 漏标即全员必须回收，62 条通胀来源）；
 * - 全书总章数口径优先 prefs.targetChapters（压缩后目标），不再用被回收卷推高的大纲最大章号。
 */
class ForeshadowingAuditorConsentTest {

    @TempDir
    Path tempDir;
    private SqliteNovelRepository repo;
    private LlmGateway gateway;
    private PromptTemplateService promptService;
    private ForeshadowingImportanceAuditor auditor;
    private String pid;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("auditor.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        gateway = mock(LlmGateway.class);
        promptService = mock(PromptTemplateService.class);
        when(promptService.render(anyString(), any())).thenReturn("prompt");
        auditor = new ForeshadowingImportanceAuditor(gateway, repo, promptService);
        Project p = new Project("审计书", "构思", "武侠");
        repo.saveProject(p);
        pid = p.getId();
    }

    @Test
    void 升级HIGH未明确标注mustResolve时不置必须回收() {
        Foreshadowing f = new Foreshadowing(pid, "氛围伏笔", "第2章埋设", 2);
        f.setPriority(Foreshadowing.Priority.MEDIUM);
        repo.saveForeshadowing(f);

        when(gateway.complete(any(LlmRequest.class))).thenReturn(new LlmResponse(
                "{\"audits\":[{\"index\":1,\"priority\":\"HIGH\"}]}", 50, 100));

        int upgraded = auditor.audit(pid);

        assertEquals(1, upgraded);
        Foreshadowing saved = repo.findUnresolvedForeshadowings(pid).stream()
                .filter(x -> x.getDescription().equals("氛围伏笔")).findFirst().orElseThrow();
        assertEquals(Foreshadowing.Priority.HIGH, saved.getPriority());
        assertFalse(saved.isMustResolve(),
                "LLM 未明确标注 mustResolve 时不得默认进入必须回收（缺省通胀来源）");
    }

    @Test
    void 升级HIGH明确标注mustResolve时照常生效() {
        Foreshadowing f = new Foreshadowing(pid, "主线信物", "第2章埋设", 2);
        f.setPriority(Foreshadowing.Priority.MEDIUM);
        repo.saveForeshadowing(f);

        when(gateway.complete(any(LlmRequest.class))).thenReturn(new LlmResponse(
                "{\"audits\":[{\"index\":1,\"priority\":\"HIGH\",\"mustResolve\":true,\"expectedResolveChapter\":9}]}", 50, 100));

        auditor.audit(pid);

        Foreshadowing saved = repo.findUnresolvedForeshadowings(pid).stream()
                .filter(x -> x.getDescription().equals("主线信物")).findFirst().orElseThrow();
        assertTrue(saved.isMustResolve(), "明确标注的必须回收照常生效");
        assertEquals(9, saved.getExpectedResolveChapter());
    }

    @Test
    void 总章数口径优先压缩后目标而非大纲最大章号() {
        WritingPreferences prefs = new WritingPreferences(pid);
        prefs.setTargetChapters(10);
        repo.saveWritingPreferences(prefs);
        // 大纲最大章号 18（含历史追加的回收卷）——不得作为审计总数口径
        Outline outline = new Outline(pid, "总纲");
        Volume v = new Volume(pid, 1, "卷1", "");
        for (int i = 1; i <= 18; i++) {
            v.getSynopses().add(new ChapterSynopsis(pid, i, "第" + i + "章", "梗概"));
        }
        outline.getVolumes().add(v);
        repo.saveOutline(outline);

        Foreshadowing f = new Foreshadowing(pid, "任意伏笔", "第2章埋设", 2);
        f.setPriority(Foreshadowing.Priority.MEDIUM);
        repo.saveForeshadowing(f);

        when(gateway.complete(any(LlmRequest.class))).thenReturn(new LlmResponse(
                "{\"audits\":[{\"index\":1,\"priority\":\"LOW\"}]}", 50, 100));

        auditor.audit(pid);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(promptService).render(eq("foreshadowing-audit"), captor.capture());
        assertEquals("10", captor.getValue().get("totalChapters"),
                "总数口径应取压缩后目标章数（target=10），而非大纲最大章号 18");
    }
}
