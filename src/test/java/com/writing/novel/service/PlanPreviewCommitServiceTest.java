package com.writing.novel.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.ChapterSynopsis;
import com.writing.novel.domain.Outline;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.Volume;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * p38-02/03（审计 P1-3）：计划确认必须绑定预览凭证。
 * 无 token/hash 的确认拒绝；目标数字与预览不符拒绝；来源变化（新增章）旧凭证失效；
 * 正确凭证确认后目标与大纲生效且处置落审计。
 */
class PlanPreviewCommitServiceTest {

    @TempDir
    Path tempDir;

    private SqliteNovelRepository repo;
    private PlanEditionService service;
    private String pid;

    @BeforeEach
    void setUp() {
        var cp = new ConnectionProvider(tempDir.resolve("plan.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        service = new PlanEditionService(repo);
        Project p = new Project("计划凭证", "c", "t");
        repo.saveProject(p);
        pid = p.getId();
        Outline outline = new Outline(pid, "总纲");
        Volume v = new Volume(pid, 1, "卷一", "摘要");
        for (int i = 1; i <= 24; i++) {
            v.addSynopsis(new ChapterSynopsis(pid, i, "第" + i + "章", "梗概" + i));
        }
        outline.addVolume(v);
        repo.saveOutline(outline);
    }

    @Test
    void 无凭证确认被拒绝() {
        // 预览 22
        Map<String, Object> preview = service.previewTarget(pid, 22);
        assertEquals(Boolean.FALSE, preview.get("applied"));
        assertNotNull(preview.get("previewId"));
        assertNotNull(preview.get("sourcePlanHash"));

        // 不提供 token/hash 直接确认 20 → 拒绝
        assertThrows(IllegalArgumentException.class,
                () -> service.confirmTarget(pid, null, null, 20, List.of(), "k1"),
                "缺 previewId 必须拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> service.confirmTarget(pid, (String) preview.get("previewId"), null, 20, List.of(), "k2"),
                "缺 sourcePlanHash 必须拒绝");

        // 目标与预览不符 → 拒绝（预览是 22，确认 20）
        assertThrows(IllegalStateException.class,
                () -> service.confirmTarget(pid, (String) preview.get("previewId"),
                        (String) preview.get("sourcePlanHash"), 20, List.of(), "k3"),
                "改目标数字不能沿用旧预览");

        // 拒绝后原状态不变
        assertEquals(24, outlineChapterCount(), "大纲未变");
    }

    @Test
    void 来源变化后旧凭证失效() {
        Map<String, Object> preview = service.previewTarget(pid, 22);
        // 来源变化：新增第 25 章大纲
        Outline outline = repo.findOutlineByProject(pid).orElseThrow();
        outline.getVolumes().get(0)
                .addSynopsis(new ChapterSynopsis(pid, 25, "第25章", "梗概25"));
        repo.saveOutline(outline);

        assertThrows(IllegalStateException.class,
                () -> service.confirmTarget(pid, (String) preview.get("previewId"),
                        (String) preview.get("sourcePlanHash"), 22, List.of(), "k4"),
                "计划变化后旧凭证必须失效");
        assertEquals(25, outlineChapterCount(), "失效确认不改动大纲");
    }

    @Test
    void 正确凭证确认生效且处置落审计() {
        Map<String, Object> preview = service.previewTarget(pid, 20);
        @SuppressWarnings("unchecked")
        List<Integer> pruned = (List<Integer>) preview.get("prunedChapters");
        assertEquals(List.of(21, 22, 23, 24), pruned);

        Map<String, Object> result = service.confirmTarget(pid,
                (String) preview.get("previewId"),
                (String) preview.get("sourcePlanHash"),
                20, List.of(), "key-confirm-1");
        assertEquals(Boolean.TRUE, result.get("applied"));

        assertEquals(20, outlineChapterCount(), "大纲裁剪到 20");
        var planChange = repo.findPlanChange(pid, (String) preview.get("previewId")).orElseThrow();
        assertEquals(com.writing.novel.domain.PlanChange.Status.CONFIRMED, planChange.getStatus());

        // 重复确认同一凭证 → 拒绝（已确认）
        assertThrows(IllegalStateException.class,
                () -> service.confirmTarget(pid, (String) preview.get("previewId"),
                        (String) preview.get("sourcePlanHash"), 20, List.of(), "key-confirm-2"));
    }

    @Test
    void 受影响承诺必须逐项处置() {
        // 埋一个预计 22 章回收的 mustResolve 伏笔
        var f = new com.writing.novel.domain.Foreshadowing(pid, "旧发夹真相", "ctx", 1);
        f.setExpectedResolveChapter(22);
        f.setMustResolve(true);
        repo.saveForeshadowing(f);

        Map<String, Object> preview = service.previewTarget(pid, 20);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> affected = (List<Map<String, Object>>) preview.get("affectedPromises");
        assertEquals(1, affected.size());

        // 缺处置 → 拒绝
        assertThrows(IllegalStateException.class,
                () -> service.confirmTarget(pid, (String) preview.get("previewId"),
                        (String) preview.get("sourcePlanHash"), 20, List.of(), "k5"),
                "mustResolve 承诺缺处置必须拒绝");

        // 逐项处置后成功
        var decision = new com.writing.novel.domain.PlanChange.PromiseDecision(
                java.util.UUID.randomUUID().toString(), (String) preview.get("previewId"),
                f.getId(), com.writing.novel.domain.PlanChange.PromiseDecision.ACTION_MIGRATE, 18, null);
        Map<String, Object> result = service.confirmTarget(pid,
                (String) preview.get("previewId"), (String) preview.get("sourcePlanHash"),
                20, List.of(decision), "k6");
        assertEquals(Boolean.TRUE, result.get("applied"));
    }

    private int outlineChapterCount() {
        return repo.findOutlineByProject(pid).orElseThrow().getVolumes().stream()
                .mapToInt(v -> v.getSynopses().size()).sum();
    }
}
