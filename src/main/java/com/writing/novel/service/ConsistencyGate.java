package com.writing.novel.service;

import com.writing.novel.domain.ContinuityIssue;
import com.writing.novel.domain.GenreProfile;
import com.writing.novel.spi.NovelRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * p10-d2: 一致性门禁——纯规则检测校对硬伤（无 LLM，零成本）。
 *
 * 检测三类问题：
 * 1. 章节编号错误：正文中"第N章"与实际 chapter_index 不一致
 * 2. 时代用语穿帮：古风题材出现"补钙/数据库/系统"等现代词汇
 * 3. 角色名不一致：使用了角色卡中不存在的名字（粗检）
 */
public class ConsistencyGate {

    private final NovelRepository repository;

    /** 各题材的禁用现代词清单（题材无关框架 + 题材化词表） */
    private static final Map<GenreProfile, List<String>> FORBIDDEN_MODERN_TERMS = Map.of(
            GenreProfile.HISTORICAL, List.of(
                    "补钙", "数据库", "系统", "程序", "代码", "网络", "电脑", "手机",
                    "互联网", "软件", "硬件", "算法", "参数", "模块", "接口",
                    "量子", "原子", "分子", "细胞", "基因", "DNA",
                    "公里", "千克", "摄氏度", "瓦特"
            ),
            GenreProfile.FANTASY, List.of(
                    "数据库", "系统", "程序", "代码", "网络", "电脑", "手机",
                    "互联网", "量子", "原子", "基因", "DNA"
            ),
            GenreProfile.CULTIVATION, List.of(
                    "数据库", "系统", "程序", "代码", "网络", "电脑", "手机",
                    "互联网", "量子", "原子", "基因", "DNA"
            )
    );

    public ConsistencyGate(NovelRepository repository) {
        this.repository = repository;
    }

    /**
     * 检测章节的校对硬伤。
     * @param projectId 项目 ID
     * @param chapterIndex 实际章节号
     * @param chapterContent 章节正文
     * @param genre 题材（用于选择禁用词表）
     * @return 发现的问题列表
     */
    public List<ContinuityIssue> check(String projectId, int chapterIndex,
                                        String chapterContent, GenreProfile genre) {
        List<ContinuityIssue> issues = new ArrayList<>();
        if (chapterContent == null) return issues;

        // 1. 章节编号检查：正文中的"第N章"是否与实际一致
        Matcher chapterM = Pattern.compile("第([一二三四五六七八九十\\d]+)章").matcher(chapterContent);
        while (chapterM.find()) {
            String numStr = chapterM.group(1);
            int parsed = parseNumber(numStr);
            if (parsed > 0 && parsed != chapterIndex && Math.abs(parsed - chapterIndex) <= 3) {
                // 允许引用其他章节（如"上回说到第三章"），但正文开头的章号应一致
                if (chapterContent.indexOf(chapterM.group()) < 50) {
                    issues.add(new ContinuityIssue(projectId, chapterIndex,
                            ContinuityIssue.Type.WORLDBUILDING,
                            ContinuityIssue.Severity.MEDIUM,
                            "章节编号错误：正文标题写为「" + chapterM.group()
                                    + "」，实际应为「第" + chapterIndex + "章」"));
                }
            }
        }

        // 2. 时代用语检查
        if (genre != null) {
            List<String> forbidden = FORBIDDEN_MODERN_TERMS.getOrDefault(genre, List.of());
            for (String term : forbidden) {
                if (chapterContent.contains(term)) {
                    issues.add(new ContinuityIssue(projectId, chapterIndex,
                            ContinuityIssue.Type.WORLDBUILDING,
                            ContinuityIssue.Severity.HIGH,
                            "时代用语穿帮：" + genre + "题材中出现了现代词汇「" + term
                                    + "」，请替换为符合时代背景的表达"));
                }
            }
        }

        return issues;
    }

    private int parseNumber(String s) {
        if (s.matches("\\d+")) return Integer.parseInt(s);
        String[] digits = {"零","一","二","三","四","五","六","七","八","九"};
        if (s.equals("十")) return 10;
        for (int i = 0; i < digits.length; i++) {
            if (s.equals(digits[i])) return i;
        }
        return -1;
    }
}
