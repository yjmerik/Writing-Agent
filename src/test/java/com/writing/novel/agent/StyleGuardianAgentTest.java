package com.writing.novel.agent;

import com.writing.novel.domain.StyleProfile;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.client.StreamHandler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

class StyleGuardianAgentTest {

    @Test
    void 从样章生成风格基线() {
        LlmGateway mockGateway = new LlmGateway() {
            @Override
            public LlmResponse complete(LlmRequest req) {
                return new LlmResponse("""
                    【基调】热血、紧凑、节奏明快
                    【叙事】第三人称，多动作描写，少心理独白
                    【注意事项】多用短句推进节奏；避免大段说明性文字；战斗场景要有画面感
                    """, 50, 100);
            }
            @Override
            public void streamComplete(LlmRequest req, StreamHandler handler) {}
        };

        StyleGuardianAgent agent = new StyleGuardianAgent(mockGateway, null, TestPromptHelper.create());
        StyleProfile profile = agent.establishProfile("proj-1", "样章正文……");

        assertNotNull(profile);
        assertEquals("热血、紧凑、节奏明快", profile.getTone());
        assertTrue(profile.getDosAndDonts().contains("短句"));
    }

    @Test
    void 检测风格一致性() {
        LlmGateway mockGateway = new LlmGateway() {
            @Override
            public LlmResponse complete(LlmRequest req) {
                return new LlmResponse("风格一致性评分：85/100。整体符合热血风格，但第3段节奏偏慢。", 10, 30);
            }
            @Override
            public void streamComplete(LlmRequest req, StreamHandler handler) {}
        };
        StyleGuardianAgent agent = new StyleGuardianAgent(mockGateway, null, TestPromptHelper.create());
        String report = agent.checkConsistency("proj-1", "新章节正文", "热血风格");
        assertTrue(report.contains("85"));
    }

    /** NCS-198: 卷末范本收割——高分章入样、cap 3 滚动刷新、低分不收 */
    @org.junit.jupiter.api.Test
    void 范本收割_高分入库滚动刷新_低分不收(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) {
        var cp = new com.writing.novel.persistence.sqlite.ConnectionProvider(tempDir.resolve("sg.db").toString());
        new com.writing.novel.persistence.sqlite.SqliteSchemaInitializer(cp).initialize();
        var repo = new com.writing.novel.persistence.sqlite.SqliteNovelRepository(cp);
        var p = new com.writing.novel.domain.Project("历史", "庙堂", "历史");
        repo.saveProject(p);

        StyleGuardianAgent agent = new StyleGuardianAgent(null, repo, TestPromptHelper.create());
        // 5 章：ch1/ch2 高分 9，ch3 高分 8，ch4 低分 6，ch5 高分 7（≥8 阈值之下）
        for (int i = 1; i <= 5; i++) {
            var ch = new com.writing.novel.domain.Chapter(p.getId(), i, "第" + i + "章");
            ch.setContent(("第" + i + "章的开场段落，叙述与对白交织，物件细节压着人心。")
                    + "\n\n" + ("第" + i + "章的最佳范本段：他把文书折起，压回原处，指腹在封蜡上停了半息，才抬眼看向堂下。").repeat(3));
            repo.saveChapter(ch);
            var review = new com.writing.novel.domain.ChapterReview(p.getId(), i);
            review.setOverallScore(i <= 3 ? (i == 3 ? 8 : 9) : (i == 5 ? 7 : 6));
            repo.saveChapterReview(review);
        }
        var profile = new StyleProfile(p.getId());
        profile.addStyleSample("旧样一。");
        profile.addStyleSample("旧样二。");
        profile.addStyleSample("旧样三。");
        repo.saveStyleProfile(profile);

        int added = agent.harvestHighScoreSamples(p.getId(), List.of(1, 2, 3, 4, 5), 8);
        assertEquals(2, added, "单次至多收 2 段（高分章优先）");
        var saved = repo.findStyleProfileByProject(p.getId()).orElseThrow();
        assertEquals(3, saved.getStyleSamples().size(), "cap 3 滚动");
        assertFalse(saved.getStyleSamples().contains("旧样一。"), "最旧样本被滚动淘汰");
        assertTrue(saved.getStyleSamples().stream().anyMatch(s -> s.contains("最佳范本段")),
                "新范本入库");
        assertTrue(saved.getSampleChapterIndices().size() >= 2, "范本章号登记");
    }

    /** NCS-199: 质感维度建档——新档案含描写密度/转场手法/对话配比三字段 */
    @org.junit.jupiter.api.Test
    void 建档含质感维度三字段() {
        LlmGateway mockGateway = new LlmGateway() {
            @Override
            public LlmResponse complete(LlmRequest req) {
                return new LlmResponse("""
                    【基调】沉郁克制
                    【叙事】第三人称限制视角，节奏舒缓
                    【注意事项】多用物件细节承载情绪；避免直白抒情
                    【描写密度】高：每场景至少两处感官锚点
                    【转场手法】时间跳切+物件叠印
                    【对话配比】叙述 6:对话 4
                    """, 50, 100);
            }
            @Override
            public void streamComplete(LlmRequest req, StreamHandler handler) {}
        };
        StyleGuardianAgent agent = new StyleGuardianAgent(mockGateway, null, TestPromptHelper.create());
        StyleProfile profile = agent.establishProfile("proj-tex", "历史权谋构思");
        assertEquals("高：每场景至少两处感官锚点", profile.getDescriptionDensity());
        assertEquals("时间跳切+物件叠印", profile.getTransitionTechnique());
        assertEquals("叙述 6:对话 4", profile.getDialogueNarrationRatio());
    }

    /** NCS-199: 旧档案解析兼容——缺质感维度列的档案读回不炸、字段为 null */
    @org.junit.jupiter.api.Test
    void 旧档案缺质感维度解析兼容(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) {
        var cp = new com.writing.novel.persistence.sqlite.ConnectionProvider(tempDir.resolve("old.db").toString());
        new com.writing.novel.persistence.sqlite.SqliteSchemaInitializer(cp).initialize();
        var repo = new com.writing.novel.persistence.sqlite.SqliteNovelRepository(cp);
        var p = new com.writing.novel.domain.Project("t", "c", "tag");
        repo.saveProject(p);
        // 旧档案：三字段 null（模拟 NCS-199 之前的存量）
        var legacy = new StyleProfile(p.getId());
        legacy.setTone("热血");
        repo.saveStyleProfile(legacy);
        var loaded = repo.findStyleProfileByProject(p.getId()).orElseThrow();
        assertEquals("热血", loaded.getTone());
        assertNull(loaded.getDescriptionDensity());
        assertNull(loaded.getTransitionTechnique());
        assertNull(loaded.getDialogueNarrationRatio());
        // 再经 updateProfile 缺失维度输出时不回退、不炸（空输出兼容）
        LlmGateway oldFormat = new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest req) {
                return new LlmResponse("【基调】沉郁\n【叙事】第三人称\n【注意事项】无", 10, 10);
            }
            @Override public void streamComplete(LlmRequest req, StreamHandler h) {}
        };
        StyleGuardianAgent agent = new StyleGuardianAgent(oldFormat, repo, TestPromptHelper.create());
        var updated = agent.updateProfile(p.getId(), "近期章节文本……");
        assertNotNull(updated);
        assertEquals("沉郁", updated.getTone());
        assertNull(updated.getDescriptionDensity(), "旧格式输出不产生质感字段");
    }
}
