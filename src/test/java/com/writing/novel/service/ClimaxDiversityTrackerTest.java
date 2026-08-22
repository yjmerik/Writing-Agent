package com.writing.novel.service;

import com.writing.novel.domain.ClimaxType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClimaxDiversityTrackerTest {

    @Test
    void 轮换约束序列化顺序与章节记录顺序无关_按枚举声明顺序() {
        ClimaxDiversityTracker tracker = new ClimaxDiversityTracker();
        // 打乱记录顺序：情感反转只出现在被淘汰的旧章，最近3章（2/3/4）覆盖 信息差/智斗
        tracker.record(1, List.of(ClimaxType.EMOTIONAL_REVERSAL));
        tracker.record(2, List.of(ClimaxType.INFO_GAP));
        tracker.record(3, List.of(ClimaxType.STRATEGIC));
        tracker.record(4, List.of(ClimaxType.INFO_GAP));

        String expected = "□ 爽点类型轮换：最近3章已使用【信息差、智斗】，本章禁止使用以上类型\n"
                + "  建议使用：情感反转 / 规则利用 / 反预期\n";
        assertEquals(expected, tracker.generateRotationConstraint());
    }

    @Test
    void 全部类型均用过后已使用列表仍按枚举声明顺序序列化() {
        ClimaxDiversityTracker tracker = new ClimaxDiversityTracker();
        // 最近3章（3/4/5）覆盖全部 5 种爽点类型
        tracker.record(1, List.of(ClimaxType.INFO_GAP));
        tracker.record(2, List.of(ClimaxType.STRATEGIC));
        tracker.record(3, List.of(ClimaxType.RULE_EXPLOIT));
        tracker.record(4, List.of(ClimaxType.EMOTIONAL_REVERSAL, ClimaxType.ANTI_EXPECTATION));
        tracker.record(5, List.of(ClimaxType.INFO_GAP, ClimaxType.STRATEGIC));

        String expected = "□ 爽点类型轮换：最近3章已使用【信息差、智斗、情感反转、规则利用、反预期】，本章禁止使用以上类型\n";
        assertEquals(expected, tracker.generateRotationConstraint());
    }
}
