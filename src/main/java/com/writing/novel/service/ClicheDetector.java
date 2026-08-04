package com.writing.novel.service;

import com.writing.novel.spi.NovelRepository;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * p10-d1: 套路词热度控制——纯规则检测高频微表情/反应词。
 *
 * 维护一个通用的"反应词"清单（题材无关），统计每章及最近 N 章的词频。
 * 超过阈值的词被加入"本章禁用词表"，注入到写作 prompt 中。
 *
 * 不使用 LLM——这是纯统计，成本为零。
 */
public class ClicheDetector {

    /** 通用微表情/反应词清单（题材无关，中文网络小说高频套路词） */
    private static final List<String> CLICHE_PHRASES = List.of(
            // 眼神类
            "目光一凝", "目光一凛", "目光一变", "目光一沉", "目光一闪", "目光微缩",
            "眼神一凛", "眼神一变", "眼神一沉",
            // 脸色类
            "脸色一变", "脸色一白", "脸色一青", "脸色微变", "面色一变", "面色一白", "面色一青",
            // 嘴部类
            "嘴角一抽", "嘴角微抽", "嘴角一勾",
            // 呼吸/心跳类
            "倒吸一口凉气", "倒吸了一口凉气", "心中一凛", "心中一惊", "心头一跳",
            // 瞳孔类
            "瞳孔一缩", "瞳孔骤缩", "瞳孔微缩",
            // 下意识类
            "不由得", "不禁", "暗道不好", "暗叫不好", "心中暗道",
            // 通用震惊
            "不由自主地", "浑身一震", "身体一僵", "猛地站起", "腾地站起"
    );

    /** 单章内某词出现 > 此阈值 → 加入禁用词表 */
    private static final int SINGLE_CHAPTER_THRESHOLD = 3;

    /** 最近 N 章累计出现 > 此阈值 → 加入禁用词表 */
    private static final int RECENT_CHAPTERS_THRESHOLD = 5;
    private static final int RECENT_WINDOW = 5;

    private final NovelRepository repository;

    public ClicheDetector(NovelRepository repository) {
        this.repository = repository;
    }

    /**
     * 统计指定文本中各套路词的出现次数。
     */
    public Map<String, Integer> countCliches(String text) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (text == null || text.isEmpty()) return counts;
        for (String phrase : CLICHE_PHRASES) {
            int count = countOccurrences(text, phrase);
            if (count > 0) counts.put(phrase, count);
        }
        return counts;
    }

    /**
     * 生成"本章禁用词表"——基于本章 + 最近 N 章的高频套路词。
     * @param projectId 项目 ID
     * @param currentChapterIndex 当前章节（即将写的章）
     * @return 禁用词约束文本（如为空则返回 ""）
     */
    public String generateForbiddenList(String projectId, int currentChapterIndex) {
        if (repository == null) return "";
        Set<String> forbidden = new LinkedHashSet<>();

        // 检查最近 N 章的累计频率
        Map<String, Integer> cumulative = new LinkedHashMap<>();
        for (int ch = Math.max(1, currentChapterIndex - RECENT_WINDOW); ch < currentChapterIndex; ch++) {
            var chapter = repository.findChapterByIndex(projectId, ch);
            if (chapter.isPresent()) {
                var counts = countCliches(chapter.get().getContent());
                for (var entry : counts.entrySet()) {
                    cumulative.merge(entry.getKey(), entry.getValue(), Integer::sum);
                }
            }
        }
        for (var entry : cumulative.entrySet()) {
            if (entry.getValue() >= RECENT_CHAPTERS_THRESHOLD) {
                forbidden.add(entry.getKey());
            }
        }

        if (forbidden.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("□ 本章禁止使用以下高频反应词（最近").append(RECENT_WINDOW)
          .append("章已过度使用）：");
        sb.append(String.join("、", forbidden)).append("\n");
        sb.append("  请用具体的动作、对话、内心独白来表现角色反应，而非套用固定短语\n");
        return sb.toString();
    }

    private int countOccurrences(String text, String phrase) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(phrase, idx)) != -1) {
            count++;
            idx += phrase.length();
        }
        return count;
    }

    /** 获取套路词清单（供测试/调试） */
    public List<String> getClichePhrases() {
        return Collections.unmodifiableList(CLICHE_PHRASES);
    }
}
