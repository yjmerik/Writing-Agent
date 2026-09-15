package com.writing.novel.domain;

import java.time.Instant;
import java.util.List;

/**
 * p39-01：事实来源锚点——证据按 章×稿×引文 锚定，知情者按人在不同章分别记录。
 * validFrom/validTo 为叙事时点（章号），不是 createdAt 推断。
 * 缺证据记 UNKNOWN，不伪造 quote、不假设所有出场者知情。
 */
public class FactEvidence {

    public record Observer(String characterId, int learnedAtChapter,
                           int chapterVersion, String quote) {}

    private final String id;
    private final String factId;
    private final String projectId;
    private final int chapterIndex;
    private final int chapterVersion;
    private final String contentHash;
    private final String quote;
    private final int paragraphIndex;
    private final String anchorHash;
    private final int validFromChapter;
    private final Integer validToChapter;
    private final List<Observer> observers;
    private final Instant createdAt;

    public FactEvidence(String id, String factId, String projectId,
                        int chapterIndex, int chapterVersion, String contentHash,
                        String quote, int paragraphIndex, String anchorHash,
                        int validFromChapter, Integer validToChapter,
                        List<Observer> observers, Instant createdAt) {
        this.id = id;
        this.factId = factId;
        this.projectId = projectId;
        this.chapterIndex = chapterIndex;
        this.chapterVersion = chapterVersion;
        this.contentHash = contentHash;
        this.quote = quote;
        this.paragraphIndex = paragraphIndex;
        this.anchorHash = anchorHash;
        this.validFromChapter = validFromChapter;
        this.validToChapter = validToChapter;
        this.observers = observers != null ? List.copyOf(observers) : List.of();
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getFactId() { return factId; }
    public String getProjectId() { return projectId; }
    public int getChapterIndex() { return chapterIndex; }
    public int getChapterVersion() { return chapterVersion; }
    public String getContentHash() { return contentHash; }
    public String getQuote() { return quote; }
    public int getParagraphIndex() { return paragraphIndex; }
    public String getAnchorHash() { return anchorHash; }
    public int getValidFromChapter() { return validFromChapter; }
    public Integer getValidToChapter() { return validToChapter; }
    public List<Observer> getObservers() { return observers; }
    public Instant getCreatedAt() { return createdAt; }
}
