package com.writing.novel.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NCS-047: 整组采纳检查覆盖门禁——缺必需检查或未处置 UNKNOWN 禁止接受；
 * 报告绑定精确候选 hash（afterJson 的 SHA-256，12 hex）。
 */
@Service
public class GroupCoverageGate {

    /** 章节类候选采纳前必须完成的检查 */
    public static final List<String> REQUIRED_CHECKS = List.of("CONTINUITY", "READER_REVIEW");

    /** 候选精确指纹：afterJson 规范化 SHA-256（12 hex） */
    public static String candidateHash(String afterJson) {
        return com.writing.novel.util.ReviewFingerprint.sha256Hex12(
                afterJson == null ? "" : afterJson);
    }

    public record Verdict(boolean accept, List<String> missingChecks,
                          List<String> unresolvedUnknowns, String candidateHash) {}

    /**
     * 覆盖裁决。
     * @param candidateAfterJson 候选 afterJson（hash 绑定）
     * @param checkStatuses      阶段名 → COMPLETED/INVALID/UNKNOWN/缺失(null)
     * @param unknownDisposition UNKNOWN 的处置（FALSE_POSITIVE/EXCEPTION/DEFER）；空=未处置
     */
    public Verdict verdict(String candidateAfterJson, Map<String, String> checkStatuses,
                           Map<String, String> unknownDisposition) {
        List<String> missing = REQUIRED_CHECKS.stream()
                .filter(c -> !"COMPLETED".equals(checkStatuses.get(c)))
                .toList();
        List<String> unresolved = unknownDisposition.entrySet().stream()
                .filter(e -> "UNKNOWN".equals(e.getValue())
                        || (e.getValue() == null || e.getValue().isBlank()))
                .map(Map.Entry::getKey)
                .toList();
        return new Verdict(missing.isEmpty() && unresolved.isEmpty(),
                missing, unresolved, candidateHash(candidateAfterJson));
    }

    /** 报告载体（绑定精确候选 hash） */
    public Map<String, Object> report(Verdict v, String projectId, String proposalId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("projectId", projectId);
        m.put("proposalId", proposalId);
        m.put("candidateHash", v.candidateHash());
        m.put("accept", v.accept());
        m.put("missingChecks", v.missingChecks());
        m.put("unresolvedUnknowns", v.unresolvedUnknowns());
        return m;
    }
}
