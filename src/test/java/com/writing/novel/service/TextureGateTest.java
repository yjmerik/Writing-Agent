package com.writing.novel.service;

import com.writing.novel.domain.ContinuityIssue;
import com.writing.novel.domain.NarrativePolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-195: TextureGate 双向质感门禁——指标 vs NarrativePolicy 区间，越界落
 * ContinuityIssue(TEXTURE/PACING) + 分级路由：质感单项→定点扩写目标（NCS-196 消费），
 * 结构性越界→整章重写目标（纠偏指令携带具体质感目标）；正常样本 PASS 零 issue。
 * 宽松策略（现状等效区间）不设防。
 */
class TextureGateTest {

    private final TextureGate gate = new TextureGate();

    /** 历史题材质感策略（区间双向） */
    private NarrativePolicy textured() {
        return NarrativePolicy.forGenre(com.writing.novel.domain.GenreProfile.HISTORICAL);
    }

    /** 叙述为主、锚点充分、场景数合规的正常样本 */
    private String healthySample() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            sb.append("雨丝斜斜地掠过檐角，账房里的油灯被风吹得晃了一下。沈砚把算盘拨到一半的手停在半空，").append("指尖压着那页残账，纸面被灯焰烘出一层浅浅的黄。".repeat(4)).append("\n\n");
            sb.append("「这页不对。」他把册子转向灯光，「子时的墨，比别处深。」\n\n");
            sb.append("郑三斤凑过来，鼻尖几乎贴到纸面。窗外的更声隔着雨传进来，闷闷的两响。").append("巷子里有狗在刨水，木桶滚动的声音由远及近，又被人喝住。".repeat(4)).append("\n\n");
            sb.append("「更声也不对。」沈砚吹熄了灯，「今夜没有子时那一更。」\n\n");
            if (i < 2) sb.append("* * *\n\n");
        }
        return sb.toString();
    }

    /** 连续对白乒乓样本（≥4 段连续对白无锚点行） */
    private String pingPongSample() {
        return ("「你昨晚在哪。」「在家。」「有人证么。」「没有。」「那谁信你。」「随你。」\n\n").repeat(6);
    }

    @Test
    void 正常样本PASS不产生issue与目标() {
        var v = gate.check("p1", 3, healthySample(), textured());
        assertTrue(v.pass(), "区间内样本应 PASS: " + v.issues());
        assertTrue(v.issues().isEmpty(), "正常样本不得产生 issue");
        assertTrue(v.expansionTargets().isEmpty());
        assertTrue(v.rewriteTargets().isEmpty());
        assertEquals(0, v.pingPongCount());
    }

    @Test
    void 对白段占比超上限路由到定点扩写() {
        var v = gate.check("p1", 3, pingPongSample(), textured());
        assertFalse(v.pass());
        assertTrue(v.dialogueRatio() > textured().getDialogueParagraphRatioMax(), "样本应对白超限");
        assertFalse(v.expansionTargets().isEmpty(), "质感单项越界应路由到扩写目标");
        assertTrue(v.issues().stream().allMatch(i -> i.getType() == ContinuityIssue.Type.TEXTURE
                        || i.getType() == ContinuityIssue.Type.PACING),
                "越界应落 TEXTURE/PACING issue");
    }

    @Test
    void 对白乒乓检测捕获连续对白无锚点() {
        var v = gate.check("p1", 3, pingPongSample(), textured());
        assertTrue(v.pingPongCount() > 0, "连续>3段对白应检出乒乓: " + v.pingPongCount());
        assertTrue(v.expansionTargets().stream().anyMatch(t -> t.contains("锚点") || t.contains("对白")),
                "乒乓应给出补锚点行扩写目标");
    }

    @Test
    void 叙述碎片化低于成段下限路由到扩写() {
        // 每段都是短叙述标签，无成段叙述块
        String fragmented = ("他推门。\n\n雨很大。\n\n灯灭了。\n\n「谁。」\n\n没人应。\n\n").repeat(20);
        var v = gate.check("p1", 3, fragmented, textured());
        assertFalse(v.pass());
        assertTrue(v.expansionTargets().stream().anyMatch(t -> t.contains("叙述")),
                "叙述碎片化应给出成段叙述扩写目标: " + v.expansionTargets());
    }

    @Test
    void 场景数超区间为结构性越界路由到整章重写() {
        // sceneBreaksMax=4：8 个分隔行超上限
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            sb.append(healthySample()).append("\n\n* * *\n\n");
        }
        var v = gate.check("p1", 3, sb.toString(), textured());
        assertFalse(v.rewriteTargets().isEmpty(), "结构性越界应路由到整章重写目标");
        assertTrue(v.rewriteTargets().stream().anyMatch(t -> t.contains("场景数")),
                "重写目标应携带具体质感/结构指标");
    }

    @Test
    void 宽松策略不设防直接PASS() {
        NarrativePolicy loose = NarrativePolicy.forGenre(com.writing.novel.domain.GenreProfile.CULTIVATION);
        var v1 = gate.check("p1", 3, pingPongSample(), loose);
        assertTrue(v1.pass(), "宽松策略不产生越界");
        var v2 = gate.check("p1", 3, null, loose);
        assertTrue(v2.pass(), "空内容在宽松策略下不设防");
    }

    @Test
    void 说明文检测并入为反向扩写目标() {
        // 连续 6 段纯叙述无对话 → ExpositionDetector 反向触发 → 扩写对白目标
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            sb.append("诏书读完，堂上安静了很久。").append("他把文书折好放进袖中，抬头看了一眼窗外的天色，雨还没停。".repeat(3)).append("\n\n");
        }
        var v = gate.check("p1", 3, sb.toString(), textured());
        assertFalse(v.pass());
        assertTrue(v.expansionTargets().stream().anyMatch(t -> t.contains("对白") || t.contains("对话")),
                "说明文过重应给出补对白扩写目标: " + v.expansionTargets());
    }
}
