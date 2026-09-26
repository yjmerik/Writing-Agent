package com.writing.novel.orchestrator;

import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.ChapterQualityReport;
import com.writing.novel.domain.ChapterSynopsis;
import com.writing.novel.domain.Outline;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.Volume;
import com.writing.novel.spi.NovelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NCS-095：章节字数趋势预警——本章字数低于前 3 章均值 50% 时报告携带警示
 * （第二轮旅程实测后半部从 10k 量级滑落到 3-4k 且无任何提示）。只读提示，不阻断。
 */
class WordTrendWarningTest {

    @TempDir
    Path tempDir;
    private NovelRepository repo;
    private NovelOrchestrator orchestrator;
    private String pid;

    @BeforeEach
    void setUp() {
        com.writing.novel.persistence.sqlite.ConnectionProvider cp =
                new com.writing.novel.persistence.sqlite.ConnectionProvider(
                        tempDir.resolve("word-trend.db").toString());
        new com.writing.novel.persistence.sqlite.SqliteSchemaInitializer(cp).initialize();
        repo = new com.writing.novel.persistence.sqlite.SqliteNovelRepository(cp);
        orchestrator = bareOrchestrator();

        Project p = new Project("字数趋势", "构思", "武侠");
        repo.saveProject(p);
        pid = p.getId();

        Outline outline = new Outline(pid, "总纲");
        Volume volume = new Volume(pid, 1, "卷一", "摘要");
        for (int i = 1; i <= 5; i++) {
            volume.addSynopsis(new ChapterSynopsis(pid, i, "第" + i + "章", "梗概"));
        }
        outline.addVolume(volume);
        repo.saveOutline(outline);

        int[] words = {9000, 9500, 8500, 9200, 3000}; // 第 5 章骤降
        for (int i = 1; i <= 5; i++) {
            Chapter ch = new Chapter(pid, i, "第" + i + "章");
            ch.setContent(("第" + i + "章正文。").repeat(words[i - 1] / 10));
            ch.setStatus(Chapter.Status.DONE);
            repo.saveChapter(ch);
        }
        repo.findProject(pid).ifPresent(p2 -> {
            p2.setCurrentChapterIndex(5);
            repo.saveProject(p2);
        });
    }

    /** 构造仅含仓库的编排器（报告构建只依赖 repository）。 */
    private NovelOrchestrator bareOrchestrator() {
        return new NovelOrchestrator(repo,
                new com.writing.novel.orchestrator.ProjectService(repo),
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void 骤降章携带警示与对比均值() {
        ChapterQualityReport report = orchestrator.buildChapterQualityReport(pid, 5);
        assertTrue(report.isWordTrendWarning(), "低于前 3 章均值 50% 应告警");
        // 第2-4章正文各为 5700/5100/5520 字（6 字句 × N 次重复），均值 5440
        assertEquals(5440, report.getPreviousAvgWordCount(), "对比均值为前 3 章均值取整");
    }

    @Test
    void 正常章不告警() {
        ChapterQualityReport report = orchestrator.buildChapterQualityReport(pid, 4);
        assertFalse(report.isWordTrendWarning(), "与前期持平的章节不告警");
    }

    @Test
    void 历史不足三章不告警() {
        ChapterQualityReport report = orchestrator.buildChapterQualityReport(pid, 2);
        assertFalse(report.isWordTrendWarning(), "历史不足时无从对比，不告警");
        assertEquals(0, report.getPreviousAvgWordCount());
    }
}
