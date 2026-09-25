package com.writing.novel.web;

import com.writing.novel.domain.*;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.service.WorkspaceViewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * NCS-005（方案 §15）：WorkspaceView 统一读取协议。
 * 单一端点聚合 identity / contentStatus / run / review（含 unknown/stale 计数）/
 * availableActions（allowed + reason）——前端不再从多个轮询结果拼装互相矛盾的状态。
 */
class WorkspaceViewTest {

    @TempDir
    Path dir;

    private SqliteNovelRepository repo;
    private MockMvc mvc;
    private String url;

    @BeforeEach
    void setup() {
        var cp = new ConnectionProvider(dir.resolve("wv.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        mvc = MockMvcBuilders.standaloneSetup(new WorkspaceViewController(new WorkspaceViewService(repo)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void 空项目_各区块语义正确_动作带原因() throws Exception {
        Project project = new Project("空项目", "构思", "武侠");
        repo.saveProject(project);
        url = "/api/projects/" + project.getId() + "/workspace-view";

        mvc.perform(get(url))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identity.projectId").value(project.getId()))
                .andExpect(jsonPath("$.identity.state").value("CONCEIVING"))
                .andExpect(jsonPath("$.identity.revisionOfView").exists())
                .andExpect(jsonPath("$.contentStatus.currentChapter").doesNotExist())
                .andExpect(jsonPath("$.contentStatus.totalChapters").value(0))
                .andExpect(jsonPath("$.contentStatus.pendingCandidates").value(0))
                .andExpect(jsonPath("$.run").doesNotExist())
                .andExpect(jsonPath("$.review.activeBarriers").value(0))
                .andExpect(jsonPath("$.review.pendingCheckpoints").value(0))
                .andExpect(jsonPath("$.review.unknownChecks").value(0))
                .andExpect(jsonPath("$.review.staleIssues").value(0))
                .andExpect(jsonPath("$.availableActions.length()").value(5))
                .andExpect(jsonPath("$.availableActions[?(@.action=='ADVANCE')].allowed").value(true))
                .andExpect(jsonPath("$.availableActions[?(@.action=='WRITE_CHAPTER')].allowed").value(false))
                .andExpect(jsonPath("$.availableActions[?(@.action=='WRITE_CHAPTER')].reason").isNotEmpty())
                .andExpect(jsonPath("$.availableActions[?(@.action=='FINISH')].allowed").value(false))
                .andExpect(jsonPath("$.availableActions[?(@.action=='FINISH')].reason").isNotEmpty());
    }

    @Test
    void 有稿项目_内容任务审阅区块聚合一致() throws Exception {
        Project project = new Project("有稿项目", "构思", "武侠");
        project.setState(NovelState.WRITING);
        project.setCurrentChapterIndex(2);
        repo.saveProject(project);
        String pid = project.getId();

        Chapter ch1 = new Chapter(pid, 1, "第一章");
        ch1.setContent("第一章正文。".repeat(100));
        ch1.setStatus(Chapter.Status.DONE);
        repo.saveChapter(ch1);
        Chapter ch2 = new Chapter(pid, 2, "第二章");
        ch2.setContent("第二章正文。".repeat(100));
        ch2.setStatus(Chapter.Status.DONE);
        repo.saveChapter(ch2);
        // 第二章修订推进到 v2（setContent 自增版本），并带派生刷新待核对
        ch2.setContent("第二章修订后的正文。".repeat(100));
        repo.saveChapter(ch2);

        // 未解决且绑定旧稿版本的 issue：第一章后续修订到 v2 → 绑定 v1 的问题过期
        ContinuityIssue issue = new ContinuityIssue(pid, 1,
                ContinuityIssue.Type.TIMELINE, ContinuityIssue.Severity.HIGH, "旧稿问题");
        issue.setChapterVersion(1);
        repo.saveContinuityIssue(issue);
        ch1.setContent("第一章修订后的正文。".repeat(100));
        repo.saveChapter(ch1);

        // 人审屏障 + 待审检查点
        ReviewBarrier barrier = new ReviewBarrier(pid, 2, 2, "待人工复核");
        repo.saveReviewBarrier(barrier);
        Checkpoint checkpoint = new Checkpoint(pid, Checkpoint.Type.AFTER_CHAPTER, "审核第二章");
        repo.saveCheckpoint(checkpoint);

        // 运行中任务 + READY 候选
        WritingTask task = new WritingTask(pid, 3, WritingTask.Type.WRITE_ALL);
        task.setChaptersWritten(2);
        repo.saveWritingTask(task);
        RevisionProposal proposal = new RevisionProposal(java.util.UUID.randomUUID().toString(), pid,
                RevisionProposal.TargetType.CHAPTER, pid, 2, null, 1, "hash-1",
                "修改第二章", "{}", "{}", "摘要",
                RevisionProposal.Status.READY, "req-key-1", null,
                Instant.now(), Instant.now());
        repo.saveProposal(proposal);

        url = "/api/projects/" + pid + "/workspace-view";
        mvc.perform(get(url))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contentStatus.currentChapter.index").value(2))
                // setContent 每次自增版本：初稿1 + 修订2 + 派生pending标记保存3
                .andExpect(jsonPath("$.contentStatus.currentChapter.version").value(3))
                .andExpect(jsonPath("$.contentStatus.totalChapters").value(2))
                .andExpect(jsonPath("$.contentStatus.doneChapters").value(2))
                .andExpect(jsonPath("$.contentStatus.pendingCandidates").value(1))
                .andExpect(jsonPath("$.run.taskId").value(task.getId()))
                .andExpect(jsonPath("$.run.status").value("RUNNING"))
                .andExpect(jsonPath("$.run.chaptersWritten").value(2))
                .andExpect(jsonPath("$.review.activeBarriers").value(1))
                .andExpect(jsonPath("$.review.pendingCheckpoints").value(1))
                .andExpect(jsonPath("$.review.staleIssues").value(1))
                // WRITING 但存在待审检查点 → WRITE_CHAPTER 被阻止且带原因
                .andExpect(jsonPath("$.availableActions[?(@.action=='WRITE_CHAPTER')].allowed").value(false))
                .andExpect(jsonPath("$.availableActions[?(@.action=='WRITE_CHAPTER')].reason").isNotEmpty());
    }

    @Test
    void 项目不存在返回400() throws Exception {
        mvc.perform(get("/api/projects/no-such/workspace-view"))
                .andExpect(status().isBadRequest());
    }
}
