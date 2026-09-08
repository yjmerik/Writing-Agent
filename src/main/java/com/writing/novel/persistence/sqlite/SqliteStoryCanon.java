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

    public SqliteStoryCanon(StoryFactRepository factRepo) {
        this.factRepo = factRepo;
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
        StoryFact old = sameSubject.get(0);
        if (old.getAssertion().contains(machineFact.getAssertion())
                || machineFact.getAssertion().contains(old.getAssertion())) {
            old.validateAt(machineFact.getChapterEstablished());
            factRepo.save(old);
            return new WriteResult(Outcome.VALIDATED, old);
        }
        if (isProtectedHumanPatch(old, machineFact.getChapterEstablished())) {
            return new WriteResult(Outcome.SKIPPED_PROTECTED, null);
        }
        // p32-06: 待核断言（机器收割的世界铁律）不得取代已确认铁律
        if (old.getEvidenceGrade() == com.writing.novel.domain.StoryFact.EvidenceGrade.CONFIRMED
                && machineFact.getEvidenceGrade() == com.writing.novel.domain.StoryFact.EvidenceGrade.UNVERIFIED) {
            return new WriteResult(Outcome.SKIPPED_UNVERIFIED, null);
        }
        factRepo.supersede(old, machineFact);
        return new WriteResult(Outcome.SUPERSEDED, machineFact);
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
