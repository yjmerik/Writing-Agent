package com.writing.novel.service;

import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.ChapterVersion;
import com.writing.novel.spi.NovelRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Single persistence boundary for manual and generated chapter revisions. */
public class ChapterRevisionService {

    public interface RevisionEffects {
        void refresh(String projectId, Chapter chapter);
    }

    private static final int COMPLETED_CONTENT_MIN = 500;

    private final NovelRepository repository;
    private final WritingTaskExecutor writingTaskExecutor;
    private final RevisionEffects effects;

    public ChapterRevisionService(NovelRepository repository) {
        this(repository, null, null);
    }

    public ChapterRevisionService(NovelRepository repository,
                                  WritingTaskExecutor writingTaskExecutor,
                                  RevisionEffects effects) {
        this.repository = repository;
        this.writingTaskExecutor = writingTaskExecutor;
        this.effects = effects;
    }

    public Chapter createDraft(String projectId, String title, String content) {
        return withProjectWork(projectId, () -> {
            requireProject(projectId);
            String safeTitle = requireTitle(title);
            String safeContent = content == null ? "" : content;
            int nextIndex = repository.findChaptersByProject(projectId).stream()
                    .mapToInt(Chapter::getChapterIndex).max().orElse(0) + 1;
            Chapter draft = new Chapter(projectId, nextIndex, safeTitle);
            if (!safeContent.isEmpty()) draft.setContent(safeContent);
            draft.setStatus(safeContent.length() >= COMPLETED_CONTENT_MIN
                    ? Chapter.Status.DONE : Chapter.Status.DRAFTING);
            repository.saveChapter(draft);
            refresh(projectId, draft);
            return draft;
        });
    }

    public Chapter edit(String projectId, int chapterIndex, int expectedVersion,
                        String title, String content, String source) {
        return withProjectWork(projectId, () -> editInOwnedWork(
                projectId, chapterIndex, expectedVersion, title, content, source));
    }

    /** Used by an already-owned rewrite/passage workflow. */
    public Chapter editInOwnedWork(String projectId, int chapterIndex, int expectedVersion,
                                   String title, String content, String source) {
        Chapter current = requireChapter(projectId, chapterIndex);
        if (current.getVersion() != expectedVersion) {
            throw new IllegalStateException("章节版本已变化");
        }
        String safeTitle = requireTitle(title);
        String safeSource = normalizedSource(source);
        String safeContent = "DIRECT_EDIT".equals(safeSource)
                ? requireBody(content) : (content == null ? "" : content);
        if ("DIRECT_EDIT".equals(safeSource)
                && current.getStatus() != Chapter.Status.DRAFTING
                && safeContent.length() < COMPLETED_CONTENT_MIN) {
            throw new IllegalArgumentException("已完成章节正文不能少于500字");
        }

        ChapterVersion snapshot = new ChapterVersion(
                UUID.randomUUID().toString(), projectId, chapterIndex, current.getVersion(),
                current.getTitle(), current.getContent(), safeSource, Instant.now());
        current.setTitle(safeTitle);
        current.setContent(safeContent);
        if ("RESTORE".equals(safeSource)) {
            current.setStatus(safeContent.length() >= COMPLETED_CONTENT_MIN
                    ? Chapter.Status.DONE : Chapter.Status.DRAFTING);
        } else if (current.getStatus() == Chapter.Status.DRAFTING
                && safeContent.length() >= COMPLETED_CONTENT_MIN) {
            current.setStatus(Chapter.Status.DONE);
        }
        if (!repository.saveChapterRevision(snapshot, current, expectedVersion)) {
            throw new IllegalStateException("章节版本已变化");
        }
        refresh(projectId, current);
        return current;
    }

    public Chapter restore(String projectId, int chapterIndex, int historyVersion, int expectedVersion) {
        return withProjectWork(projectId, () -> {
            Chapter current = requireChapter(projectId, chapterIndex);
            if (current.getVersion() != expectedVersion) {
                throw new IllegalStateException("章节版本已变化");
            }
            ChapterVersion target = repository.findChapterVersion(projectId, chapterIndex, historyVersion)
                    .orElseThrow(() -> new IllegalArgumentException("章节历史版本不存在: " + historyVersion));
            return editInOwnedWork(projectId, chapterIndex, expectedVersion,
                    target.getTitle(), target.getContent(), "RESTORE");
        });
    }

    public List<ChapterVersion> history(String projectId, int chapterIndex) {
        requireChapter(projectId, chapterIndex);
        return repository.findChapterVersions(projectId, chapterIndex);
    }

    private Chapter requireChapter(String projectId, int chapterIndex) {
        requireProject(projectId);
        return repository.findChapterByIndex(projectId, chapterIndex)
                .orElseThrow(() -> new IllegalArgumentException("章节不存在: " + chapterIndex));
    }

    private void requireProject(String projectId) {
        if (repository.findProject(projectId).isEmpty()) {
            throw new IllegalArgumentException("项目不存在: " + projectId);
        }
    }

    private String requireTitle(String title) {
        if (title == null || title.isBlank()) throw new IllegalArgumentException("章节标题不能为空");
        return title.trim();
    }

    private String requireBody(String content) {
        if (content == null || content.isBlank()) throw new IllegalArgumentException("章节正文不能为空");
        return content;
    }

    private String normalizedSource(String source) {
        return source == null || source.isBlank() ? "DIRECT_EDIT" : source;
    }

    private void refresh(String projectId, Chapter chapter) {
        if (effects != null) effects.refresh(projectId, chapter);
    }

    private <T> T withProjectWork(String projectId, java.util.function.Supplier<T> work) {
        if (writingTaskExecutor == null) return work.get();
        String workId = "chapter-revision-" + UUID.randomUUID();
        writingTaskExecutor.beginWork(projectId, workId);
        try {
            return work.get();
        } finally {
            writingTaskExecutor.endWork(projectId, workId);
        }
    }
}
