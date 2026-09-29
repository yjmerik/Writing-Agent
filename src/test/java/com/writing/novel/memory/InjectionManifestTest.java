package com.writing.novel.memory;

import com.writing.novel.domain.Character;
import com.writing.novel.domain.Foreshadowing;
import com.writing.novel.domain.KnowledgeEntry;
import com.writing.novel.domain.Outline;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.StoryFact;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.InjectionManifestRepository;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.persistence.sqlite.StoryFactRepository;
import com.writing.novel.spi.NovelRepository;
import com.writing.novel.spi.VectorSearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.*;

/**
 * NCS-096: 上下文注入清单——组装同源生成、条目与注入一一对应、
 * 同章同版本重复组装稳定、按章稿版本持久化可查询。
 */
class InjectionManifestTest {

    @TempDir
    Path tempDir;
    private NovelRepository repository;
    private ContextAssembler assembler;
    private InjectionManifestRepository manifestRepo;
    private String pid;
    private String entryId;
    private String charId;
    private String factId;
    private String foreshadowId;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("manifest.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repository = new SqliteNovelRepository(cp);
        manifestRepo = new InjectionManifestRepository(cp);

        VectorRetrievalService vectorRetrieval = mock(VectorRetrievalService.class);
        KnowledgeBaseService knowledgeBase = new KnowledgeBaseService(repository, vectorRetrieval, new MemoryRevision());
        SummaryService summaryService = mock(SummaryService.class);

        var entityGraph = mock(com.writing.novel.service.EntityGraphService.class);
        when(entityGraph.recallConsistencySection(anyString(), anyInt())).thenReturn("");
        var climax = mock(com.writing.novel.service.ClimaxDiversityTracker.class);
        when(climax.generateRotationConstraint()).thenReturn("");
        var threads = mock(com.writing.novel.service.NarrativeThreadTracker.class);
        when(threads.getThreadSection(anyString())).thenReturn("");
        var diversity = mock(com.writing.novel.service.DiversityTracker.class);
        when(diversity.generateAllConstraints(anyBoolean())).thenReturn("");
        when(diversity.generateAllConstraints()).thenReturn("");
        var cliche = mock(com.writing.novel.service.ClicheDetector.class);
        when(cliche.generateForbiddenList(anyString(), anyInt())).thenReturn("");

        assembler = new ContextAssembler(repository, knowledgeBase, summaryService,
                vectorRetrieval, entityGraph, climax, threads, diversity, cliche, new MemoryRevision());
        assembler.setStoryCanon(new com.writing.novel.persistence.sqlite.SqliteStoryCanon(
                new StoryFactRepository(cp)));

        // 种子数据
        Project p = new Project("清单测试", "构思", "wuxia");
        repository.saveProject(p);
        pid = p.getId();
        repository.saveOutline(new Outline(pid, "总纲"));

        KnowledgeEntry entry = new KnowledgeEntry(pid, "POWER_SYSTEM", "内力体系", "内力分九重，重上有境。");
        knowledgeBase.saveEntry(entry);
        entryId = entry.getId();

        Character protagonist = new Character(pid, "林凡", "主角");
        knowledgeBase.saveCharacter(protagonist);
        charId = protagonist.getId();

        // 向量检索命中知识条目
        when(vectorRetrieval.search(anyString(), anyString(), anyInt(), anyDouble()))
                .thenReturn(List.of(new VectorSearchResult(entryId, 0.9)));
        when(vectorRetrieval.getText(anyString())).thenReturn(null);

        StoryFact fact = new StoryFact(pid, StoryFact.FactType.CHARACTER_STATE, "林凡", "左手有旧伤", 1);
        new StoryFactRepository(cp).save(fact);
        factId = fact.getId();

        Foreshadowing foreshadow = new Foreshadowing(pid, "断剑的来历", "开篇断剑", 1);
        foreshadow.setPriority(Foreshadowing.Priority.HIGH);
        repository.saveForeshadowing(foreshadow);
        foreshadowId = foreshadow.getId();
    }

