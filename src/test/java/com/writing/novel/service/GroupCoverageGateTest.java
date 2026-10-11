package com.writing.novel.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-047: 整组采纳检查覆盖门禁——缺必需检查或未处置 UNKNOWN 禁止接受；
 * 报告绑定精确候选 hash。
 */
class GroupCoverageGateTest {

    private final GroupCoverageGate gate = new GroupCoverageGate();
    private static final String AFTER_JSON = "{\"title\":\"第1章\",\"content\":\"修正稿正文。\"}";

    @Test
    void 缺必需检查禁止接受() {
        var v = gate.verdict(AFTER_JSON,
                Map.of("CONTINUITY", "COMPLETED"), // 缺 READER_REVIEW
                Map.of());
        assertFalse(v.accept(), "缺 READER_REVIEW 应拒绝");
        assertEquals(List.of("READER_REVIEW"), v.missingChecks());
        assertTrue(v.unresolvedUnknowns().isEmpty());
    }

    @Test
    void 未处置UNKNOWN禁止接受() {
        var v = gate.verdict(AFTER_JSON,
                Map.of("CONTINUITY", "COMPLETED", "READER_REVIEW", "COMPLETED"),
                Map.of("ch3:ENTITY_GRAPH", "UNKNOWN"));
        assertFalse(v.accept(), "未处置 UNKNOWN 应拒绝");
        assertEquals(List.of("ch3:ENTITY_GRAPH"), v.unresolvedUnknowns());
    }

    @Test
    void 空处置视为未处置() {
        var v = gate.verdict(AFTER_JSON,
                Map.of("CONTINUITY", "COMPLETED", "READER_REVIEW", "COMPLETED"),
                Map.of("ch2:FACT_HARVEST", ""));
        assertFalse(v.accept());
        assertEquals(List.of("ch2:FACT_HARVEST"), v.unresolvedUnknowns());
    }

    @Test
    void 覆盖完整且已处置时接受() {
        var v = gate.verdict(AFTER_JSON,
                Map.of("CONTINUITY", "COMPLETED", "READER_REVIEW", "COMPLETED"),
                Map.of("ch2:FACT_HARVEST", "FALSE_POSITIVE"));
        assertTrue(v.accept());
        assertTrue(v.missingChecks().isEmpty());
        assertTrue(v.unresolvedUnknowns().isEmpty());
    }

    @Test
    void 报告绑定精确候选hash() {
        var v = gate.verdict(AFTER_JSON,
                Map.of("CONTINUITY", "COMPLETED", "READER_REVIEW", "COMPLETED"), Map.of());
        var report = gate.report(v, "p1", "prop-9");
        assertEquals("p1", report.get("projectId"));
        assertEquals("prop-9", report.get("proposalId"));
        assertEquals(v.candidateHash(), report.get("candidateHash"));
        assertNotNull(report.get("candidateHash"));
        assertEquals(12, ((String) report.get("candidateHash")).length(), "12 hex");
        // 精确绑定：不同 afterJson → 不同 hash
        var v2 = gate.verdict("{\"title\":\"第1章\",\"content\":\"另一份稿。\"}",
                Map.of(), Map.of());
        assertNotEquals(v.candidateHash(), v2.candidateHash());
    }
}
