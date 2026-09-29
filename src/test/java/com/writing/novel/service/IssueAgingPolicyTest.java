package com.writing.novel.service;

import com.writing.novel.domain.ContinuityIssue;
import com.writing.novel.domain.Project;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-124: issue 老化复验治理——HIGH 归档前置条件：
 * 无同型复发（检查器复验未复现）才可老化；复发中的矛盾不得用遗忘替代修复。
 */
class IssueAgingPolicyTest {

    @TempDir
    Path tempDir;
    private SqliteNovelRepository repository;
    private ArchiveCompactor compactor;
    private String pid;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("aging.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repository = new SqliteNovelRepository(cp);
        compactor = new ArchiveCompactor(repository);
        var project = new Project("老化", "构思", "wuxia");
        repository.saveProject(project);
        pid = project.getId();
    }

    private ContinuityIssue high(String desc, int chapter) {
        return new ContinuityIssue(pid, chapter, ContinuityIssue.Type.TIMELINE,
                ContinuityIssue.Severity.HIGH, desc);
    }

    @Test
    void 无复发的旧HIGH可老化() {
        repository.saveContinuityIssue(high("年龄倒退：第20章说三十岁", 20));
        compactor.compact(pid, 60);   // 20 < 60-30=30，且无复发
        assertTrue(repository.findUnresolvedIssues(pid).isEmpty(), "无复发旧 HIGH 允许老化");
    }

    @Test
    void 窗口内同型复发_HIGH不得老化() {
        repository.saveContinuityIssue(high("年龄倒退：第20章说三十岁", 20));
        // 复发：同 type + 描述前 30 字相同，窗口内（第 35 章，>= 30）
        repository.saveContinuityIssue(high("年龄倒退：第20章说三十岁（复验复现）", 35));
        compactor.compact(pid, 60);
        assertEquals(2, repository.findUnresolvedIssues(pid).size(),
                "复验复现中的矛盾不得归档（旧逻辑 30 章即遗忘）");
    }

    @Test
    void 不同描述的同型HIGH互不阻塞() {
        repository.saveContinuityIssue(high("年龄倒退：第20章说三十岁", 20));
        repository.saveContinuityIssue(high("时序颠倒：先见结局后见起因", 35)); // 同 type 不同描述
        compactor.compact(pid, 60);
        assertEquals(1, repository.findUnresolvedIssues(pid).size(),
                "不同描述不构成复发，旧的可老化、新的保留");
        assertTrue(repository.findUnresolvedIssues(pid).stream()
                .anyMatch(i -> i.getDescription().contains("时序颠倒")));
    }
}
