package com.writing.novel.web;

import com.writing.novel.domain.*;
import com.writing.novel.domain.Character;
import com.writing.novel.domain.EntityType;
import com.writing.novel.domain.RelationshipType;
import com.writing.novel.persistence.sqlite.*;
import com.writing.novel.service.CharacterAsOfService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * NCS-010（方案 §07/R01/R15）：人物时点读取 API。
 * as-of(N) 只返回时点 N 之前确立的状态事实、N 前获知的引文（含来源）、
 * 该时点有效的关系边；信念（所信但事实已变）与已知分开；N 之后的内容不可见。
 */
class CharacterAsOfTest {

    @TempDir
    Path dir;

    private SqliteNovelRepository repo;
    private StoryFactRepository factRepo;
    private SqliteStoryCanon canon;
    private MockMvc mvc;
    private String projectId;

    @BeforeEach
    void setUp() {
        var cp = new ConnectionProvider(dir.resolve("asof.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        factRepo = new StoryFactRepository(cp);
        canon = new SqliteStoryCanon(factRepo, repo);
        Project project = new Project("时点项目", "构思", "武侠");
        repo.saveProject(project);
        projectId = project.getId();

        // 人物：沈昭
        Character shen = new Character(projectId, "沈昭", "渡口少女");
        repo.saveCharacter(shen);

        // 状态事实：第 8 章不识字（negative）；第 15 章识字（取代）
        StoryFact illiterate = new StoryFact(projectId, StoryFact.FactType.CHARACTER_STATE,
                "沈昭", "沈昭不识字", 8);
        illiterate.setPredicate("ability.read");
        illiterate.setPolarity("negative");
        canon.harvest(illiterate);
        StoryFact literate = new StoryFact(projectId, StoryFact.FactType.CHARACTER_STATE,
                "沈昭", "沈昭学会识字", 15);
        literate.setPredicate("ability.read");
        literate.setPolarity("positive");
        canon.harvest(literate); // 同谓词同极性? negative vs positive → 冲突双方 ACTIVE
        // 第 12 章位置事实（另一谓词并存）
        StoryFact atGate = new StoryFact(projectId, StoryFact.FactType.CHARACTER_STATE,
                "沈昭", "沈昭位于城门", 12);
        atGate.setPredicate("location");
        atGate.setObjectValue("城门");
        atGate.setPolarity("positive");
        canon.harvest(atGate);

        // 证据与知情：密信事实，沈昭第 10 章得知（quote）
        StoryFact letter = new StoryFact(projectId, StoryFact.FactType.OBJECT_STATE,
                "密信", "江砚把密信交给沈昭", 8);
        canon.harvest(letter);
        String letterId = canon.recallBySubject(projectId, "密信").get(0).getId();
        String evidenceId = java.util.UUID.randomUUID().toString();
        factRepo.saveFactEvidence(evidenceId, letterId, projectId, 8, 1,
                "hash-8", "江砚把信塞进沈昭袖中", 3, "anchor-8", 8, null);
        factRepo.saveObserver(evidenceId, "沈昭", 10, 1);

        // 关系边：沈昭—江砚（第 9 章起信任；第 16 章破裂）
        Entity shenEntity = new Entity(projectId, "沈昭", EntityType.CHARACTER);
        Entity jiangEntity = new Entity(projectId, "江砚", EntityType.CHARACTER);
        repo.saveEntity(shenEntity);
        repo.saveEntity(jiangEntity);
        EntityEdge trust = new EntityEdge(projectId, shenEntity.getId(), jiangEntity.getId(),
                RelationshipType.KNOWS);
        trust.setValidFromChapter(9);
        trust.setValidToChapter(16);
        repo.saveEntityEdge(trust);

        mvc = MockMvcBuilders.standaloneSetup(new CharacterAsOfController(
                new CharacterAsOfService(repo, new SqliteStoryCanon(factRepo, repo),
                        new com.writing.novel.persistence.FactEvidenceRepository(factRepo))))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void 时点12_只见12前事实与知情_关系有效() throws Exception {
        String url = "/api/projects/" + projectId + "/characters/沈昭/as-of?chapter=12";
        mvc.perform(get(url))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.character.name").value("沈昭"))
                .andExpect(jsonPath("$.asOfChapter").value(12))
                // 第 12 章时 ability.read 仍是"不识字"（第 15 章的识字记录不可见）
                .andExpect(jsonPath("$.facts[?(@.predicate=='ability.read')].assertion")
                        .value(org.hamcrest.Matchers.hasItem("沈昭不识字")))
                .andExpect(jsonPath("$.facts[?(@.predicate=='location')].objectValue").value("城门"))
                // 第 10 章获知的密信引文（含来源章）
                .andExpect(jsonPath("$.knows.length()").value(1))
                .andExpect(jsonPath("$.knows[0].quote").value("江砚把信塞进沈昭袖中"))
                .andExpect(jsonPath("$.knows[0].learnedAtChapter").value(10))
                // 关系边在第 12 章有效
                .andExpect(jsonPath("$.relationships.length()").value(1))
                .andExpect(jsonPath("$.relationships[0].target").value("江砚"))
                .andExpect(jsonPath("$.relationships[0].relationship").value("KNOWS"));
    }

    @Test
    void 时点20_信念与事实分开_过期关系不可见() throws Exception {
        String url = "/api/projects/" + projectId + "/characters/沈昭/as-of?chapter=20";
        mvc.perform(get(url))
                .andExpect(status().isOk())
                // 关系已破裂（validTo=16）→ 第 20 章不可见
                .andExpect(jsonPath("$.relationships.length()").value(0))
                // knows 仍含第 10 章引文；belief 标记由 fact 状态驱动（密信事实仍 ACTIVE → knows）
                .andExpect(jsonPath("$.knows.length()").value(1));
    }

    @Test
    void 事实被撤回后_知情呈现为可能过时的信念() throws Exception {
        // 密信唯一证据随第 8 章修订失效 → 事实 UNSUPPORTED（NCS-009）
        new com.writing.novel.service.SupportWithdrawalService(factRepo)
                .onChapterRevised(projectId, 8, 2);
        String url = "/api/projects/" + projectId + "/characters/沈昭/as-of?chapter=12";
        mvc.perform(get(url))
                .andExpect(status().isOk())
                // 事实已失去支持：知情记录转入"所信（可能与客观相反）"，不再当作当前事实
                .andExpect(jsonPath("$.believes.length()").value(1))
                .andExpect(jsonPath("$.believes[0].quote").value("江砚把信塞进沈昭袖中"))
                .andExpect(jsonPath("$.knows.length()").value(0));
    }

    @Test
    void 人物不存在返回400() throws Exception {
        mvc.perform(get("/api/projects/" + projectId + "/characters/无此人/as-of?chapter=5"))
                .andExpect(status().isBadRequest());
    }
}
