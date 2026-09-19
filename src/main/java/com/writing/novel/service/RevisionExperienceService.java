package com.writing.novel.service;

import com.writing.novel.domain.RevisionOutcome;
import com.writing.novel.persistence.sqlite.RevisionOutcomeRepository;
import com.writing.novel.telemetry.EventLog;

import java.util.List;
import java.util.Map;

/**
 * NCS-026（方案 §13/Reflexion·L4）：运行经验库。
 * 修订接受/拒绝样本按类存储；后续同类修订任务注入相关经验（仅修订链路，不进入故事事实）；
 * 作者可删除；被拒绝的理由优先注入（避免重复失败模式）。
 */
public class RevisionExperienceService {

    private static final int INJECTION_LIMIT = 3;

    private final RevisionOutcomeRepository repository;

    public RevisionExperienceService(RevisionOutcomeRepository repository) {
        this.repository = repository;
    }

    public void recordAccepted(String projectId, String changeClass, String instructions, String summary) {
        save(projectId, changeClass, RevisionOutcome.ACCEPTED, instructions, summary, "");
    }

    public void recordRejected(String projectId, String changeClass, String instructions, String summary, String reason) {
        save(projectId, changeClass, RevisionOutcome.REJECTED, instructions, summary, reason);
    }

    private void save(String projectId, String changeClass, String decision,
                      String instructions, String summary, String reason) {
        RevisionOutcome outcome = new RevisionOutcome();
        outcome.setProjectId(projectId);
        outcome.setChangeClass(changeClass);
        outcome.setDecision(decision);
        outcome.setInstructions(instructions == null ? "" : instructions);
        outcome.setSummary(summary == null ? "" : summary);
        outcome.setReason(reason == null ? "" : reason);
        repository.save(outcome);
        EventLog.emit("REVISION_OUTCOME_RECORDED", Map.of(
                "projectId", projectId, "decision", decision, "changeClass", String.valueOf(changeClass)));
    }

    /** 同类经验注入文本（拒绝优先；空库返回空串——无经验不注入） */
    public String buildInjection(String projectId, String changeClass) {
        List<RevisionOutcome> outcomes = repository.findRecent(projectId, changeClass, INJECTION_LIMIT * 2);
        List<RevisionOutcome> rejected = outcomes.stream()
                .filter(o -> RevisionOutcome.REJECTED.equals(o.getDecision()))
                .limit(INJECTION_LIMIT).toList();
        List<RevisionOutcome> accepted = outcomes.stream()
                .filter(o -> RevisionOutcome.ACCEPTED.equals(o.getDecision()))
                .limit(INJECTION_LIMIT).toList();
        if (rejected.isEmpty() && accepted.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("【本作品既有修订经验（同类过往样本，非故事事实）】\n");
        for (RevisionOutcome o : rejected) {
            sb.append("曾被拒绝：").append(brief(o.getInstructions()))
                    .append("——理由：").append(brief(o.getReason())).append("（避免重蹈）\n");
        }
        for (RevisionOutcome o : accepted) {
            sb.append("曾被接受：").append(brief(o.getInstructions()))
                    .append("——").append(brief(o.getSummary())).append("\n");
        }
        return sb.toString().trim();
    }

    public List<RevisionOutcome> list(String projectId) {
        return repository.findRecent(projectId, null, 100);
    }

    public boolean delete(String projectId, String outcomeId) {
        return repository.delete(projectId, outcomeId);
    }

    private String brief(String text) {
        if (text == null) return "";
        return text.length() > 80 ? text.substring(0, 80) + "…" : text;
    }
}
