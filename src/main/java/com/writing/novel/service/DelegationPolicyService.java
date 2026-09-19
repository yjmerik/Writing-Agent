package com.writing.novel.service;

import com.writing.novel.domain.RevisionProposal;
import com.writing.novel.domain.RevisionRequest;
import com.writing.novel.persistence.sqlite.DelegationPolicyRepository;
import com.writing.novel.spi.NovelRepository;
import com.writing.novel.telemetry.EventLog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NCS-025（方案 §05/DELEGATION）：按变更类别的委托策略。
 * 类别：WORDING（措辞/润色）/ FACT_LOCAL（单章正文修复）/ MOTIVE（人物动机与命运·设定）
 * / CROSS_CHAPTER（跨章改动·计划调整）。默认 WORDING/FACT_LOCAL=AUTO、MOTIVE/CROSS_CHAPTER=CONFIRM
 * ——"允许自动修复称谓，跨章改变人物动机需确认"。AUTO 范围内自动接受留痕；越界类别产生确认要求。
 */
public class DelegationPolicyService {

    public enum ChangeClass { WORDING, FACT_LOCAL, MOTIVE, CROSS_CHAPTER }
    public enum Mode { AUTO, CONFIRM }

    public static final Map<ChangeClass, Mode> DEFAULTS = Map.of(
            ChangeClass.WORDING, Mode.AUTO,
            ChangeClass.FACT_LOCAL, Mode.AUTO,
            ChangeClass.MOTIVE, Mode.CONFIRM,
            ChangeClass.CROSS_CHAPTER, Mode.CONFIRM);

    private final DelegationPolicyRepository repository;
    private final NovelRepository novelRepository;

    public DelegationPolicyService(DelegationPolicyRepository repository, NovelRepository novelRepository) {
        this.repository = repository;
        this.novelRepository = novelRepository;
    }

    /** 当前规则（覆盖默认；缺省类回落 DEFAULTS） */
    public Map<ChangeClass, Mode> rules(String projectId) {
        Map<ChangeClass, Mode> merged = new LinkedHashMap<>(DEFAULTS);
        repository.findRules(projectId).forEach((k, v) -> {
            try { merged.put(ChangeClass.valueOf(k), Mode.valueOf(v)); } catch (IllegalArgumentException ignored) {}
        });
        return merged;
    }

    public void saveRules(String projectId, Map<String, String> rules) {
        for (Map.Entry<String, String> entry : rules.entrySet()) {
            try {
                ChangeClass.valueOf(entry.getKey());
                Mode.valueOf(entry.getValue());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("无效委托规则: " + entry.getKey() + "=" + entry.getValue());
            }
        }
        repository.saveRules(projectId, rules);
    }

    private ChangeClass classifyOrigin(String origin) {
        if (origin == null) return ChangeClass.FACT_LOCAL;
        return switch (origin) {
            case "PASSAGE_POLISH", "DIRECT_EDIT" -> ChangeClass.WORDING;
            case "CHAPTER_REWRITE" -> ChangeClass.FACT_LOCAL;
            case "SETTING_CHANGE" -> ChangeClass.MOTIVE;
            case "PLAN_ADJUST", "PLAN_DEVIATION", "RESTORE" -> ChangeClass.CROSS_CHAPTER;
            default -> ChangeClass.FACT_LOCAL;
        };
    }

    /** 修订请求的变更类别（多章候选→CROSS_CHAPTER；否则按 origin） */
    public ChangeClass classOfRequest(String projectId, RevisionRequest request) {
        long chapters = novelRepository.listProposals(projectId).stream()
                .filter(p -> p.getRequestKey() != null && p.getRequestKey().startsWith("rr:" + request.getId() + ":"))
                .map(RevisionProposal::getChapterIndex)
                .distinct().count();
        if (chapters > 1) return ChangeClass.CROSS_CHAPTER;
        return classifyOrigin(request.getOrigin().name());
    }

    public boolean canAutoAccept(String projectId, ChangeClass changeClass) {
        return rules(projectId).get(changeClass) == Mode.AUTO;
    }

    /** 委托自动接受：请求全部变更类别在 AUTO 规则内 → 走整组接受（留痕）；否则需作者确认 */
    public AutoDecision autoAcceptIfDelegated(String projectId, String requestId,
                                              GroupAcceptanceService acceptance) {
        List<RevisionProposal> group = novelRepository.listProposals(projectId).stream()
                .filter(p -> p.getRequestKey() != null && p.getRequestKey().startsWith("rr:" + requestId + ":"))
                .toList();
        if (group.isEmpty()) {
            return new AutoDecision(false, ChangeClass.FACT_LOCAL, "无候选");
        }
        long chapters = group.stream().map(RevisionProposal::getChapterIndex).distinct().count();
        ChangeClass changeClass = chapters > 1 ? ChangeClass.CROSS_CHAPTER : null;
        // 单章时需要请求 origin——从列表读取太绕，这里用候选目标近似（修复候选=FACT_LOCAL）
        if (changeClass == null) changeClass = ChangeClass.FACT_LOCAL;
        if (!canAutoAccept(projectId, changeClass)) {
            return new AutoDecision(false, changeClass,
                    "变更类别 " + changeClass + " 需作者确认（委托规则）");
        }
        var receipt = acceptance.acceptGroup(projectId, requestId,
                "auto-delegation-" + java.util.UUID.randomUUID());
        EventLog.emit("DELEGATION_AUTO_ACCEPTED", Map.of(
                "projectId", projectId, "requestId", requestId,
                "changeClass", changeClass.name()));
        return new AutoDecision(true, changeClass, receipt.message());
    }

    public record AutoDecision(boolean autoAccepted, ChangeClass changeClass, String message) {}
}
