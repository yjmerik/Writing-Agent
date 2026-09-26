package com.writing.novel.service;

import com.writing.novel.domain.ChapterSynopsis;
import com.writing.novel.domain.Foreshadowing;
import com.writing.novel.domain.Outline;
import com.writing.novel.domain.PlanChange;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.Volume;
import com.writing.novel.domain.WritingPreferences;
import com.writing.novel.spi.NovelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NCS-092（第二轮旅程 A-5）：篇幅压缩必须处置"无有效回收位的关键承诺"。
 * 此前 analyze() 只把 expectedResolveChapter 超出目标者列为受影响——
 * 回收章号只写在描述里（expectedResolveChapter=null）的 mustResolve/HIGH
 * 承诺全部漏网（旅程中 8 条 HIGH 全部失位），债务延迟到成书门禁引爆。
 */
class PlanCompressionPromiseLedgerTest {

    @TempDir
    Path tempDir;
    private NovelRepository repo;
    private PlanEditionService service;
    private String pid;

    @BeforeEach
    void setUp() {
        com.writing.novel.persistence.sqlite.ConnectionProvider cp =
                new com.writing.novel.persistence.sqlite.ConnectionProvider(
                        tempDir.resolve("plan-compression-ledger.db").toString());
        new com.writing.novel.persistence.sqlite.SqliteSchemaInitializer(cp).initialize();
        repo = new com.writing.novel.persistence.sqlite.SqliteNovelRepository(cp);
        service = new PlanEditionService(repo, null, null);

        Project p = new Project("压缩台账", "构思", "武侠");
        repo.saveProject(p);
        pid = p.getId();

        Outline outline = new Outline(pid, "总纲");
        Volume volume = new Volume(pid, 1, "卷一", "摘要");
        for (int i = 1; i <= 24; i++) {
            volume.addSynopsis(new ChapterSynopsis(pid, i, "第" + i + "章", "梗概"));
        }
        outline.addVolume(volume);
        repo.saveOutline(outline);

        WritingPreferences prefs = new WritingPreferences(pid);
        prefs.setTargetChapters(24);
        repo.saveWritingPreferences(prefs);
    }

    private Foreshadowing promise(String description, boolean mustResolve,
                                  Foreshadowing.Priority priority, Integer resolveAt) {
        Foreshadowing f = new Foreshadowing(pid, description, "埋设语境", 1);
        f.setMustResolve(mustResolve);
        f.setPriority(priority);
        f.setExpectedResolveChapter(resolveAt);
        repo.saveForeshadowing(f);
        return f;
    }

    @Test
    void 无回收位的必须回收承诺列入受影响且缺少处置时拒绝确认() {
        Foreshadowing mustResolve = promise("埋-师父之死的真凶(HIGH)→15章回收。", true,
                Foreshadowing.Priority.HIGH, null);
        promise("埋-普通小伏笔(MEDIUM)→10章回收。", false, Foreshadowing.Priority.MEDIUM, null);

        Map<String, Object> preview = service.previewTarget(pid, 12);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> affected =
                (List<Map<String, Object>>) preview.get("affectedPromises");
        assertEquals(1, affected.size(), "仅无回收位的关键承诺受影响（普通伏笔不强制）");
        assertEquals(mustResolve.getId(), affected.get(0).get("id"));

        String previewId = (String) preview.get("previewId");
        String hash = (String) preview.get("sourcePlanHash");

        // 缺处置 → 拒绝
        IllegalStateException missing = assertThrows(IllegalStateException.class,
                () -> service.confirmTarget(pid, previewId, hash, 12, List.of(), null));
        assertTrue(missing.getMessage().contains("师父之死"), missing.getMessage());
    }

    @Test
    void 关键承诺MIGRATE后确认生效并更新回收位() {
        Foreshadowing high = promise("埋-鱼纹刀的来历(HIGH)→15章回收。", false,
                Foreshadowing.Priority.HIGH, 15);
        promise("埋-普通小伏笔(MEDIUM)→20章回收。", false, Foreshadowing.Priority.MEDIUM, 20);

        Map<String, Object> preview = service.previewTarget(pid, 12);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> affected =
                (List<Map<String, Object>>) preview.get("affectedPromises");
        assertEquals(2, affected.size(), "HIGH@15 超界 + MEDIUM@20 超界均受影响（既有行为）");
        assertTrue(affected.stream().anyMatch(m -> m.get("id").equals(high.getId())));

        String previewId = (String) preview.get("previewId");
        String hash = (String) preview.get("sourcePlanHash");
        List<PlanChange.PromiseDecision> decisions = affected.stream()
                .map(m -> new PlanChange.PromiseDecision("d-" + m.get("id"), previewId,
                        String.valueOf(m.get("id")), "MIGRATE", 12, "并入回收章"))
                .toList();
        Map<String, Object> result = service.confirmTarget(pid, previewId, hash, 12, decisions, null);
        assertEquals(true, result.get("applied"));
        Foreshadowing migrated = repo.findUnresolvedForeshadowings(pid).stream()
                .filter(f -> f.getId().equals(high.getId())).findFirst().orElseThrow();
        assertEquals(12, migrated.getExpectedResolveChapter(), "MIGRATE 后回收位更新");
    }

    @Test
    void OPEN_END处置必须说明理由() {
        promise("埋-铜尺刻字之谜(HIGH)→18章回收。", true, Foreshadowing.Priority.HIGH, null);
        Map<String, Object> preview = service.previewTarget(pid, 12);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> affected =
                (List<Map<String, Object>>) preview.get("affectedPromises");
        String previewId = (String) preview.get("previewId");
        String hash = (String) preview.get("sourcePlanHash");

        List<PlanChange.PromiseDecision> bad = affected.stream()
                .map(m -> new PlanChange.PromiseDecision("d-" + m.get("id"), previewId,
                        String.valueOf(m.get("id")), "OPEN_END", null, null))
                .toList();
        assertThrows(IllegalStateException.class,
                () -> service.confirmTarget(pid, previewId, hash, 12, bad, null),
                "OPEN_END 无理由必须拒绝");

        List<PlanChange.PromiseDecision> good = affected.stream()
                .map(m -> new PlanChange.PromiseDecision("d2-" + m.get("id"), previewId,
                        String.valueOf(m.get("id")), "OPEN_END", null,
                        "作者决定该线以留白方式收束，不作强回收"))
                .toList();
        Map<String, Object> result = service.confirmTarget(pid, previewId, hash, 12, good, null);
        assertEquals(true, result.get("applied"));
        assertFalse(repo.findUnresolvedForeshadowings(pid).isEmpty());
    }
}
