package com.writing.novel.agent;

import com.writing.novel.domain.GenreProfile;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.llm.prompt.PromptTemplateService;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.service.GenreRuleProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * p17-03: volume-outline.st 补 genreRules 注入。
 * 原状：卷大纲模板硬编码「每章至少一个爽点」且无 {{genreRules}} 变量——
 * >40 章长篇走分层大纲，总纲带题材规则而逐卷细纲不带，项目越长题材约束断链越严重。
 */
class VolumeOutlineGenreRulesTest {

    @TempDir
    Path tempDir;
    private CapturingGateway gateway;
    private PlotterAgent plotter;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("vo.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        gateway = new CapturingGateway();
        plotter = new PlotterAgent(gateway, new SqliteNovelRepository(cp), TestPromptHelper.create());
        plotter.setGenreRuleProvider(new GenreRuleProvider());
    }

    @Test
    void 卷大纲prompt注入题材规则_武侠不落空() {
        plotter.planVolume("总纲", "", "卷2", "卷梗概", 9, 16, "角色A", "主角甲", GenreProfile.WUXIA);
        assertTrue(gateway.lastPrompt.contains("题材规则（武侠）"), "卷大纲 prompt 应含武侠题材规则");
        // p21-01: 武侠不再视为快节奏题材——不强制爽点，改用张力点要求
        assertFalse(gateway.lastPrompt.contains("每章至少一个爽点"), "武侠卷大纲不得强制爽点");
        assertTrue(gateway.lastPrompt.contains("核心张力点"), "武侠卷大纲应以张力点要求替代爽点");
    }

    @Test
    void 历史题材卷大纲不强制爽点_换张力点要求() {
        plotter.planVolume("总纲", "", "卷2", "卷梗概", 9, 16, "角色A", "主角甲", GenreProfile.HISTORICAL);
        assertTrue(gateway.lastPrompt.contains("题材规则（历史权谋）"), "卷大纲 prompt 应含历史题材规则");
        assertFalse(gateway.lastPrompt.contains("每章至少一个爽点"),
                "历史题材卷大纲不应有无条件爽点要求");
        assertTrue(gateway.lastPrompt.contains("张力点") || gateway.lastPrompt.contains("张力"),
                "历史题材应有张力点替代要求");
        // 输出格式标签保持「爽点：」（解析管道兼容）
        assertTrue(gateway.lastPrompt.contains("爽点："), "输出格式标签「爽点：」必须保留（解析兼容）");
    }

    @Test
    void 未注入GenreRuleProvider时安全降级为快节奏措辞() {
        plotter.setGenreRuleProvider(null);
        plotter.planVolume("总纲", "", "卷2", "卷梗概", 9, 16, "角色A", "主角甲", GenreProfile.HISTORICAL);
        assertTrue(gateway.lastPrompt.contains("爽点："), "降级路径模板渲染正常");
    }

    /** NCS-192: 节拍数要求读 NarrativePolicy——历史题材 2-3/喘息 1-2 豁免；无 projectId 走现状 3-5 */
    @Test
    void 卷大纲节拍数_历史题材策略化_无项目走现状() {
        // 无 policy service（或无 projectId）→ 现状 3-5 文案
        plotter.planVolume("总纲", "", "卷2", "卷梗概", 9, 16, "角色A", "主角甲", GenreProfile.HISTORICAL);
        assertTrue(gateway.lastPrompt.contains("3-5个用箭头分隔"), "无项目上下文保持现状节拍区间");

        // 有项目（历史题材）→ 策略渲染：标准章 2-3、喘息章 1-2 豁免
        var cp = new ConnectionProvider(tempDir.resolve("vo2.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        var repo = new SqliteNovelRepository(cp);
        var h = new com.writing.novel.domain.Project("权谋", "庙堂", "历史");
        h.setGenre(GenreProfile.HISTORICAL);
        repo.saveProject(h);
        var plotter2 = new PlotterAgent(gateway, repo, TestPromptHelper.create());
        plotter2.setGenreRuleProvider(new GenreRuleProvider());
        plotter2.setNarrativePolicyService(new com.writing.novel.service.NarrativePolicyService(
                repo, new com.writing.novel.persistence.sqlite.NarrativePolicyRepository(cp)));
        plotter2.planVolume("总纲", "", "卷2", "卷梗概", 9, 16, "角色A", "主角甲",
                GenreProfile.HISTORICAL, h.getId());
        assertTrue(gateway.lastPrompt.contains("标准章 2-3 个"), "标准章节拍数应按策略渲染: "
                + gateway.lastPrompt.substring(Math.max(0, gateway.lastPrompt.length() - 600)));
        assertTrue(gateway.lastPrompt.contains("喘息章可减至 1-2 个"), "喘息章豁免应渲染");
        assertTrue(gateway.lastPrompt.contains("场景节拍(2-3个用箭头分隔)"), "格式行节拍区间应策略化");
        assertTrue(gateway.lastPrompt.contains("承接："), "章梗概应含承接提示字段");
        assertFalse(gateway.lastPrompt.contains("3-5个"), "策略化后不得残留硬编码 3-5");
    }

    private static class CapturingGateway implements LlmGateway {
        String lastPrompt;

        @Override
        public LlmResponse complete(LlmRequest request) {
            lastPrompt = request.getSystemPrompt();
            return new LlmResponse("【总纲】测试", 10, 5);
        }

        @Override
        public void streamComplete(LlmRequest request, StreamHandler handler) {
            throw new UnsupportedOperationException();
        }
    }
}
