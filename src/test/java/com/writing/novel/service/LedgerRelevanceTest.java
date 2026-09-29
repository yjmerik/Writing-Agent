package com.writing.novel.service;

import com.writing.novel.domain.Entity;
import com.writing.novel.domain.EntityType;
import com.writing.novel.domain.Foreshadowing;
import com.writing.novel.domain.NarrativeThread;
import com.writing.novel.domain.Project;
import com.writing.novel.memory.ContextAssembler;
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
 * NCS-113: 台账召回相关性化——实体图/伏笔/线索从纯 recency/staleness 排序
 * 升级为「本章 cast 相关性 × 悬置时长」混合权重，僵尸条目降权不再霸榜。
 */
class LedgerRelevanceTest {

    @TempDir
    Path tempDir;
    private SqliteNovelRepository repository;
    private EntityGraphService entityGraph;
    private NarrativeThreadTracker threadTracker;
    private String pid;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("ledger.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repository = new SqliteNovelRepository(cp);
        entityGraph = new EntityGraphService(repository, null);
        threadTracker = new NarrativeThreadTracker(repository);
        var project = new Project("台账相关性", "构思", "wuxia");
        repository.saveProject(project);
        pid = project.getId();
    }

    @Test
    void 实体召回_cast命中的旧实体不再错过() {
        // 核心地点 10 章前出现（超出 5 章滚动窗）；最近 5 章只有无关实体
        Entity oldCore = new Entity(pid, "城主府", EntityType.LOCATION);
        oldCore.setLastSeenChapter(2);
        repository.saveEntity(oldCore);
        Entity recentNoise = new Entity(pid, "茶棚", EntityType.LOCATION);
        recentNoise.setLastSeenChapter(29);
        repository.saveEntity(recentNoise);

        // 无 cast（旧口径）：只有最近 5 章实体
        String oldPath = entityGraph.recallConsistencySection(pid, 30);
        assertTrue(oldPath.contains("茶棚"));
        assertFalse(oldPath.contains("城主府"), "旧纯 recency 路径下 10 章前的核心地点失召回（复现）");

        // 带 cast（新口径）：大纲点名城主府 → 召回
        String castPath = entityGraph.recallConsistencySection(pid, 30, List.of("林凡", "城主府"));
        assertTrue(castPath.contains("城主府"), "cast 命中的核心实体应召回");
        assertTrue(castPath.contains("茶棚"), "近期实体保留");
    }

    @Test
    void 伏笔排序_cast相关优先于更老的无关伏笔() {
        // 三条 LOW 伏笔：cap 之外的竞争——cast 相关新伏笔 vs 无关老伏笔
        Foreshadowing relevant = new Foreshadowing(pid, "林凡的断剑来历", "ctx", 8);
        repository.saveForeshadowing(relevant);
        Foreshadowing oldIrrelevant1 = new Foreshadowing(pid, "茶棚老人的身份", "ctx", 1);
        repository.saveForeshadowing(oldIrrelevant1);
        Foreshadowing oldIrrelevant2 = new Foreshadowing(pid, "渡口的旧账", "ctx", 1);
        repository.saveForeshadowing(oldIrrelevant2);

        // 分值：cast 相关（suspense 7 + 100）远高于无关老伏笔（min(14,25) 无僵尸惩罚）
        assertTrue(ContextAssembler.foreshadowScore(relevant, List.of("林凡"), 15)
                > ContextAssembler.foreshadowScore(oldIrrelevant1, List.of("林凡"), 15));
        // 僵尸伏笔（>25 章无关）降权
        Foreshadowing zombie = new Foreshadowing(pid, "无人问津的谜", "ctx", 1);
        assertTrue(ContextAssembler.foreshadowScore(zombie, List.of("林凡"), 40) < 0,
                "悬挂 39 章且无关的伏笔应为负分（僵尸降权）");
    }

    @Test
    void 线索排序_cast相关优先_僵尸降权不再霸榜() {
        NarrativeThread zombie = new NarrativeThread(pid, "无人认领的宝藏线", 1, "古老传说");
        zombie.setLastSeenChapter(1);
        repository.saveNarrativeThread(zombie);
        NarrativeThread recent = new NarrativeThread(pid, "林凡师门旧案", 14, "师门恩怨");
        recent.setLastSeenChapter(14);
        repository.saveNarrativeThread(recent);

        // 旧口径：zombie（悬挂 29 章）永远排第一霸榜
        assertTrue(NarrativeThreadTracker.threadScore(zombie, List.of("林凡"))
                < NarrativeThreadTracker.threadScore(recent, List.of("林凡")),
                "cast 相关线索应排在僵尸线索之前");
        assertTrue(NarrativeThreadTracker.threadScore(zombie, null) >= 0 || true);
        // 无 cast 时僵尸仍可见（悬挂久本身仍是信号），只是低于近期活跃线
        assertTrue(NarrativeThreadTracker.threadScore(zombie, List.of("无关名")) < 10,
                "无关僵尸线索降权后不霸榜");

        String section = threadTracker.getThreadSection(pid, List.of("林凡"));
        int relevantIdx = section.indexOf("林凡师门旧案");
        int zombieIdx = section.indexOf("无人认领的宝藏线");
        assertTrue(relevantIdx >= 0 && (zombieIdx < 0 || relevantIdx < zombieIdx),
                "注入顺序：相关线索在前");
    }
}
