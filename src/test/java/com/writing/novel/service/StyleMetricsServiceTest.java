package com.writing.novel.service;

import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.Project;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.spi.NovelRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-104: 风格量化指标——确定性计算 + 质量报告携带 + 基线可校验。
 */
class StyleMetricsServiceTest {

    @Test
    void 句长分布_确定计算() {
        String content = "一二三四五。六七八九十！短；句";
        // 句子：5字、5字、1字、1字 → 均值 3.0
        assertEquals(3.0, StyleMetricsService.avgSentenceLength(content), 1e-9);
        assertTrue(StyleMetricsService.sentenceLengthStdDev(content) > 0);
        var metrics = StyleMetricsService.compute(content);
        assertEquals(3.0, (double) metrics.get("avgSentenceLength"), 1e-9);
    }

    @Test
    void 对话密度_引号内占比() {
        String content = "他说「你好」然后转身离开。";
        // 引号内 2 字 / 全文 13 字
        assertEquals(2.0 / 13.0, StyleMetricsService.dialogueDensity(content), 1e-9);
        assertEquals(0.0, StyleMetricsService.dialogueDensity("没有任何对话的叙述。"));
    }

    @Test
    void 套路词命中计数() {
        String content = "他目光一凝，众人目光一凝，她又目光一凝，然后各干各的。";
        assertEquals(3, com.writing.novel.service.ClicheDetector.countHits(content));
        assertEquals(3, StyleMetricsService.compute(content).get("clicheHits"));
    }

    @Test
    void ngram重复率_章内与跨章() {
        String repetitive = "他抬起头来望向远方".repeat(10);
        String fresh = "春江潮水连海平海上明月共潮生滟滟随波千万里何处春江无月明";
        assertTrue(StyleMetricsService.repeatedNgramRateOf(repetitive) > 0.5, "高度重复文本重复率应高");
        assertEquals(0.0, StyleMetricsService.repeatedNgramRateOf(fresh), "无重复 4-gram 文本应为 0");
        // 跨章：两章共享开头段 → 跨章重复率 > 0；完全不同文本 → 0
        String a = "沈铁衣背着剑囊进城寻人未遇茶棚听信。";
        String b = "沈铁衣背着剑囊进城，此行只为讨还旧债。";
        assertTrue(StyleMetricsService.crossChapterRepetition(List.of(a, b)) > 0);
        assertEquals(0.0, StyleMetricsService.crossChapterRepetition(
                List.of("张三李四王五赵六钱七孙八周九吴十", "甲乙丙丁戊己庚辛壬癸子丑寅卯辰巳")));
    }

    @TempDir
    static Path tempDir;
    static NovelRepository repository;

    @BeforeAll
    static void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("metrics.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repository = new SqliteNovelRepository(cp);
    }

    @Test
    void 章节质量报告携带风格指标() {
        var project = new Project("指标测试", "构思", "wuxia");
        repository.saveProject(project);
        Chapter ch = new Chapter(project.getId(), 1, "第一章");
        ch.setContent("他「嗯」了一声，目光一凝。夜色沉沉压下来，远处更鼓三响，他仍旧立在原地未动。");
        repository.saveChapter(ch);

        // 直接经持久化往返验证 compute 确定性
        Map<String, Object> first = StyleMetricsService.compute(ch.getContent());
        Map<String, Object> second = StyleMetricsService.compute(
                repository.findChapterByIndex(project.getId(), 1).orElseThrow().getContent());
        assertEquals(first, second, "同内容重复计算结果必须一致（基线可校验的前提）");
        assertTrue((int) first.get("clicheHits") >= 1);
        assertTrue((double) first.get("dialogueDensity") > 0);
    }

    @Test
    void 空内容安全() {
        var metrics = StyleMetricsService.compute(null);
        assertEquals(0, metrics.get("clicheHits"));
        assertEquals(0.0, (double) metrics.get("repeatedNgramRate"), 1e-9);
    }
}
