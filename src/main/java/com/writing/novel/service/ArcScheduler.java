package com.writing.novel.service;

import com.writing.novel.domain.Character;
import com.writing.novel.spi.NovelRepository;

import java.util.ArrayList;
import java.util.List;

/**
 * p10-a2: 角色弧光调度器——每章写作前运行，算出该出场的配角 + 弧光推进建议。
 *
 * 解决"配角扁平/工具人"问题：从被动的事后重估（ArcUpdater 每10章）
 * 改为主动的事前调度——确保配角不会长时间冷场，弧光持续推进。
 */
public class ArcScheduler {

    private final NovelRepository repository;

    /** 角色多久未出场则建议出场（默认值，可被 WritingPreferences.characterUpgradeSpan 覆盖） */
    private static final int DEFAULT_COLD_GAP = 3;

    public ArcScheduler(NovelRepository repository) {
        this.repository = repository;
    }

    /**
     * 计算本章的角色出场建议。
     * @param projectId 项目 ID
     * @param currentChapter 当前章节号（即将写的章）
     * @return 出场建议文本（注入到 cast 指令），如无建议返回 ""
     */
    public String scheduleAppearance(String projectId, int currentChapter) {
        if (repository == null) return "";
        var characters = repository.findCharactersByProject(projectId);
        if (characters.isEmpty()) return "";

        List<String> suggestions = new ArrayList<>();

        for (Character c : characters) {
            if (c.getRole() != null && c.getRole().contains("主角")) continue;

            Integer lastSeen = c.getLastSeenChapter();
            int gap = lastSeen != null ? currentChapter - lastSeen : currentChapter;

            // 冷场角色（超过 DEFAULT_COLD_GAP 章未出场）→ 建议出场
            if (gap >= DEFAULT_COLD_GAP) {
                StringBuilder sb = new StringBuilder();
                sb.append(c.getName());
                if (lastSeen != null) {
                    sb.append("（已").append(gap).append("章未出场");
                } else {
                    sb.append("（尚未正式出场");
                }
                if (c.getArcStage() != null && !c.getArcStage().isBlank()) {
                    sb.append("，弧光阶段：").append(c.getArcStage());
                }
                if (c.getGrowthArc() != null && !c.getGrowthArc().isBlank()) {
                    // 取成长弧的第一个阶段作为推进方向提示
                    sb.append("，建议推进其弧光");
                }
                sb.append("）");
                suggestions.add(sb.toString());
            }
        }

        if (suggestions.isEmpty()) return "";

        return "【角色出场建议】以下配角已较久未出场，建议本章安排其出场并推进角色弧光：\n"
                + String.join("、", suggestions) + "\n";
    }

    /**
     * 更新角色最近出场章节（每章写完后调用，扫描正文中的角色名）。
     */
    public void updateLastSeenChapters(String projectId, int chapterIndex, String chapterContent) {
        if (repository == null || chapterContent == null) return;
        var characters = repository.findCharactersByProject(projectId);
        for (Character c : characters) {
            if (c.getName() != null && chapterContent.contains(c.getName())) {
                c.setLastSeenChapter(chapterIndex);
                repository.saveCharacter(c);
            }
        }
    }
}
