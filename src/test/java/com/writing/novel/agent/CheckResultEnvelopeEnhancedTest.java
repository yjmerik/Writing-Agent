package com.writing.novel.agent;

import com.writing.novel.domain.ContinuityIssue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-121: 检查信封解析增强——「已回收」严重度/类型枚举对齐 prompt 承诺/
 * VALID_EMPTY 收紧（混合输出不再吞真问题）。
 */
class CheckResultEnvelopeEnhancedTest {

    @Test
    void 已回收严重度解析为LOW不再降级扭曲() {
        var envelope = CheckResultEnvelope.of("【伏笔|已回收】断剑之谜在第5章已兑现。");
        assertEquals(CheckResultEnvelope.Status.SUCCESS, envelope.status());
        assertEquals(ContinuityIssue.Severity.LOW, envelope.issues().get(0).getSeverity());
        assertEquals(ContinuityIssue.Type.FORESHADOWING, envelope.issues().get(0).getType());
    }

    @Test
    void 类型枚举对齐_结构与节奏() {
        var envelope = CheckResultEnvelope.of("""
                【结构|中】本章场景切换无过渡。
                【节奏|低】后半段推进偏慢。
                """);
        assertEquals(2, envelope.issues().size());
        assertEquals(ContinuityIssue.Type.STRUCTURE, envelope.issues().get(0).getType(),
                "「结构」不再默认成 WORLDBUILDING（会错路由到真典覆写）");
        assertEquals(ContinuityIssue.Type.PACING, envelope.issues().get(1).getType());
        assertEquals(ContinuityIssue.Severity.MEDIUM, envelope.issues().get(0).getSeverity());
    }

    @Test
    void VALID_EMPTY收紧_混合输出不再吞真问题() {
        // 混合输出：一部分说"未发现"，其余真问题未按格式——旧 contains(未发现) 判干净
        var mixed = "第1项检查未发现问题。第2项：张三的佩剑名字与第3章矛盾，第3章叫断水本章叫霜寒。第3项：时代用语出现手机。";
        assertEquals(CheckResultEnvelope.Status.INVALID, CheckResultEnvelope.of(mixed).status(),
                "长混合输出必须 INVALID（人工/重检），不得伪装无问题");

        // 真正的干净声明（短句含标记）仍 VALID_EMPTY
        assertEquals(CheckResultEnvelope.Status.VALID_EMPTY,
                CheckResultEnvelope.of("未发现连续性问题。").status());
        assertEquals(CheckResultEnvelope.Status.VALID_EMPTY,
                CheckResultEnvelope.of("  经逐项核对，未发现矛盾。 ").status());
    }

    @Test
    void 空响应与截断语义不变() {
        assertEquals(CheckResultEnvelope.Status.UNKNOWN, CheckResultEnvelope.of("  ").status());
        assertEquals(CheckResultEnvelope.Status.INVALID, CheckResultEnvelope.of("截断的半截输").status());
    }
}
