package com.writing.novel.service;

import com.writing.novel.domain.NarrativeThread;
import com.writing.novel.domain.ThreadStatus;
import com.writing.novel.spi.NovelRepository;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 叙事线索追踪器——追踪剧情线索的活跃/悬挂/回收状态。
 * 防止叙事遗漏和线索断裂。
 */
public class NarrativeThreadTracker {

    private final NovelRepository repository;

    public NarrativeThreadTracker(NovelRepository repository) {
        this.repository = repository;
    }

    /**
     * 更新线索状态：本章提及的线索标记为 ACTIVE，未提及的标记为 SUSPENDED。
     *
     * @param projectId 项目 ID
     * @param chapterIndex 当前章节
     * @param mentionedThreads 本章提及的线索名称列表
     * @param resolvedThreads 本章回收的线索名称列表
     */
    public void updateThreads(String projectId, int chapterIndex,
                              List<String> mentionedThreads, List<String> resolvedThreads) {
        var allThreads = repository.findAllNarrativeThreads(projectId);

        for (var thread : allThreads) {
            if (thread.getStatus() == ThreadStatus.RESOLVED) continue;

            if (resolvedThreads.contains(thread.getName())) {
                thread.markResolved(chapterIndex);
                repository.saveNarrativeThread(thread);
            } else if (mentionedThreads.contains(thread.getName())) {
                thread.markActive(chapterIndex);
                repository.saveNarrativeThread(thread);
            } else {
                // p10-w3-07: 未提及改用 markPending（保持 ACTIVE，仅累积悬挂计数），
                // 长线不因一章未提及即 SUSPENDED；超阈值（15章）才在 getThreadSection 告警
                thread.markPending(chapterIndex);
                repository.saveNarrativeThread(thread);
            }
        }
    }

    /**
     * 注册新发现的线索（如果不存在同名线索）。
     */
    public void registerThread(String projectId, String name, int chapterIndex, String description) {
        var existing = repository.findAllNarrativeThreads(projectId);
        boolean exists = existing.stream()
                .anyMatch(t -> t.getName().equals(name));
        if (!exists) {
            repository.saveNarrativeThread(
                    new NarrativeThread(projectId, name, chapterIndex, description));
        }
    }

    /**
     * 生成叙事线索追踪文本，注入写作上下文。
     */
    public String getThreadSection(String projectId) {
        var threads = repository.findAllNarrativeThreads(projectId);
        var active = threads.stream()
                .filter(t -> t.getStatus() != ThreadStatus.RESOLVED)
                // p10-w3-07: cap 未回收线索（百万字下无界增长会塞满上下文），优先 suspenseChapters 高的
                .sorted((a, b) -> Integer.compare(b.getSuspenseChapters(), a.getSuspenseChapters()))
                .limit(10)
                .collect(Collectors.toList());

        if (active.isEmpty()) return "";

        // p10-w3-07: 阈值从 3 放宽到 15——长线容忍更长未跟进窗口，避免持续噪声告警
        int overdueThreshold = 15;
        var overdue = active.stream()
                .filter(t -> t.isOverdue(overdueThreshold))
                .collect(Collectors.toList());

        var normal = active.stream()
                .filter(t -> !t.isOverdue(overdueThreshold))
                .collect(Collectors.toList());

        StringBuilder sb = new StringBuilder();
        sb.append("【叙事线索追踪（请确保不遗漏以下线索，悬挂线索需在近期章节回收）】\n");

        if (!normal.isEmpty()) {
            sb.append("■ 活跃线索：\n");
            for (var t : normal) {
                sb.append("  - ").append(t.getName())
                  .append("（第").append(t.getFirstSeenChapter())
                  .append("-").append(t.getLastSeenChapter()).append("章）");
                if (t.getSuspenseChapters() > 0) {
                    sb.append(" ⚠️ 已").append(t.getSuspenseChapters()).append("章未跟进");
                }
                sb.append("\n");
            }
        }

        if (!overdue.isEmpty()) {
            sb.append("■ 悬挂过久（需尽快回收）：\n");
            for (var t : overdue) {
                sb.append("  ⚠️ ").append(t.getName())
                  .append("（已").append(t.getSuspenseChapters()).append("章未跟进")
                  .append("，第").append(t.getLastSeenChapter()).append("章最后提及）\n");
            }
        }

        return sb.toString();
    }
}
