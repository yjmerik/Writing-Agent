package com.writing.novel.service;

import com.writing.novel.domain.GenreProfile;
import com.writing.novel.domain.KnowledgeEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * p17-04: ConceiveGate POWER_SYSTEM 按题材豁免。
 * 原状：checkWorldbuilding 对所有题材强制 POWER_SYSTEM（:36-39），
 * 纯悬疑/现代言情/历史正剧被门禁打回或被迫编造无关力量体系。
 */
class ConceiveGateGenreExemptionTest {

    private final ConceiveGate gate = new ConceiveGate();
    private final GenreDetector detector = new GenreDetector();

    private KnowledgeEntry entry(String type, String name) {
        return new KnowledgeEntry("proj-ge", type, name, "内容");
    }

    @Test
    void 悬疑题材无POWER_SYSTEM通过门禁() {
        var entries = List.of(entry("FACTION", "警方"), entry("RULE", "线索守则"), entry("SETTING", "小城"));
        var result = gate.checkWorldbuilding(entries, "悬疑,推理", GenreProfile.MYSTERY);
        assertTrue(result.pass(), "MYSTERY 不应强制 POWER_SYSTEM: " + result.issues());
    }

    @Test
    void 历史言情文学题材豁免() {
        for (GenreProfile g : new GenreProfile[]{GenreProfile.HISTORICAL, GenreProfile.ROMANCE, GenreProfile.LITERARY}) {
            var entries = List.of(entry("FACTION", "势力"), entry("RULE", "规则"), entry("SETTING", "场景"));
            assertTrue(gate.checkWorldbuilding(entries, "", g).pass(), g + " 应豁免 POWER_SYSTEM");
        }
    }

    @Test
    void 修仙奇幻仍强制POWER_SYSTEM_武侠豁免() {
        // p21-01: 传统武侠（非玄幻）不强制 POWER_SYSTEM——超自然力量体系属于 CULTIVATION
        for (GenreProfile g : new GenreProfile[]{GenreProfile.CULTIVATION, GenreProfile.FANTASY}) {
            var entries = List.of(entry("FACTION", "势力"), entry("RULE", "规则"), entry("SETTING", "场景"));
            assertFalse(gate.checkWorldbuilding(entries, "", g).pass(),
                    g + " 无 POWER_SYSTEM 应 FAIL（力量体系是核心）");
        }
        var wuxiaEntries = List.of(entry("FACTION", "门派"), entry("RULE", "规矩"), entry("SETTING", "襄阳"));
        assertTrue(gate.checkWorldbuilding(wuxiaEntries, "", GenreProfile.WUXIA).pass(),
                "WUXIA 应豁免 POWER_SYSTEM（路线图对齐）");
    }

    @Test
    void 豁免题材仍要求核心设定维度非空() {
        // 只有 1 条设定 → 条目过少仍 FAIL
        var result = gate.checkWorldbuilding(List.of(entry("FACTION", "孤条")), "", GenreProfile.MYSTERY);
        assertFalse(result.pass(), "豁免 POWER_SYSTEM 不等于放水完整性");
    }

    @Test
    void 兼容入口_tags仍可推断题材() {
        // tags=悬疑 无显式 genre → 自动检测豁免
        var entries = List.of(entry("FACTION", "警方"), entry("RULE", "守则"), entry("SETTING", "小城"));
        assertTrue(gate.checkWorldbuilding(entries, "悬疑,推理", null).pass(),
                "tags 检测为 MYSTERY 时同样豁免");
    }
}
