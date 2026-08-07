package com.writing.novel.service;

import com.writing.novel.domain.ContinuityIssue;

import java.util.Optional;

/**
 * p11.1-J4: 说明文密度检测——纯规则检测连续无对话的段落。
 *
 * 解决"诏书/疏文念诵偏长""大段说明性文本影响节奏"问题。
 * 如果连续 5+ 段无对话（无引号），说明信息密度以说明性文本为主，缺乏角色互动。
 */
public class ExpositionDetector {

    /** 连续无对话段落阈值 */
    private static final int CONSECUTIVE_THRESHOLD = 5;

    /**
     * 检测章节中的连续说明性文本。
     * @return 检测到的问题（可能为空）
     */
    public Optional<ContinuityIssue> detect(String projectId, int chapterIndex, String content) {
        if (content == null || content.length() < 500) return Optional.empty();

        String[] paragraphs = content.split("\n");
        int maxConsecutive = 0;
        int currentStreak = 0;

        for (String para : paragraphs) {
            String trimmed = para.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.equals("---")) {
                continue;
            }
            // 判断是否有对话（中文引号 "" 或 '' 或 「」）
            boolean hasDialogue = trimmed.contains("\u201C") || trimmed.contains("\u300C")
                    || trimmed.contains("\u2018") || trimmed.contains("\"");
            if (hasDialogue) {
                currentStreak = 0;
            } else {
                currentStreak++;
                maxConsecutive = Math.max(maxConsecutive, currentStreak);
            }
        }

        if (maxConsecutive >= CONSECUTIVE_THRESHOLD) {
            return Optional.of(new ContinuityIssue(
                    projectId, chapterIndex,
                    ContinuityIssue.Type.PACING,
                    ContinuityIssue.Severity.MEDIUM,
                    "说明文密度过高：本章连续 " + maxConsecutive + " 段无对话。" +
                    "大段说明性文本（可能是诏书/疏文/条文念诵或设定铺陈）影响阅读节奏。" +
                    "建议插入角色反应、对话或动作来打断说明性段落。"));
        }

        return Optional.empty();
    }
}
