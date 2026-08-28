package com.writing.novel.service;

import com.writing.novel.domain.GenreProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * p17-01: WUXIA 题材全链路。
 * 原状：GenreProfile 无 WUXIA 枚举、GenreDetector 检测不到武侠关键词（落入 GENERAL）、
 * 无 wuxia 预设——武侠三入口全部缺席。
 */
class WuxiaGenreTest {

    private final GenreDetector detector = new GenreDetector();
    private final GenreRuleProvider rules = new GenreRuleProvider();

    @Test
    void 武侠标签和构思被检测为WUXIA() {
        assertEquals(GenreProfile.WUXIA, detector.detect("武侠,江湖,快意恩仇", ""));
        assertEquals(GenreProfile.WUXIA, detector.detect("", "少年侠客佩剑入江湖，门派恩仇三十年"));
        assertEquals(GenreProfile.WUXIA, detector.detect("武林,镖局", ""));
    }

    @Test
    void 武侠优先于修仙_混合标签不误判() {
        // 「武侠」明确出现时应判 WUXIA 而非被「宗门/境界」拉去 CULTIVATION
        assertEquals(GenreProfile.WUXIA, detector.detect("武侠,宗门", ""));
        // 纯修仙标签仍判 CULTIVATION（现状不回退）
        assertEquals(GenreProfile.CULTIVATION, detector.detect("修仙,境界,灵气", ""));
    }

    @Test
    void 武侠规则各方法非空且不含修仙境界词() {
        String plotting = rules.getPlottingRules(GenreProfile.WUXIA);
        String chapter = rules.getChapterWritingRules(GenreProfile.WUXIA);
        String climax = rules.getClimaxSuggestions(GenreProfile.WUXIA);
        String scene = rules.getRecommendedSceneDistribution(GenreProfile.WUXIA);
        assertTrue(plotting != null && plotting.contains("武侠"));
        assertTrue(chapter.contains("ACTION") || chapter.contains("节奏"), "章节节奏规则应非空");
        assertTrue(climax != null && !climax.isBlank());
        assertTrue(scene.contains("ACTION"), "武侠场景分布应以行动战斗为主");
        // 不携带修仙专属境界词
        for (String s : new String[]{plotting, chapter}) {
            assertFalse(s.contains("境界提升"), "武侠规则不应套用修仙境界节奏");
            assertFalse(s.contains("灵气"));
        }
        assertEquals(4, rules.getDefaultMaxBeats(GenreProfile.WUXIA));
        assertEquals("武侠江湖", GenreProfile.WUXIA.getChineseName());
    }

    @Test
    void wuxia预设可加载且字段完整() {
        var registry = new PresetRegistry();
        var preset = registry.getPreset("wuxia").orElseThrow();
        assertTrue(preset.isBuiltin());
        assertEquals(GenreProfile.WUXIA, preset.getGenreHint());
        assertNotNull(preset.getStyleTone());
        assertNotNull(preset.getStyleVoice());
        assertNotNull(preset.getStyleDosAndDonts());
        assertNotNull(preset.getPreferences().get("dialogueDensity"));
    }
}
