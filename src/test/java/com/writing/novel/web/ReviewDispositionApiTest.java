package com.writing.novel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.domain.*;
import com.writing.novel.spi.NovelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @Import(StubLlmGatewayTestConfig.class)
@TestPropertySource(properties={"persistence.embedding=stub", "persistence.sqlite.db-path=./data/test-review-disp.db", "persistence.vector-store.index-path=./data/test-review-disp-vecs.idx"})
class ReviewDispositionApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired NovelRepository repository;
    String pid;
    ContinuityIssue issue;
    ChapterApproval.Identity identity;
    @BeforeEach void seed() {
        var project=new Project("裁决归属", "c", "t"); project.setState(NovelState.PAUSED_AT_CHECKPOINT);
        repository.saveProject(project); pid=project.getId();
        var ch=new Chapter(pid,1,"第一章"); ch.setContent("真实正文。".repeat(200));
        ch.setStatus(Chapter.Status.NEEDS_MANUAL_REVIEW); repository.saveChapter(ch);
        repository.saveReviewBarrier(new ReviewBarrier(pid,1,ch.getVersion(),"HIGH"));
        issue=new ContinuityIssue(pid,1,ContinuityIssue.Type.TIMELINE,ContinuityIssue.Severity.HIGH,"时间矛盾");
        repository.saveContinuityIssue(issue); identity=repository.readReviewContext(pid,1).identity();
    }
    Map<String,Object> body() {
        return new HashMap<>(Map.of("issueId",issue.getId(),"action","FALSE_POSITIVE","reason","作者核实",
                "identity",new HashMap<>(Map.of("projectId",pid,"chapterIndex",1,"version",identity.version(),"contentHash",identity.contentHash()))));
    }
    @Test void createAndListActualDispositionThenApprove() throws Exception {
        mvc.perform(post("/api/projects/{id}/dispositions",pid).contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.action").value("FALSE_POSITIVE"));
        mvc.perform(get("/api/projects/{id}/dispositions",pid).param("chapterIndex","1").param("chapterVersion",String.valueOf(identity.version())).param("contentHash",identity.contentHash()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        var ctx=repository.readReviewContext(pid,1);
        mvc.perform(post("/api/projects/{id}/chapters/1/review-decisions",pid).contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("identity",ctx.identity(),"reviewSetHash",ctx.reviewSetHash(),"idempotencyKey",UUID.randomUUID().toString()))))
                .andExpect(status().isOk());
        assertEquals(Chapter.Status.DONE,repository.findChapterByIndex(pid,1).orElseThrow().getStatus());
    }
    @ParameterizedTest @CsvSource({"missingIssue,400","foreignIssue,400","wrongChapter,400","missingChapter,400","version,409","hash,409","missingVersion,400","missingHash,400","identityShape,400","missingReason,400","nullReason,400","foreignIdentity,400"})
    void invalidSourceRejectedWithoutSaving(String fault,int expected) throws Exception {
        var body=body();
        @SuppressWarnings("unchecked") var source=(Map<String,Object>)body.get("identity");
        switch(fault) {
            case "missingIssue" -> body.put("issueId","missing");
            case "foreignIssue" -> {
                var other=new Project("另一个项目","c","t"); repository.saveProject(other);
                var foreign=new ContinuityIssue(other.getId(),1,ContinuityIssue.Type.PLOT,ContinuityIssue.Severity.HIGH,"外部问题");
                repository.saveContinuityIssue(foreign); body.put("issueId",foreign.getId());
            }
            case "wrongChapter" -> { var ch=new Chapter(pid,2,"第二章"); ch.setContent("另一章"); repository.saveChapter(ch); source.put("chapterIndex",2); }
            case "missingChapter" -> source.put("chapterIndex",999);
            case "version" -> source.put("version",999);
            case "hash" -> source.put("contentHash","fake");
            case "missingVersion" -> source.remove("version");
            case "missingHash" -> source.remove("contentHash");
            case "identityShape" -> body.put("identity","wrong shape");
            case "missingReason" -> body.remove("reason");
            case "nullReason" -> body.put("reason",null);
            case "foreignIdentity" -> source.put("projectId","other");
        }
        mvc.perform(post("/api/projects/{id}/dispositions",pid).contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body)))
                .andExpect(status().is(expected));
        // Query both the submitted and actual identity; rejected writes must not leave an audit row.
        assertTrue(repository.findDispositions(pid,1,identity.version(),identity.contentHash()).isEmpty());
        if(source.get("version") instanceof Number version && source.get("contentHash") instanceof String hash)
            assertTrue(repository.findDispositions(pid,((Number)source.get("chapterIndex")).intValue(),version.intValue(),hash).isEmpty());
        assertNull(repository.readReviewContext(pid,1).approval());
        assertEquals(1,repository.readReviewContext(pid,1).barrierIds().size());
    }
}
