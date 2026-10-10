package com.writing.novel.service;

import com.writing.novel.domain.GenreProfile;
import com.writing.novel.domain.NarrativePolicy;
import com.writing.novel.domain.Project;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.NarrativePolicyRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-190: 叙事策略（NarrativePolicy）——题材默认区间 + 项目级覆盖 + SQLite 持久化。
 *
 * <p>设定点口径（spec docs/specs/2026-10-09-narrative-quality-architecture.md §1）：
 * 快节奏/网文题材默认≈现状（宽松区间，golden 不回归）；HISTORICAL（慢节奏质感）给出
 * 真实双向区间——对话段上限 0.55、节拍上限 3、转场/叙述块/定场下限等。
 */
class NarrativePolicyTest {

    @TempDir
    Path tempDir;
    private NarrativePolicyRepository policyRepo;
    private NarrativePolicyService service;
    private com.writing.novel.persistence.sqlite.SqliteNovelRepository repo;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("policy.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new com.writing.novel.persistence.sqlite.SqliteNovelRepository(cp);
        policyRepo = new NarrativePolicyRepository(cp);
        service = new NarrativePolicyService(repo, policyRepo);
    }

    @Test
    void 历史题材默认为质感区间() {
        NarrativePolicy p = NarrativePolicy.forGenre(GenreProfile.HISTORICAL);
        assertEquals(3, p.getBeatsStandardMax(), "HISTORICAL 节拍上限 3");
        assertEquals(2, p.getBeatsStandardMin());
        assertEquals(0.55, p.getDialogueParagraphRatioMax(), 1e-9, "对话段区间含 0.55 上限");
        assertEquals(0.30, p.getDialogueParagraphRatioMin(), 1e-9);
        assertEquals(120, p.getNarrationRunMinChars(), "成段叙述最短块 120 字");
        assertEquals(80, p.getTransitionMinChars(), "转场最短 80 字");
        assertEquals(150, p.getOpeningSettingAllowance(), "章首定场许可 150 字");
        assertEquals(1200, p.getCharsPerBeatMin());
        assertEquals(2, p.getSensoryAnchorPerScene());
        assertEquals(200, p.getParagraphMaxChars(), "单段上限维持现状 200");
    }

    @Test
    void 快节奏与武侠题材默认为现状等效宽松区间() {
        for (GenreProfile g : new GenreProfile[]{GenreProfile.WUXIA, GenreProfile.CULTIVATION,
                GenreProfile.GENERAL, GenreProfile.MYSTERY}) {
            NarrativePolicy p = NarrativePolicy.forGenre(g);
            assertEquals(1.0, p.getDialogueParagraphRatioMax(), 1e-9, g + " 对话段无上限（现状）");
            assertEquals(0, p.getNarrationRunMinChars(), g + " 不设叙述块下限（现状）");
            assertEquals(0, p.getTransitionMinChars(), g + " 不设转场下限（现状）");
            assertEquals(0, p.getOpeningSettingAllowance(), g + " 定场许可 0（现状禁止铺陈）");
            int expectedMax = (g == GenreProfile.MYSTERY) ? 3 : 4;
            assertEquals(expectedMax, p.getBeatsStandardMax(), g + " 节拍上限对齐 GenreRuleProvider 默认");
        }
    }

    @Test
    void resolve按项目题材取默认并合并覆盖() {
        Project p = new Project("权谋", "庙堂", "历史");
        p.setGenre(GenreProfile.HISTORICAL);
        repo.saveProject(p);

        NarrativePolicy def = service.resolve(p.getId());
        assertEquals(80, def.getTransitionMinChars(), "无覆盖时取题材默认");

        service.saveOverrides(p.getId(), Map.of("transitionMinChars", 120, "dialogueParagraphRatioMax", 0.60));
        NarrativePolicy effective = service.resolve(p.getId());
        assertEquals(120, effective.getTransitionMinChars(), "覆盖生效");
        assertEquals(0.60, effective.getDialogueParagraphRatioMax(), 1e-9);
        assertEquals(3, effective.getBeatsStandardMax(), "未覆盖字段保持默认");
        var ov = service.overridesOf(p.getId());
        assertEquals(120, ((Number) ov.get("transitionMinChars")).intValue(), "覆盖持久化可读回");
        assertEquals(0.60, ((Number) ov.get("dialogueParagraphRatioMax")).doubleValue(), 1e-9);

        service.clearOverrides(p.getId());
        assertEquals(80, service.resolve(p.getId()).getTransitionMinChars(), "清除后回到题材默认");
    }

    @Test
    void 无题材项目按通用宽松默认() {
        Project p = new Project("t", "c", "tag");
        repo.saveProject(p);
        NarrativePolicy def = service.resolve(p.getId());
        assertEquals(1.0, def.getDialogueParagraphRatioMax(), 1e-9);
        assertEquals(0, def.getTransitionMinChars());
    }

    @Test
    void 覆盖非法值被拒绝() {
        Project p = new Project("t", "c", "tag");
        repo.saveProject(p);
        assertThrows(IllegalArgumentException.class,
                () -> service.saveOverrides(p.getId(), Map.of("dialogueParagraphRatioMin", 1.5)),
                "比例越界拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> service.saveOverrides(p.getId(), Map.of("transitionMinChars", -1)),
                "负数拒绝");
        // min > max（覆盖后区间倒置）拒绝
        Project h = new Project("h", "c", "tag");
        h.setGenre(GenreProfile.HISTORICAL);
        repo.saveProject(h);
        assertThrows(IllegalArgumentException.class,
                () -> service.saveOverrides(h.getId(), Map.of("beatsStandardMin", 4)),
                "区间倒置拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> service.saveOverrides("不存在", Map.of()),
                "项目不存在拒绝");
    }
}
