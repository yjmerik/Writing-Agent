package com.writing.novel.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** 风格基线：定义全书文风锚点 */
public class StyleProfile {
    private String projectId;
    private String tone;          // 基调：热血/暗黑/幽默
    private String voice;         // 叙事声音说明
    private String dosAndDonts;   // 风格注意事项
    private List<Integer> sampleChapterIndices;  // 作为风格样本的章节号
    /** NCS-102: 风格范本段（例文 few-shot 锚点；来源 preset/作者例文/作者确认的生成段落，至多 3 段） */
    private List<String> styleSamples = new ArrayList<>();
    /** NCS-128: 风格意图快照（tone/voice/dosAndDonts 首次确立时的锁定副本）——漂移检测的固定基准；
     *  为 null 表示尚未锁定（旧数据/未迁移），锁定后 updateProfile 只改可演化字段、不再触碰意图 */
    private String intentTone;
    private String intentVoice;
    private String intentDosAndDonts;
    private Instant updatedAt;

    public StyleProfile(String projectId) {
        this.projectId = projectId;
        this.sampleChapterIndices = new ArrayList<>();
        this.styleSamples = new ArrayList<>();
        this.updatedAt = Instant.now();
    }

    public void addSampleChapter(int chapterIndex) {
        if (!sampleChapterIndices.contains(chapterIndex)) {
            sampleChapterIndices.add(chapterIndex);
        }
        this.updatedAt = Instant.now();
    }

    /** NCS-102: 追加例文范本（去重、上限 3 段） */
    public void addStyleSample(String sample) {
        if (sample == null || sample.isBlank()) return;
        if (styleSamples.size() < 3 && !styleSamples.contains(sample)) {
            styleSamples.add(sample);
            this.updatedAt = Instant.now();
        }
    }

    public String getProjectId() { return projectId; }
    public String getTone() { return tone; }
    public void setTone(String t) { this.tone = t; this.updatedAt = Instant.now(); }
    public String getVoice() { return voice; }
    public void setVoice(String v) { this.voice = v; this.updatedAt = Instant.now(); }
    public String getDosAndDonts() { return dosAndDonts; }
    public void setDosAndDonts(String d) { this.dosAndDonts = d; this.updatedAt = Instant.now(); }
    public List<Integer> getSampleChapterIndices() { return sampleChapterIndices; }
    public List<String> getStyleSamples() { return styleSamples; }

    /** NCS-128: 锁定意图快照（幂等——已锁定不覆盖，作者显式重置经 setIntentLocked(false)） */
    public void lockIntent() {
        if (intentTone == null && intentVoice == null && intentDosAndDonts == null) {
            this.intentTone = tone;
            this.intentVoice = voice;
            this.intentDosAndDonts = dosAndDonts;
            this.updatedAt = Instant.now();
        }
    }

    /** 持久层恢复专用（绕过幂等锁） */
    public void setIntentToneRestore(String v) { this.intentTone = v; }
    public void setIntentVoiceRestore(String v) { this.intentVoice = v; }
    public void setIntentDosAndDontsRestore(String v) { this.intentDosAndDonts = v; }

    public boolean isIntentLocked() {
        return intentTone != null || intentVoice != null || intentDosAndDonts != null;
    }

    /** 意图原文（锁定值优先；未锁定回退当前值）——漂移检测与风格注入的固定基准 */
    public String intentTone() { return intentTone != null ? intentTone : tone; }
    public String intentVoice() { return intentVoice != null ? intentVoice : voice; }
    public String intentDosAndDonts() { return intentDosAndDonts != null ? intentDosAndDonts : dosAndDonts; }
    public void setStyleSamples(List<String> samples) {
        this.styleSamples = samples == null ? new ArrayList<>() : new ArrayList<>(samples);
        this.updatedAt = Instant.now();
    }
    public Instant getUpdatedAt() { return updatedAt; }
}
