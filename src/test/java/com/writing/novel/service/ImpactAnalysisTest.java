package com.writing.novel.service;

import com.writing.novel.domain.*;
import com.writing.novel.domain.Character;
import com.writing.novel.persistence.sqlite.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-014（方案 §12/R04/F01）：文本变更影响分析。
 * 修订章后扫描后文：锚定事实命中→MUST_REPAIR；仅实体名出现→RECHECK；
 * 无关章→UNAFFECTED_WITH_REASON 且理由保留；覆盖说明非空（含未知部分声明）。
 */
class ImpactAnalysisTest {

    @TempDir
    Path tempDir;

    private SqliteNovelRepository repo;
    private StoryFactRepository factRepo;
    private SqliteStoryCanon canon;
    private ImpactAnalysisService service;
    private String projectId;

    @BeforeEach
    void setUp() {
        var cp = new ConnectionProvider(tempDir.resolve("impact.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        factRepo = new StoryFactRepository(cp);
        canon = new SqliteStoryCanon(factRepo, repo);
        service = new ImpactAnalysisService(repo, canon, factRepo);
        Project project = new Project("影响项目", "构思", "武侠");
        repo.saveProject(project);
        projectId = project.getId();
        repo.saveCharacter(new Character(projectId, "沈昭", "渡口少女"));
        repo.saveCharacter(new Character(projectId, "江砚", "游方郎中"));
    }

    private Chapter chapter(int index, String content) {
        Chapter ch = new Chapter(projectId, index, "第" + index + "章");
        ch.setContent(content);
        ch.setStatus(Chapter.Status.DONE);
        repo.saveChapter(ch);
        return ch;
    }

    @Test
    void 锚定事实后文命中_列为MUST_REPAIR() {
        chapter(8, "江砚把密信交给沈昭，信中名单足以致命。");
        chapter(12, "沈昭凭着那封密信里的名单，向官差告了密。");
        chapter(20, "官差在堂上展示了密信原件，字迹确凿。");
        chapter(9, "隔壁镇的绣娘晾晒新染的绸缎，无人谈及书信。");

        // 事实锚定第 8 章 v1（含后文依赖的密信去向）
        StoryFact letter = new StoryFact(projectId, StoryFact.FactType.OBJECT_STATE,
                "密信", "密信由江砚交给沈昭", 8);
        canon.harvest(letter);
        StoryFact saved = canon.recallBySubject(projectId, "密信").get(0);
        factRepo.saveFactEvidence(java.util.UUID.randomUUID().toString(), saved.getId(), projectId,
                8, 1, "hash-8", "江砚把密信交给沈昭", 1, "anchor-8", 8, null);
        factRepo.saveObserver("e1", "沈昭", 8, 1);

        // 第 8 章修订（版本推进）
        Chapter ch8 = repo.findChapterByIndex(projectId, 8).orElseThrow();
        ch8.setContent("江砚把密信凑近烛火，当着沈昭的面焚毁了它。");
        repo.saveChapter(ch8);
        int newVersion = repo.findChapterByIndex(projectId, 8).orElseThrow().getVersion();

        var plan = service.analyze(projectId, 8, newVersion);

        var must = plan.items().stream().filter(i -> i.classification() == ImpactItem.Classification.MUST_REPAIR).toList();
        // 第 12、20 章正文包含"密信"（锚定事实的 subject）→ MUST_REPAIR
        assertEquals(2, must.size(), "锚定事实的后文命中必须列为 MUST_REPAIR");
        assertTrue(must.stream().anyMatch(i -> i.chapterIndex() == 12));
        assertTrue(must.stream().anyMatch(i -> i.chapterIndex() == 20));
        var ch12 = must.stream().filter(i -> i.chapterIndex() == 12).findFirst().orElseThrow();
        assertTrue(ch12.reasons().stream().anyMatch(r -> r.contains("密信")),
                "理由必须点名受影响实体");
        assertTrue(ch12.factIds().contains(saved.getId()));
    }

    @Test
    void 仅实体名出现无锚定事实_列为RECHECK() {
        chapter(5, "沈昭在码头帮工，结识了船夫老周。");
        chapter(10, "沈昭收到江砚托人带来的口信。");
        // 第 5 章收割一个事实（锚定 v1）
        StoryFact job = new StoryFact(projectId, StoryFact.FactType.CHARACTER_STATE,
                "老周", "老周是渡口船夫", 5);
        canon.harvest(job);

        Chapter ch5 = repo.findChapterByIndex(projectId, 5).orElseThrow();
        ch5.setContent("沈昭在码头帮工，船夫已换作旁人。");
        repo.saveChapter(ch5);
        int newVersion = repo.findChapterByIndex(projectId, 5).orElseThrow().getVersion();

        var plan = service.analyze(projectId, 5, newVersion);
        var ch10 = plan.items().stream().filter(i -> i.chapterIndex() == 10).findFirst().orElseThrow();
        // 第 10 章"沈昭"出现但无锚定于第 5 章的事实支持 → RECHECK
        assertEquals(ImpactItem.Classification.RECHECK, ch10.classification());
        assertTrue(ch10.reasons().size() > 0);
    }

    @Test
    void 无关章列为UNAFFECTED_WITH_REASON_理由保留() {
        chapter(8, "江砚把密信交给沈昭。");
        chapter(12, "沈昭凭名单告密。");
        chapter(9, "绣娘晾晒绸缎。"); // 无关支线
        StoryFact letter = new StoryFact(projectId, StoryFact.FactType.OBJECT_STATE,
                "密信", "密信由江砚交给沈昭", 8);
        canon.harvest(letter);

        Chapter ch8 = repo.findChapterByIndex(projectId, 8).orElseThrow();
        ch8.setContent("江砚焚毁了密信。");
        repo.saveChapter(ch8);
        int newVersion = repo.findChapterByIndex(projectId, 8).orElseThrow().getVersion();

        var plan = service.analyze(projectId, 8, newVersion);
        var ch9 = plan.items().stream().filter(i -> i.chapterIndex() == 9).findFirst().orElseThrow();
        assertEquals(ImpactItem.Classification.UNAFFECTED_WITH_REASON, ch9.classification());
        assertTrue(ch9.reasons().stream().anyMatch(r -> r.contains("未发现")),
                "无关理由必须保留（不因首轮未列出就拒绝检查）");
    }

    @Test
    void 覆盖说明非空_含未知声明() {
        chapter(8, "江砚把密信交给沈昭。");
        StoryFact letter = new StoryFact(projectId, StoryFact.FactType.OBJECT_STATE,
                "密信", "密信由江砚交给沈昭", 8);
        canon.harvest(letter);
        Chapter ch8 = repo.findChapterByIndex(projectId, 8).orElseThrow();
        ch8.setContent("江砚焚毁了密信。");
        repo.saveChapter(ch8);

        var plan = service.analyze(projectId, 8, ch8.getVersion());
        assertNotNull(plan.coverageNote());
        assertTrue(plan.coverageNote().contains("词法"), "覆盖说明必须声明扫描方式（词法级）");
        assertTrue(plan.coverageNote().contains("未知") || plan.coverageNote().contains("语义"),
                "必须声明词法扫描不能证明语义无影响（不能把未检查改标为无影响）");
    }

    @Test
    void 事实建立在别章但证据锚定修订章也须传播() {
        chapter(8, "密信落款得到核实。"); chapter(12, "密信被呈上公堂。");
        StoryFact fact = new StoryFact(projectId, StoryFact.FactType.OBJECT_STATE, "密信", "密信属实", 3);
        canon.harvest(fact);
        factRepo.saveFactEvidence("e8", fact.getId(), projectId, 8, 1, "hash", "密信落款", 0, "anchor", 8, null);
        var plan = service.analyze(projectId, 8, 2);
        assertEquals(ImpactItem.Classification.MUST_REPAIR, plan.items().get(0).classification());
        assertTrue(plan.items().get(0).factIds().contains(fact.getId()));
    }

    @Test
    void 经关系链关联的未点名依赖不能判为无影响() {
        chapter(8, "密信被焚毁。"); chapter(12, "官府开始追捕告密者。");
        canon.harvest(new StoryFact(projectId, StoryFact.FactType.OBJECT_STATE, "密信", "密信已焚毁", 8));
        Entity letter = new Entity(projectId, "密信", EntityType.ITEM);
        Entity witness = new Entity(projectId, "证人", EntityType.CHARACTER);
        Entity office = new Entity(projectId, "官府", EntityType.FACTION);
        repo.saveEntity(letter); repo.saveEntity(witness); repo.saveEntity(office);
        repo.saveEntityEdge(new EntityEdge(projectId, witness.getId(), letter.getId(), RelationshipType.USES));
        repo.saveEntityEdge(new EntityEdge(projectId, witness.getId(), office.getId(), RelationshipType.SERVES));
        var item = service.analyze(projectId, 8, 2).items().get(0);
        assertEquals(ImpactItem.Classification.RECHECK, item.classification());
        assertTrue(item.reasons().stream().anyMatch(r -> r.contains("关系")));
    }

    @Test
    void 未修订章分析_空计划但覆盖说明仍在() {
        chapter(8, "江砚把密信交给沈昭。");
        var plan = service.analyze(projectId, 8,
                repo.findChapterByIndex(projectId, 8).orElseThrow().getVersion());
        // 修订章后无后文章节 → items 空（扫描范围 8+1..当前 = 空）
        assertTrue(plan.items().isEmpty());
        assertNotNull(plan.coverageNote());
    }
}
