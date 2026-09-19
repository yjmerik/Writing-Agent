package com.writing.novel.persistence.sqlite;

import com.writing.novel.domain.StoryFact;
import com.writing.novel.spi.StoryCanon;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * p22-01: StoryCanon 的 SQLite 适配器——保护窗口 / ACTIVE 唯一性 / 审计链语义
 * 经 {@link StoryFactRepository} 落地，业务层不感知存储细节。
 */
@Component
public class SqliteStoryCanon implements StoryCanon {

    private final StoryFactRepository factRepo;
    /** NCS-008: 冲突裁决时写入 ContinuityIssue；null 时仅留事件（测试装配） */
    private final com.writing.novel.spi.NovelRepository issueSink;

    public SqliteStoryCanon(StoryFactRepository factRepo) {
        this(factRepo, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SqliteStoryCanon(StoryFactRepository factRepo, com.writing.novel.spi.NovelRepository issueSink) {
        this.factRepo = factRepo;
        this.issueSink = issueSink;
    }

    @Override
    public List<StoryFact> recall(String projectId) {
        return factRepo.findActive(projectId);
    }

    @Override
    public List<StoryFact> recallBySubject(String projectId, String subject) {
        return factRepo.findActiveBySubject(projectId, subject);
    }

    @Override
    public List<StoryFact> findAll(String projectId) {
        return factRepo.findAll(projectId);
    }

    @Override
    public Optional<StoryFact> findById(String factId) {
        return factRepo.findById(factId);
    }

    @Override
    public WriteResult harvest(StoryFact machineFact) {
        List<StoryFact> sameSubject = factRepo.findActiveBySubject(
                machineFact.getProjectId(), machineFact.getSubject()).stream()
                .filter(f -> f.getFactType() == machineFact.getFactType())
                .toList();
        if (sameSubject.isEmpty()) {
            factRepo.save(machineFact);
            return new WriteResult(Outcome.ADDED, machineFact);
        }
        // NCS-008（R02/F03）: 谓词化断言——
        // 1) 不同谓词 = 不同属性，并存不覆盖（旧字符串包含语义仅适用于无谓词旧数据）
        // 2) 同谓词相反极性 = 冲突候选：双方保留 ACTIVE + 事件 + 待处理问题，绝不静默覆盖
        if (machineFact.getPredicate() != null) {
            List<StoryFact> samePredicate = sameSubject.stream()
                    .filter(f -> machineFact.getPredicate().equals(f.getPredicate()))
                    .toList();
            if (samePredicate.isEmpty()) {
                factRepo.save(machineFact);
                return new WriteResult(Outcome.ADDED, machineFact);
            }
            StoryFact old = samePredicate.get(0);
            boolean oppositePolarity = old.getPolarity() != null && machineFact.getPolarity() != null
                    && !old.getPolarity().equals(machineFact.getPolarity());
            if (oppositePolarity) {
                factRepo.save(machineFact);
                recordConflict(machineFact, old);
                return new WriteResult(Outcome.CONFLICT, machineFact);
            }
            if (sameValueOrContained(old, machineFact)) {
                old.validateAt(machineFact.getChapterEstablished());
                factRepo.save(old);
                return new WriteResult(Outcome.VALIDATED, old);
            }
            // 同谓词同极性、取值变化 = 正常状态推进（先走人工保护/待核门槛再取代）
            WriteResult gated = gateProtectedOrUnverified(old, machineFact);
            if (gated != null) return gated;
            factRepo.supersede(old, machineFact);
            return new WriteResult(Outcome.SUPERSEDED, machineFact);
        }
        // 无谓词（旧数据/旧格式）：沿用旧语义
        List<StoryFact> legacyPeers = sameSubject.stream()
                .filter(f -> f.getPredicate() == null).toList();
        if (legacyPeers.isEmpty()) {
            factRepo.save(machineFact);
            return new WriteResult(Outcome.ADDED, machineFact);
        }
        StoryFact old = legacyPeers.get(0);
        if (old.getAssertion().contains(machineFact.getAssertion())
                || machineFact.getAssertion().contains(old.getAssertion())) {
            old.validateAt(machineFact.getChapterEstablished());
            factRepo.save(old);
            return new WriteResult(Outcome.VALIDATED, old);
        }
        WriteResult gated = gateProtectedOrUnverified(old, machineFact);
        if (gated != null) return gated;
        factRepo.supersede(old, machineFact);
        return new WriteResult(Outcome.SUPERSEDED, machineFact);
    }

    private boolean sameValueOrContained(StoryFact old, StoryFact newer) {
        if (old.getObjectValue() != null && newer.getObjectValue() != null) {
            return old.getObjectValue().equals(newer.getObjectValue());
        }
        return old.getAssertion().contains(newer.getAssertion())
                || newer.getAssertion().contains(old.getAssertion());
    }

    private WriteResult gateProtectedOrUnverified(StoryFact old, StoryFact machineFact) {
        if (isProtectedHumanPatch(old, machineFact.getChapterEstablished())) {
            return new WriteResult(Outcome.SKIPPED_PROTECTED, null);
        }
        // p32-06: 待核断言（机器收割的世界铁律）不得取代已确认铁律
        if (old.getEvidenceGrade() == com.writing.novel.domain.StoryFact.EvidenceGrade.CONFIRMED
                && machineFact.getEvidenceGrade() == com.writing.novel.domain.StoryFact.EvidenceGrade.UNVERIFIED) {
            return new WriteResult(Outcome.SKIPPED_UNVERIFIED, null);
        }
        return null;
    }

    /** NCS-008: 冲突候选落事件 + 待处理问题（不静默、不覆盖，交作者/检查链裁决） */
    private void recordConflict(StoryFact newer, StoryFact old) {
        String description = "断言冲突：" + newer.getSubject() + " / " + newer.getPredicate()
                + " ——「" + old.getAssertion() + "」(" + old.getPolarity() + ", 章" + old.getChapterEstablished()
                + ") vs「" + newer.getAssertion() + "」(" + newer.getPolarity() + ", 章"
                + newer.getChapterEstablished() + ")";
        com.writing.novel.telemetry.EventLog.emit("FACT_CONFLICT", java.util.Map.of(
                "projectId", newer.getProjectId(),
                "subject", newer.getSubject(),
                "predicate", newer.getPredicate(),
                "oldPolarity", String.valueOf(old.getPolarity()),
                "newPolarity", String.valueOf(newer.getPolarity()),
                "chapterIndex", newer.getChapterEstablished()));
        if (issueSink != null) {
            var issue = new com.writing.novel.domain.ContinuityIssue(newer.getProjectId(),
                    newer.getChapterEstablished(),
                    com.writing.novel.domain.ContinuityIssue.Type.WORLDBUILDING,
                    com.writing.novel.domain.ContinuityIssue.Severity.HIGH, description);
            issue.setChapterVersion(newer.getSourceChapterVersion() > 0
                    ? newer.getSourceChapterVersion() : null);
            issueSink.saveContinuityIssue(issue);
        }
    }

    @Override
    public StoryFact applyHumanPatch(String projectId, StoryFact.FactType type, String subject,
                                     String assertion, int chapter, Integer importance) {
        String text = assertion.startsWith(HUMAN_PATCH_PREFIX) ? assertion : HUMAN_PATCH_PREFIX + assertion;
        StoryFact newer = new StoryFact(projectId, type, subject, text, chapter);
        newer.setImportance(importance != null ? importance : 5);
        newer.setLastValidatedChapter(chapter);
        // 人工写入总是取代同 type 的旧 ACTIVE（含保护窗口内的人工补丁）——恰好一条 ACTIVE
        for (StoryFact old : factRepo.findActiveBySubject(projectId, subject)) {
            if (old.getFactType() == type) {
                factRepo.supersede(old, newer);
                return newer;
            }
        }
        factRepo.save(newer);
        return newer;
    }

    @Override
    public boolean saveIfAbsent(StoryFact fact) {
        boolean exists = factRepo.findActiveBySubject(fact.getProjectId(), fact.getSubject()).stream()
                .anyMatch(f -> f.getFactType() == fact.getFactType());
        if (exists) return false;
        factRepo.save(fact);
        return true;
    }

    @Override
    public void validate(StoryFact fact, int chapter) {
        fact.validateAt(chapter);
        factRepo.save(fact);
    }

    @Override
    public StoryFact supersedeFact(StoryFact oldFact, StoryFact newFact) {
        factRepo.supersede(oldFact, newFact);
        return newFact;
    }

    @Override
    public StoryFact retire(String projectId, String factId) {
        StoryFact existing = factRepo.findById(factId)
                .orElseThrow(() -> new IllegalArgumentException("真典事实不存在: " + factId));
        if (!projectId.equals(existing.getProjectId())) {
            throw new IllegalArgumentException("真典事实不属于该项目: " + factId);
        }
        existing.retire();
        factRepo.save(existing);
        return existing;
    }
}
