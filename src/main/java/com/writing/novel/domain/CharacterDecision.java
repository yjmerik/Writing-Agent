package com.writing.novel.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * NCS-012（方案 §07/F16）：关键决策记录——人物在具体情节点的可追踪选择。
 * 触发事件、当时所知、认为可行的选择、所选行动、代价、与旧承诺的冲突、正文证据锚点。
 * 绑定章号+稿版本；不是要求把心理解释写进正文，而是供规划与审校理解"这个人为什么这样做"。
 */
public class CharacterDecision {

    private String id;
    private String projectId;
    private String characterName;
    private int chapterIndex;
    private int chapterVersion;
    private String triggerEvent;      // 触发事件
    private String knewThen;          // 当时所知（可含误信）
    private List<String> options;     // 认为可行的选择
    private String chosenAction;      // 所选行动
    private String cost;              // 代价
    private String conflictWithCommitment; // 与旧承诺的冲突（可空）
    private String evidenceQuote;     // 正文证据锚点（原文引文）
    private Instant createdAt;

    public CharacterDecision() {
        this.id = UUID.randomUUID().toString();
        this.createdAt = Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getCharacterName() { return characterName; }
    public void setCharacterName(String characterName) { this.characterName = characterName; }
    public int getChapterIndex() { return chapterIndex; }
    public void setChapterIndex(int chapterIndex) { this.chapterIndex = chapterIndex; }
    public int getChapterVersion() { return chapterVersion; }
    public void setChapterVersion(int chapterVersion) { this.chapterVersion = chapterVersion; }
    public String getTriggerEvent() { return triggerEvent; }
    public void setTriggerEvent(String triggerEvent) { this.triggerEvent = triggerEvent; }
    public String getKnewThen() { return knewThen; }
    public void setKnewThen(String knewThen) { this.knewThen = knewThen; }
    public List<String> getOptions() { return options; }
    public void setOptions(List<String> options) { this.options = options; }
    public String getChosenAction() { return chosenAction; }
    public void setChosenAction(String chosenAction) { this.chosenAction = chosenAction; }
    public String getCost() { return cost; }
    public void setCost(String cost) { this.cost = cost; }
    public String getConflictWithCommitment() { return conflictWithCommitment; }
    public void setConflictWithCommitment(String conflictWithCommitment) { this.conflictWithCommitment = conflictWithCommitment; }
    public String getEvidenceQuote() { return evidenceQuote; }
    public void setEvidenceQuote(String evidenceQuote) { this.evidenceQuote = evidenceQuote; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
