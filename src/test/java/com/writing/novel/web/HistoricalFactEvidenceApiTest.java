package com.writing.novel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.domain.*;
import com.writing.novel.persistence.FactEvidenceRepository;
import com.writing.novel.persistence.sqlite.StoryFactRepository;
import com.writing.novel.spi.NovelRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(StubLlmGatewayTestConfig.class)
@TestPropertySource(properties = {
        "persistence.embedding=stub",
        "persistence.sqlite.db-path=./data/test-historical-fact-evidence.db",
        "persistence.vector-store.index-path=./data/test-historical-fact-evidence-vecs.idx"
})
class HistoricalFactEvidenceApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired NovelRepository repository;
    @Autowired StoryFactRepository facts;
    @Autowired FactEvidenceRepository evidence;
    private static final String OLD_QUOTE = "旧稿中铜钥匙留在井底。";
    private static final String NEW_QUOTE = "新稿中铜钥匙已被取走。";
    private static final String BODY = "雨夜来客沿着石阶寻找失落的线索，院内只剩一盏灯。".repeat(30);
    record Fixture(String pid, String factId, Chapter historical, Chapter current) {}

    private String project() throws Exception {
        var response = mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("title", "history evidence", "concept", "c",
                        "tags", "", "mode", "SOLO"))))
                .andExpect(status().isOk()).andReturn();
        return om.readTree(response.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("id").asText();
    }

    private Fixture seed() throws Exception {
        String pid = project();
        Chapter initial = new Chapter(pid, 1, "初稿");
        initial.setContent(BODY);
        initial.setStatus(Chapter.Status.DONE);
        repository.saveChapter(initial);
        edit(pid, "历史标题", BODY + OLD_QUOTE);
        Chapter historical = repository.findChapterByIndex(pid, 1).orElseThrow();
        edit(pid, "当前标题", BODY + NEW_QUOTE);
        Chapter current = repository.findChapterByIndex(pid, 1).orElseThrow();
        assertNotEquals(historical.getVersion(), current.getVersion());
        var snapshot = repository.findChapterVersion(pid, 1, historical.getVersion()).orElseThrow();
        assertEquals(historical.getContent(), snapshot.getContent());
        StoryFact fact = new StoryFact(pid, StoryFact.FactType.WORLD_RULE, "铜钥匙", "位置变动", 1);
        facts.save(fact);
        return new Fixture(pid, fact.getId(), historical, current);
    }

    private void edit(String pid, String title, String content) throws Exception {
        Chapter current = repository.findChapterByIndex(pid, 1).orElseThrow();
        mvc.perform(put("/api/projects/{id}/chapters/1", pid).contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("expectedVersion", current.getVersion(),
                        "title", title, "content", content))))
                .andExpect(status().isOk());
    }

    private Map<String, Object> request(Chapter chapter) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chapterIndex", 1);
        body.put("chapterVersion", chapter.getVersion());
        body.put("contentHash", ChapterApproval.contentHash(chapter.getTitle(), chapter.getContent()));
        return body;
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void acceptsActualHistoricalSnapshotWithKnownOrUnknownQuote(boolean known) throws Exception {
        Fixture f = seed();
        var body = request(f.historical());
        if (known) body.put("quote", OLD_QUOTE);
        mvc.perform(post("/api/projects/{id}/facts/{fid}/evidence", f.pid(), f.factId())
                .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.quoteKnown").value(known));
        var stored = evidence.findByFact(f.pid(), f.factId());
        assertEquals(1, stored.size());
        assertEquals(f.historical().getVersion(), stored.get(0).get("chapterVersion"));
        assertEquals(body.get("contentHash"), stored.get(0).get("contentHash"));
        assertEquals(known ? OLD_QUOTE : null, stored.get(0).get("quote"));
        assertEquals(f.current().getContent(), repository.findChapterByIndex(f.pid(), 1).orElseThrow().getContent());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missingVersion", "wrongHash", "currentHash", "currentOnlyQuote", "foreignFact", "foreignSnapshot"})
    void rejectsUnverifiableHistoricalSourcesWithoutWriting(String invalid) throws Exception {
        Fixture f = seed();
        var body = request(f.historical());
        body.put("quote", OLD_QUOTE);
        String pid = f.pid();
        switch (invalid) {
            case "missingVersion" -> body.put("chapterVersion", 999);
            case "wrongHash" -> body.put("contentHash", "forged");
            case "currentHash" -> body.put("contentHash", request(f.current()).get("contentHash"));
            case "currentOnlyQuote" -> body.put("quote", NEW_QUOTE);
            case "foreignFact" -> pid = project();
            case "foreignSnapshot" -> {
                Fixture other = seed();
                edit(other.pid(), "别的项目", BODY + "另一个来源。");
                Chapter foreign = repository.findChapterByIndex(other.pid(), 1).orElseThrow();
                edit(other.pid(), "再修改", BODY + "另一个来源的新稿。");
                body.put("chapterVersion", foreign.getVersion());
                body.put("contentHash", request(foreign).get("contentHash"));
            }
            default -> fail("unknown test case");
        }
        mvc.perform(post("/api/projects/{id}/facts/{fid}/evidence", pid, f.factId())
                .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
        assertTrue(evidence.findByFact(f.pid(), f.factId()).isEmpty());
    }
}
