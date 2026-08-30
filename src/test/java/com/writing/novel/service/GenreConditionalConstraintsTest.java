package com.writing.novel.service;

import com.writing.novel.agent.PlotterAgent;
import com.writing.novel.agent.TestPromptHelper;
import com.writing.novel.domain.GenreProfile;
import com.writing.novel.domain.KnowledgeEntry;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * p21-01: 题材条件化约束与路线图对齐。
 *
 * 传统武侠（非玄幻）默认不是快节奏网文：
 * - isFastPaced=false → 生成的卷大纲/章节约束不强制爽点/四段式/悬念钩子
 * - 武侠不强制 POWER_SYSTEM（超自然力量体系属于 CULTIVATION）
 * - CULTIVATION 保持网文快节奏硬约束不变
 */
class GenreConditionalConstraintsTest {

    @TempDir Path tempDir;
    private PlotterAgent plotter;
    private CapturingGateway gateway;
    private final ConceiveGate gate = new ConceiveGate();

    static class CapturingGateway implements LlmGateway {
        String lastPrompt = "";
        @Override public LlmResponse complete(LlmRequest req) {
            lastPrompt = req.getSystemPrompt() + "\n" + req.getUserMessage();
            return new LlmResponse("ok", 1, 1);
        }
        @Override public void streamComplete(LlmRequest req, StreamHandler h) {}
    }

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("gcc.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        gateway = new CapturingGateway();
        plotter = new PlotterAgent(gateway, new SqliteNovelRepository(cp), TestPromptHelper.create());
        plotter.setGenreRuleProvider(new GenreRuleProvider());
    }

    private void planVolume(GenreProfile genre) {
        plotter.planVolume("总纲", "", "卷1", "卷梗概", 1, 8, "角色A", "主角甲", genre);
    }

    @Test
    void 武侠默认不是快节奏题材_修仙是() {
        assertFalse(GenreProfile.WUXIA.isFastPaced(), "传统武侠不应默认快节奏（路线图对齐）");
        assertTrue(GenreProfile.CULTIVATION.isFastPaced(), "修仙保留网文快节奏约束");
    }

    @Test
    void 武侠卷大纲约束不强制爽点_用张力点替代() {
        planVolume(GenreProfile.WUXIA);
        assertFalse(gateway.lastPrompt.contains("每章至少一个爽点"),
                "武侠卷大纲不得强制爽点");
        assertTrue(gateway.lastPrompt.contains("核心张力点"),
                "武侠卷大纲应以张力点要求替代爽点");
    }

    @Test
    void 修仙卷大纲保留爽点硬约束() {
        planVolume(GenreProfile.CULTIVATION);
        assertTrue(gateway.lastPrompt.contains("每章至少一个爽点"),
                "CULTIVATION 保持爽点硬约束不变");
    }

    private KnowledgeEntry entry(String type, String name) {
        return new KnowledgeEntry("proj-gcc", type, name, "内容");
    }

    @Test
    void 武侠构思门禁不强制POWER_SYSTEM_修仙强制() {
        var entries = List.of(entry("FACTION", "门派"), entry("RULE", "江湖规矩"), entry("SETTING", "襄阳城"));
        assertTrue(gate.checkWorldbuilding(entries, "武侠", GenreProfile.WUXIA).pass(),
                "传统武侠无超自然力量体系，不强制 POWER_SYSTEM: "
                        + gate.checkWorldbuilding(entries, "武侠", GenreProfile.WUXIA).issues());
        assertFalse(gate.checkWorldbuilding(entries, "修仙", GenreProfile.CULTIVATION).pass(),
                "CULTIVATION 仍强制 POWER_SYSTEM");
    }
}
