package com.writing.novel.service;

import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.Project;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.RevisionRequestRepository;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-013（方案 §12/F07/F09）：统一修订请求与范围批准。
 * 六类修改入口归一登记 RevisionRequest（基准版本/锚点/意图/保护项/来源）；
 * 范围批准（scopeDecision）与内容采纳（acceptance）分离记录。
 */
class RevisionRequestTest {

    @TempDir
    Path tempDir;

    private SqliteNovelRepository repo;
    private RevisionRequestRepository requestRepo;
    private RevisionRequestService service;
    private ChapterRevisionService revisionService;
    private String projectId;
    private Chapter chapter;

    @BeforeEach
    void setUp() {
        var cp = new ConnectionProvider(tempDir.resolve("rr.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        requestRepo = new RevisionRequestRepository(cp);
        service = new RevisionRequestService(requestRepo);
        revisionService = new ChapterRevisionService(repo, null, null, null, service);
        Project project = new Project("修订请求项目", "构思", "武侠");
        repo.saveProject(project);
        projectId = project.getId();
        chapter = new Chapter(projectId, 1, "第一章");
        chapter.setContent("初始正文，江砚把密信交到沈昭手中。".repeat(60));
        chapter.setStatus(Chapter.Status.DONE);
        repo.saveChapter(chapter);
    }

    private com.writing.novel.domain.RevisionRequest record(String origin) {
        return service.record(projectId, origin, "改成当场焚信，保留结尾原句",
                "[{\"chapterIndex\":8,\"anchor\":\"密信交接段\"}]",
                "{\"8\":" + repo.findChapterByIndex(projectId, 1).orElseThrow().getVersion() + "}",
                "[{\"chapterIndex\":8,\"span\":\"最后一段\",\"reason\":\"结尾原句不动\"}]");
    }

    @Test
    void 六类入口均可登记_字段完整() {
        for (String origin : List.of("DIRECT_EDIT", "PASSAGE_POLISH", "CHAPTER_REWRITE",
                "SETTING_CHANGE", "PLAN_ADJUST", "RESTORE")) {
            var r = service.record(projectId, origin, "意图-" + origin, "[]", "{}", "[]");
            assertNotNull(r.getId());
            assertEquals(origin, r.getOrigin().name());
            assertEquals("OPEN", r.getStatus().name());
            assertNotNull(r.getCreatedAt());
        }
        assertEquals(6, service.list(projectId).size());
        assertEquals(6, requestRepo.findByProject(projectId).size());
    }

    @Test
    void 范围批准与内容采纳分离_各有独立记录() {
        var r = record("CHAPTER_REWRITE");
        assertEquals("OPEN", r.getStatus().name());
        assertTrue(r.getScopeDecision() == null || r.getScopeDecision().isBlank(),
                "新请求尚无范围决定——批准前不得生成修复");

        service.approveScope(projectId, r.getId(), "批准：只修第 8/12/20 章，不动第 9 章支线");
        var approved = service.get(projectId, r.getId());
        assertEquals("SCOPE_APPROVED", approved.getStatus().name());
        assertTrue(approved.getScopeDecision().contains("只修第 8/12/20 章"));
        assertTrue(approved.getAcceptance() == null || approved.getAcceptance().isBlank(),
                "范围批准≠内容采纳——此时尚未采纳任何正文");

        service.accept(projectId, r.getId(), "{\"chapters\":[8,12,20],\"commandId\":\"cmd-1\"}");
        var done = service.get(projectId, r.getId());
        assertEquals("COMPLETED", done.getStatus().name());
        assertTrue(done.getAcceptance().contains("cmd-1"));
        assertTrue(done.getScopeDecision().contains("只修第 8/12/20 章"), "范围决定在采纳后仍可追溯");
    }

    @Test
    void 未批准范围的请求不可进入修复流程() {
        var r = record("CHAPTER_REWRITE");
        assertThrows(IllegalStateException.class,
                () -> service.requireScopeApproved(projectId, r.getId()),
                "OPEN 状态不得生成修复候选（范围批准先行）");
        service.approveScope(projectId, r.getId(), "已批准");
        assertDoesNotThrow(() -> service.requireScopeApproved(projectId, r.getId()));
    }

    @Test
    void 作者直接编辑自动登记并完成_基准版本绑定() {
        int version = repo.findChapterByIndex(projectId, 1).orElseThrow().getVersion();
        revisionService.edit(projectId, 1, version, "第一章",
                "作者改写后的正文，江砚当场焚毁了密信。".repeat(80), "DIRECT_EDIT");

        var requests = service.list(projectId);
        assertEquals(1, requests.size(), "作者直接编辑产生统一修订请求");
        var r = requests.get(0);
        assertEquals("DIRECT_EDIT", r.getOrigin().name());
        assertEquals("COMPLETED", r.getStatus().name(), "直存即完成（作者本人就是采纳决定）");
        assertNotNull(r.getAcceptance());
        assertTrue(r.getBaseVersionsJson().contains(String.valueOf(version)),
                "基准版本集记录编辑时的章稿版本");
    }

    @Test
    void 恢复旧稿自动登记RESTORE() {
        int version = repo.findChapterByIndex(projectId, 1).orElseThrow().getVersion();
        // 建第二版
        revisionService.edit(projectId, 1, version, "第一章",
                "第二版正文，内容完全不同。".repeat(80), "DIRECT_EDIT");
        int version2 = repo.findChapterByIndex(projectId, 1).orElseThrow().getVersion();
        // 恢复 v1
        revisionService.restore(projectId, 1, version, version2);
        var restores = service.list(projectId).stream()
                .filter(r -> "RESTORE".equals(r.getOrigin().name())).toList();
        assertEquals(1, restores.size(), "恢复旧稿登记为 RESTORE 请求");
        assertEquals("COMPLETED", restores.get(0).getStatus().name());
    }

    @Test
    void 取消请求保留记录() {
        var r = record("SETTING_CHANGE");
        service.cancel(projectId, r.getId(), "作者改主意");
        assertEquals("CANCELLED", service.get(projectId, r.getId()).getStatus().name());
        assertEquals(1, service.list(projectId).size(), "取消不删除记录（历史可追溯）");
    }
}