    @Test
    void 组装产出清单_条目覆盖各注入类() {
        WritingContext ctx = assembler.assemble(pid, 2, "第2章", "林凡夜探城主府", null);

        InjectionManifest manifest = ctx.getManifest();
        assertNotNull(manifest, "组装必须同步产出注入清单");
        assertEquals(pid, manifest.getProjectId());
        assertEquals(2, manifest.getChapterIndex());
        assertEquals(1, manifest.getChapterVersion(), "新章首次组装版本应为 1");

        var factEntries = manifest.entriesOfKind(InjectionManifest.Kind.FACT);
        assertEquals(1, factEntries.size(), "真典事实应记录");
        assertEquals(factId, factEntries.get(0).refId);
        assertEquals("bibleSection", factEntries.get(0).section);

        var knowledge = manifest.entriesOfKind(InjectionManifest.Kind.KNOWLEDGE);
        assertEquals(1, knowledge.size(), "知识条目应记录");
        assertEquals(entryId, knowledge.get(0).refId);

        var characters = manifest.entriesOfKind(InjectionManifest.Kind.CHARACTER);
        assertTrue(characters.stream().anyMatch(e -> charId.equals(e.refId)),
                "出场角色（主角锚定）应记录");

        var foreshadows = manifest.entriesOfKind(InjectionManifest.Kind.FORESHADOW);
        assertTrue(foreshadows.stream().anyMatch(e -> foreshadowId.equals(e.refId)),
                "待回收伏笔应记录");

        // section token 结算存在且非负
        assertTrue(manifest.getSectionTokens().containsKey("bibleSection"));
        assertTrue(manifest.totalTokens() >= 0);
    }

    @Test
    void 预算裁剪后_清单标记被削减section() {
        // 极小预算强制触发裁剪链
        WritingContext ctx = assembler.assemble(pid, 2, "第2章", "林凡夜探城主府", null, 600);
        InjectionManifest manifest = ctx.getManifest();
        assertNotNull(manifest);
        assertFalse(manifest.getTrimmedSections().isEmpty(),
                "小预算下应有 section 被标记削减");
        // relevantSettings 现行裁剪策略为置空——清单应反映终态（token=0）
        assertEquals(0, manifest.getSectionTokens().getOrDefault("relevantSettings", -1));
    }

    @Test
    void 同章同版本重复组装_清单稳定() {
        WritingContext first = assembler.assemble(pid, 2, "第2章", "林凡夜探城主府", null);
        WritingContext second = assembler.assemble(pid, 2, "第2章", "林凡夜探城主府", null);
        assertEquals(first.getManifest(), second.getManifest(),
                "同输入同版本重复组装清单应逐字段一致");
        assertEquals(first.getManifest().toJson(), second.getManifest().toJson());
    }

    @Test
    void 清单按章稿版本持久化_查询往返一致() {
        assembler.setManifestStore(manifestRepo);
        assembler.assemble(pid, 2, "第2章", "林凡夜探城主府", null);

        var latest = manifestRepo.latest(pid, 2);
        assertTrue(latest.isPresent(), "组装后清单应已落库");
        assertEquals(1, latest.get().getChapterVersion());
        assertTrue(latest.get().entriesOfKind(InjectionManifest.Kind.FACT).stream()
                .anyMatch(e -> factId.equals(e.refId)), "落库 payload 应含事实条目");

        var forVersion = manifestRepo.forVersion(pid, 2, 1);
        assertTrue(forVersion.isPresent());
        assertEquals(latest.get(), forVersion.get());

        assertTrue(manifestRepo.history(pid, 2).size() >= 1);
    }

    @Test
    void JSON往返_字段完整() {
        WritingContext ctx = assembler.assemble(pid, 2, "第2章", "林凡夜探城主府", null);
        InjectionManifest round = InjectionManifest.fromJson(ctx.getManifest().toJson());
        assertEquals(ctx.getManifest(), round, "JSON 序列化往返应无损");
    }
}
