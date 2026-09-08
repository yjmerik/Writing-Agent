package com.writing.novel.service;

import com.writing.novel.domain.ContinuityIssue;
import com.writing.novel.domain.Foreshadowing;
import com.writing.novel.spi.NovelRepository;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * p32-09（体验报告 #5/P2）：伏笔悬置提醒去重——同一伏笔至多保留一条未关闭提醒。
 * 旧行为：每章为每个 stale 伏笔各追加一条 MEDIUM issue（22 章 41 项里 35 项、
 * 23 章 36 项里 35 项均为同批旧伏笔的重复提醒），淹没当章阻断项。
 * 语义：未关闭提醒存在即跳过；作者关闭提醒且伏笔仍悬置时，下一章可重新提醒一次。
 */
public final class SuspenseReminderPolicy {

    private SuspenseReminderPolicy() {}

    /** 与 ContinuityChecker 生成的伏笔类 issue 区分的稳定前缀。 */
    private static final String PREFIX = "伏笔悬置过久：";

    /** @return 本次实际写入的提醒数 */
    public static int refresh(NovelRepository repository, String projectId, int chapterIndex, int threshold) {
        List<Foreshadowing> stale = repository.findUnresolvedForeshadowings(projectId).stream()
                .filter(f -> f.isMustResolve() || f.getPriority() == Foreshadowing.Priority.HIGH)
                .filter(f -> f.isStale(chapterIndex, threshold))
                .toList();
        if (stale.isEmpty()) return 0;
        Set<String> openHints = repository.findUnresolvedIssues(projectId).stream()
                .filter(i -> i.getType() == ContinuityIssue.Type.FORESHADOWING)
                .map(ContinuityIssue::getDescription)
                .filter(d -> d != null && d.startsWith(PREFIX))
                .collect(Collectors.toSet());
        int written = 0;
        for (Foreshadowing f : stale) {
            String key = keyOf(f);
            boolean alreadyHinted = openHints.stream().anyMatch(d -> d.contains(key));
            if (alreadyHinted) continue;
            String description = PREFIX + key + "已悬置" + f.suspenseChapters(chapterIndex)
                    + "章（埋于第" + f.getPlantedAtChapter() + "章），建议在近期章节回收";
            repository.saveContinuityIssue(new ContinuityIssue(projectId, chapterIndex,
                    ContinuityIssue.Type.FORESHADOWING, ContinuityIssue.Severity.MEDIUM, description));
            openHints.add(description); // 防同批重复描述
            written++;
        }
        return written;
    }

    /** 去重键：伏笔描述前缀（含结束括号，保证 contains 匹配唯一）。 */
    private static String keyOf(Foreshadowing f) {
        String d = f.getDescription() == null ? "" : f.getDescription();
        return "「" + d.substring(0, Math.min(30, d.length())) + "」";
    }
}
