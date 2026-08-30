package com.writing.novel.spi;

import com.writing.novel.domain.StoryFact;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * p22-01: StoryCanon 的测试内存适配器——与 SQLite 适配器共用同一契约测试。
 * 非持久化，仅测试/演示用。
 */
public class InMemoryStoryCanon implements StoryCanon {

    private final Map<String, StoryFact> facts = new LinkedHashMap<>();

    @Override
    public List<StoryFact> recall(String projectId) {
        return facts.values().stream()
                .filter(f -> f.getProjectId().equals(projectId))
                .filter(f -> f.getStatus() == StoryFact.Status.ACTIVE)
                .sorted(Comparator.comparingInt(StoryFact::getImportance).reversed())
                .toList();
    }

    @Override
    public List<StoryFact> recallBySubject(String projectId, String subject) {
        return recall(projectId).stream()
                .filter(f -> f.getSubject().equals(subject))
                .toList();
    }

    @Override
    public List<StoryFact> findAll(String projectId) {
        return new ArrayList<>(facts.values().stream()
                .filter(f -> f.getProjectId().equals(projectId))
                .toList());
    }

    @Override
    public Optional<StoryFact> findById(String factId) {
        return Optional.ofNullable(facts.get(factId));
    }

    @Override
    public WriteResult harvest(StoryFact machineFact) {
        List<StoryFact> sameSubject = recallBySubject(machineFact.getProjectId(), machineFact.getSubject())
                .stream().filter(f -> f.getFactType() == machineFact.getFactType()).toList();
        if (sameSubject.isEmpty()) {
            facts.put(machineFact.getId(), machineFact);
            return new WriteResult(Outcome.ADDED, machineFact);
        }
        StoryFact old = sameSubject.get(0);
        if (old.getAssertion().contains(machineFact.getAssertion())
                || machineFact.getAssertion().contains(old.getAssertion())) {
            old.validateAt(machineFact.getChapterEstablished());
            facts.put(old.getId(), old);
            return new WriteResult(Outcome.VALIDATED, old);
        }
        if (isProtectedHumanPatch(old, machineFact.getChapterEstablished())) {
            return new WriteResult(Outcome.SKIPPED_PROTECTED, null);
        }
        old.supersedeBy(machineFact);
        facts.put(old.getId(), old);
        facts.put(machineFact.getId(), machineFact);
        return new WriteResult(Outcome.SUPERSEDED, machineFact);
    }

    @Override
    public StoryFact applyHumanPatch(String projectId, StoryFact.FactType type, String subject,
                                     String assertion, int chapter, Integer importance) {
        String text = assertion.startsWith(HUMAN_PATCH_PREFIX) ? assertion : HUMAN_PATCH_PREFIX + assertion;
        StoryFact newer = new StoryFact(projectId, type, subject, text, chapter);
        newer.setImportance(importance != null ? importance : 5);
        newer.setLastValidatedChapter(chapter);
        for (StoryFact old : recallBySubject(projectId, subject)) {
            if (old.getFactType() == type) {
                old.supersedeBy(newer);
                facts.put(old.getId(), old);
                facts.put(newer.getId(), newer);
                return newer;
            }
        }
        facts.put(newer.getId(), newer);
        return newer;
    }

    @Override
    public boolean saveIfAbsent(StoryFact fact) {
        boolean exists = recallBySubject(fact.getProjectId(), fact.getSubject()).stream()
                .anyMatch(f -> f.getFactType() == fact.getFactType());
        if (exists) return false;
        facts.put(fact.getId(), fact);
        return true;
    }

    @Override
    public void validate(StoryFact fact, int chapter) {
        fact.validateAt(chapter);
        facts.put(fact.getId(), fact);
    }

    @Override
    public StoryFact supersedeFact(StoryFact oldFact, StoryFact newFact) {
        StoryFact stored = facts.get(oldFact.getId());
        if (stored == null) throw new IllegalArgumentException("待取代事实不存在: " + oldFact.getId());
        stored.supersedeBy(newFact);
        facts.put(stored.getId(), stored);
        facts.put(newFact.getId(), newFact);
        return newFact;
    }

    @Override
    public StoryFact retire(String projectId, String factId) {
        StoryFact existing = findById(factId)
                .orElseThrow(() -> new IllegalArgumentException("真典事实不存在: " + factId));
        if (!projectId.equals(existing.getProjectId())) {
            throw new IllegalArgumentException("真典事实不属于该项目: " + factId);
        }
        existing.retire();
        facts.put(existing.getId(), existing);
        return existing;
    }
}
