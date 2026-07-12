package com.writing.novel.memory;

import com.writing.novel.domain.*;
import com.writing.novel.domain.Character;
import com.writing.novel.spi.NovelRepository;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 上下文组装器 — 实现 spec 第 5 节的上下文组装公式。
 * 把三层记忆 + 风格 + 任务拼装成 ChapterWriter 所需的完整上下文。
 */
public class ContextAssembler {

    private static final int RETRIEVAL_TOP_K = 5;
    private static final int RECENT_TEXT_CHARS = 2000;
    private static final int RECENT_SUMMARY_WINDOW = 5;

    private final NovelRepository repository;
    private final KnowledgeBaseService knowledgeBase;
    private final SummaryService summaryService;
    private final VectorRetrievalService vectorRetrieval;

    public ContextAssembler(NovelRepository repository,
                            KnowledgeBaseService knowledgeBase,
                            SummaryService summaryService,
                            VectorRetrievalService vectorRetrieval) {
        this.repository = repository;
        this.knowledgeBase = knowledgeBase;
        this.summaryService = summaryService;
        this.vectorRetrieval = vectorRetrieval;
    }

    public WritingContext assemble(String projectId, int chapterIndex,
                                   String chapterTitle, String chapterSynopsis,
                                   String writingNotes) {

        WritingContext ctx = new WritingContext();

        repository.findStyleProfileByProject(projectId)
                .ifPresent(style -> ctx.setStyleSection(buildStyleSection(style)));

        ctx.setWorldbuildingSection(buildWorldbuildingSection(projectId));
        ctx.setVolumeOutlineSection(buildVolumeOutline(projectId, chapterIndex));
        ctx.setRecentSummarySection(buildRecentSummary(projectId, chapterIndex));
        ctx.setRecentTextSection(buildRecentText(projectId, chapterIndex));

        String queryText = chapterSynopsis != null ? chapterSynopsis : chapterTitle;
        assembleRetrievedContext(projectId, queryText, ctx);
        ctx.setRelevantForeshadowings(buildForeshadowings(projectId));
        ctx.setTaskSection(buildTaskSection(chapterTitle, chapterSynopsis, writingNotes));

        return ctx;
    }

    private String buildStyleSection(StyleProfile style) {
        StringBuilder sb = new StringBuilder();
        if (style.getTone() != null) sb.append("基调：").append(style.getTone()).append("\n");
        if (style.getVoice() != null) sb.append("叙事：").append(style.getVoice()).append("\n");
        if (style.getDosAndDonts() != null) sb.append("注意事项：").append(style.getDosAndDonts());
        return sb.toString();
    }

    private String buildWorldbuildingSection(String projectId) {
        List<KnowledgeEntry> entries = knowledgeBase.findAllEntries(projectId);
        if (entries.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (KnowledgeEntry e : entries) {
            sb.append("【").append(e.getName()).append("】").append(e.getContent()).append("\n");
        }
        return sb.toString().trim();
    }

    private String buildVolumeOutline(String projectId, int chapterIndex) {
        Optional<Outline> outlineOpt = repository.findOutlineByProject(projectId);
        if (outlineOpt.isEmpty()) return "";
        for (Volume v : outlineOpt.get().getVolumes()) {
            boolean contains = v.getSynopses().stream()
                    .anyMatch(s -> s.getChapterIndex() == chapterIndex);
            if (contains) {
                StringBuilder sb = new StringBuilder();
                sb.append("卷：").append(v.getTitle()).append("\n");
                if (v.getSummary() != null) sb.append(v.getSummary()).append("\n");
                for (ChapterSynopsis s : v.getSynopses()) {
                    sb.append("  第").append(s.getChapterIndex()).append("章 ")
                            .append(s.getTitle()).append("：").append(s.getSynopsis()).append("\n");
                }
                return sb.toString().trim();
            }
        }
        return "";
    }

    private String buildRecentSummary(String projectId, int chapterIndex) {
        int lookBackTo = Math.max(1, chapterIndex - 1);
        List<Summary> summaries = summaryService.findRecent(projectId, lookBackTo, RECENT_SUMMARY_WINDOW);
        if (summaries.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Summary s : summaries) {
            sb.append("第").append(s.getFromChapter()).append("-")
                    .append(s.getToChapter()).append("章：")
                    .append(s.getContent()).append("\n");
        }
        return sb.toString().trim();
    }

    private String buildRecentText(String projectId, int chapterIndex) {
        if (chapterIndex <= 1) return "";
        Optional<Chapter> prevOpt = repository.findChapterByIndex(projectId, chapterIndex - 1);
        if (prevOpt.isEmpty()) return "";
        String content = prevOpt.get().getContent();
        if (content == null || content.isEmpty()) return "";
        if (content.length() > RECENT_TEXT_CHARS) {
            return "..." + content.substring(content.length() - RECENT_TEXT_CHARS);
        }
        return content;
    }

    private void assembleRetrievedContext(String projectId, String queryText, WritingContext ctx) {
        List<Character> allChars = knowledgeBase.findAllCharacters(projectId);
        if (allChars.isEmpty()) return;

        // 向量检索相关人物（try-catch 防止 embedding API 失败中断写作）
        List<String> relevantIds;
        try {
            var searchResults = vectorRetrieval.search(queryText, projectId, RETRIEVAL_TOP_K);
            relevantIds = searchResults.stream().map(r -> r.id()).toList();
        } catch (Exception e) {
            relevantIds = List.of();
        }

        final List<String> finalRelevantIds = relevantIds;
        List<Character> relevantChars = allChars.stream()
                .filter(c -> finalRelevantIds.contains(c.getId()))
                .collect(Collectors.toList());

        // 强制注入主角（主角不依赖检索命中，避免名字漂移）
        for (Character c : allChars) {
            if (isMainCharacter(c) && !relevantChars.contains(c)) {
                relevantChars.add(0, c);  // 主角放第一位
            }
        }

        // 回退：检索完全没命中时，注入全部人物（最多 8 个）
        if (relevantChars.isEmpty()) {
            relevantChars = allChars.stream().limit(8).collect(Collectors.toList());
        }

        ctx.setRelevantCharacters(relevantChars);

        StringBuilder settings = new StringBuilder();
        for (String id : relevantIds) {
            repository.findKnowledgeEntry(id).ifPresent(e -> {
                settings.append("【").append(e.getName()).append("】")
                        .append(e.getContent()).append("\n");
            });
        }
        ctx.setRelevantSettings(settings.toString().trim());
    }

    /** 判断是否主角（用于强制锚定） */
    private boolean isMainCharacter(Character c) {
        String role = c.getRole();
        return role != null && (role.contains("主角") || role.contains("男主") || role.contains("女主"));
    }

    private String buildForeshadowings(String projectId) {
        List<Foreshadowing> unresolved = repository.findUnresolvedForeshadowings(projectId);
        if (unresolved.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Foreshadowing f : unresolved) {
            sb.append("- ").append(f.getDescription())
                    .append("（埋于第").append(f.getPlantedAtChapter()).append("章）\n");
        }
        return sb.toString().trim();
    }

    private String buildTaskSection(String chapterTitle, String synopsis, String writingNotes) {
        StringBuilder sb = new StringBuilder();
        sb.append("章节：").append(chapterTitle).append("\n");
        sb.append("内容：").append(synopsis != null ? synopsis : "").append("\n");
        if (writingNotes != null && !writingNotes.isBlank()) {
            sb.append("要求：").append(writingNotes);
        }
        return sb.toString().trim();
    }
}
