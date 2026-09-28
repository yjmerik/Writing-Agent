package com.writing.novel.service;

import com.writing.novel.domain.GenreProfile;
import com.writing.novel.domain.StyleProfile;
import com.writing.novel.domain.WritingPreset;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-103: preset 风格资产扩展——
 * 1) JSON 解析 styleSamples/forbiddenLexicon；
 * 2) applyToStyleProfile 落例文范本（与 NCS-102 锚点接通）；
 * 3) 内置 wuxia preset 携带资产；
 * 4) ConsistencyGate 并入 preset 词表（默认行为等价，扩展词生效）。
 */
class PresetStyleAssetTest {

    @Test
    void json解析风格资产() {
        var preset = WritingPreset.fromMap(Map.of(
                "name", "t", "genreHint", "WUXIA",
                "styleSamples", List.of("例文一。", "例文二。", "", "例文三。", "例文四。"),
                "forbiddenLexicon", List.of("系统提示", "充值")));
        assertEquals(3, preset.getStyleSamples().size(), "空白剔除、至多 3 段");
        assertEquals(List.of("系统提示", "充值"), preset.getForbiddenLexicon());
        assertTrue(preset.toMap().containsKey("styleSamples"));
    }

    @Test
    void 预设例文落StyleProfile() {
        var preset = WritingPreset.fromMap(Map.of(
                "styleTone", "白描", "styleSamples", List.of("雪夜推门，肩上落白。")));
        StyleProfile profile = new StyleProfile("p1");
        preset.applyToStyleProfile(profile);
        assertEquals("白描", profile.getTone());
        assertEquals(1, profile.getStyleSamples().size());
        assertEquals("雪夜推门，肩上落白。", profile.getStyleSamples().get(0));
    }

    @Test
    void 内置wuxia预设携带资产() {
        var registry = new PresetRegistry();
        var wuxia = registry.getPreset("wuxia").orElseThrow();
        assertEquals(GenreProfile.WUXIA, wuxia.getGenreHint());
        assertFalse(wuxia.getStyleSamples().isEmpty(), "wuxia 应携带例文范本");
        assertFalse(wuxia.getForbiddenLexicon().isEmpty(), "wuxia 应携带扩展词表");
        // 老三样仍在
        assertEquals("沉雄遒劲、白描传神、快意而不失克制", wuxia.getStyleTone());
    }

    @Test
    void 词表并入规则门禁_默认行为等价() {
        // 无扩展：默认词仍命中
        var plainGate = new ConsistencyGate(null);
        var legacy = plainGate.check("p", 1, "他打开数据库查了系统。", GenreProfile.WUXIA);
        assertTrue(legacy.stream().anyMatch(i -> i.getDescription().contains("数据库")));

        // 有扩展：preset 专属词也命中
        var gate = new ConsistencyGate(null, Map.of(GenreProfile.WUXIA, List.of("系统提示")));
        var extended = gate.check("p", 1, "耳边响起系统提示。", GenreProfile.WUXIA);
        assertTrue(extended.stream().anyMatch(i -> i.getDescription().contains("系统提示")),
                "preset 扩展词应并入规则门禁");
        // 其他题材不受影响
        assertTrue(gate.check("p", 1, "耳边响起系统提示。", GenreProfile.MYSTERY).isEmpty());
    }
}
