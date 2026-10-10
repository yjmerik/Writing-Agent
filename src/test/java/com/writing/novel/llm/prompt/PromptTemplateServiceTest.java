package com.writing.novel.llm.prompt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-192: 模板渲染契约——{{var}} 插值、{{#if}} 条件块，以及
 * volume-outline / chapter-writing 两模板的节奏改版渲染行为。
 */
class PromptTemplateServiceTest {

    private PromptTemplateService svc;

    @BeforeEach
    void setUp() throws Exception {
        svc = new PromptTemplateService();
        svc.loadTemplates();
    }

    @Test
    void 未设置变量渲染为空串_条件块整体移除() {
        String out = svc.render("chapter-writing", Map.of(
                "styleGuide", "克制冷峻",
                "writingConstraints", "", "characterVoices", "", "knowledgeQuotes", "",
                "genrePacingRules", "", "chapterLengthGuide", "", "structureGuide", "",
                "climaxCraft", "", "feedbackSection", ""));
        assertTrue(out.contains("风格约束：克制冷峻"));
        assertFalse(out.contains("{{"), "渲染后不得残留未插值标记");
    }

    @Test
    void 不存在的模板抛出明确异常() {
        assertThrows(IllegalArgumentException.class, () -> svc.render("no-such-template", Map.of()));
    }

    @Test
    void volumeOutline_节拍规则与承接字段按变量渲染() {
        // NCS-192: 节拍数要求走变量（政策渲染），格式行含承接提示字段
        String out = svc.render("volume-outline", Map.ofEntries(
                Map.entry("masterOutline", "总纲"), Map.entry("previousSummary", "前文"),
                Map.entry("volumeTitle", "第一卷"), Map.entry("volumeSummary", "梗概"),
                Map.entry("startChapter", "1"), Map.entry("endChapter", "8"),
                Map.entry("startChapterPlus1", "2"), Map.entry("chapterCount", "8"),
                Map.entry("existingCharacters", "张三"), Map.entry("protagonistName", ""),
                Map.entry("climaxPrinciple", "2. 张力点"), Map.entry("genreRules", ""),
                Map.entry("beatRule", "4. 每章的场景节拍要具体（标准章 2-3 个用箭头分隔的具体场景；喘息章可减至 1-2 个作为节奏豁免）"),
                Map.entry("beatRange", "2-3")));
        assertTrue(out.contains("标准章 2-3 个"), "节拍规则应渲染策略文本: " + out);
        assertTrue(out.contains("喘息章可减至 1-2 个"), "喘息章豁免应渲染");
        assertTrue(out.contains("场景节拍(2-3个用箭头分隔)"), "格式行节拍数应取 beatRange");
        assertTrue(out.contains("承接："), "格式行应含承接提示字段");
        assertFalse(out.contains("3-5个"), "策略渲染后不得残留硬编码节拍区间");
    }

    @Test
    void volumeOutline_缺省变量保持现状格式() {
        String out = svc.render("volume-outline", Map.ofEntries(
                Map.entry("masterOutline", "总纲"), Map.entry("previousSummary", "前文"),
                Map.entry("volumeTitle", "第一卷"), Map.entry("volumeSummary", "梗概"),
                Map.entry("startChapter", "1"), Map.entry("endChapter", "8"),
                Map.entry("startChapterPlus1", "2"), Map.entry("chapterCount", "8"),
                Map.entry("existingCharacters", "张三"), Map.entry("protagonistName", ""),
                Map.entry("climaxPrinciple", "2. 张力点"), Map.entry("genreRules", ""),
                Map.entry("beatRule", "4. 每章的场景节拍要具体（3-5个用箭头分隔的具体场景）"),
                Map.entry("beatRange", "3-5")));
        assertTrue(out.contains("3-5个用箭头分隔"), "现状等效题材保持 3-5 措辞");
        assertTrue(out.contains("承接："), "承接字段对所有题材生效（输出格式统一）");
    }

    @Test
    void chapterWriting_转场与定场条件块按需渲染() {
        Map<String, Object> base = new java.util.HashMap<>();
        base.put("styleGuide", "");
        base.put("writingConstraints", "");
        base.put("characterVoices", "");
        base.put("knowledgeQuotes", "");
        base.put("genrePacingRules", "");
        base.put("chapterLengthGuide", "");
        base.put("structureGuide", "");
        base.put("climaxCraft", "");
        base.put("feedbackSection", "");

        String with = svc.render("chapter-writing", withEntries(base,
                "transitionGuide", "场景转场：每次场景切换必须有至少 80 字的过渡。",
                "openingSettingGuide", "章首定场：开篇可用不超过 150 字的定场描写。"));
        assertTrue(with.contains("至少 80 字的过渡"), "质感题材应渲染转场要求");
        assertTrue(with.contains("不超过 150 字的定场"), "质感题材应渲染定场许可");

        String without = svc.render("chapter-writing", withEntries(base,
                "transitionGuide", "", "openingSettingGuide", ""));
        assertFalse(without.contains("字的过渡"), "宽松题材不得渲染转场要求");
        assertFalse(without.contains("的定场描写"), "宽松题材不得渲染定场许可");
    }

    private Map<String, Object> withEntries(Map<String, Object> base, Object... kv) {
        base.putAll(Map.ofEntries(toBeEntries(kv)));
        return base;
    }

    private Map.Entry<String, Object>[] toBeEntries(Object... kv) {
        @SuppressWarnings("unchecked")
        Map.Entry<String, Object>[] entries = new Map.Entry[kv.length / 2];
        for (int i = 0; i < kv.length; i += 2) {
            entries[i / 2] = Map.entry((String) kv[i], kv[i + 1]);
        }
        return entries;
    }
}
