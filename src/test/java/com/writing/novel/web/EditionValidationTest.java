package com.writing.novel.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.agent.CheckResultEnvelope;
import com.writing.novel.domain.*;
import com.writing.novel.persistence.sqlite.CheckResultRepository;
import com.writing.novel.spi.NovelRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(StubLlmGatewayTestConfig.class)
@TestPropertySource(properties = {"persistence.embedding=stub", "llm.replay.mode=replay",
        "persistence.sqlite.db-path=./data/test-edition-validation.db",
        "persistence.vector-store.index-path=./data/test-edition-validation.idx"})
class EditionValidationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired NovelRepository repo;
    @Autowired CheckResultRepository checks;

    private String project() {
        Project p = new Project("定稿校验", "测试", "mystery");
        repo.saveProject(p); return p.getId();
    }
    private Chapter chapter(String id, int index, boolean checked) {
        Chapter c = new Chapter(id, index, "章节" + index);
        c.setContent("章节" + index + "正文。".repeat(100)); c.setStatus(Chapter.Status.DONE);
        repo.saveChapter(c);
        if (checked) checks.save(id, index, "continuity", CheckResultEnvelope.Status.VALID_EMPTY, "无矛盾");
        approveChapter(id, index);
        return c;
    }
    private void approveChapter(String id, int index) {
        var ctx = repo.readReviewContext(id, index);
        repo.approveChapter(new ChapterApproval.Request(ctx.identity(), ctx.reviewSetHash(), UUID.randomUUID().toString(), "作者确认"));
    }
    private JsonNode create(String id) throws Exception {
        return om.readTree(mvc.perform(post("/api/projects/{id}/book-editions", id))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private String path(String id, JsonNode e) { return "/api/projects/" + id + "/book-editions/" + e.path("editionId").asText(); }
    private JsonNode validate(String id, JsonNode e) throws Exception {
        return om.readTree(mvc.perform(post(path(id,e) + "/validate"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private org.springframework.test.web.servlet.ResultActions approve(String id, JsonNode e) throws Exception {
        return mvc.perform(post(path(id,e) + "/approve").contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("manifestHash", e.path("manifestHash").asText()))));
    }
    private ContinuityIssue issue(String id, int index) {
        var i = new ContinuityIssue(id,index,ContinuityIssue.Type.TIMELINE,ContinuityIssue.Severity.HIGH,"死亡人物重新出现");
        repo.saveContinuityIssue(i); return i;
    }
    @Test void approvedChaptersCannotBypassMissingReport() throws Exception {
        String id=project(); chapter(id,1,true); var e=create(id);
        approve(id,e).andExpect(status().isConflict());
    }
    @Test void earliestHighIssueOutsideSixtyChapterWindowBlocksEdition() throws Exception {
        String id=project(); for(int i=1;i<=65;i++) chapter(id,i,true);
        var issue=issue(id,1); var e=create(id); var r=validate(id,e);
        assertEquals("BLOCKED", r.path("status").asText());
        assertTrue(r.path("mustFix").toString().contains(issue.getId()));
        assertEquals(65,r.path("coverage").size()); approve(id,e).andExpect(status().isConflict());
    }
    @Test void missingAndUnknownChecksNeverCountAsCovered() throws Exception {
        String id=project();chapter(id,1,false);chapter(id,2,true);
        checks.save(id,2,"continuity",CheckResultEnvelope.Status.UNKNOWN,"输出截断");
        var r=validate(id,create(id));assertEquals("BLOCKED",r.path("status").asText());
        assertEquals(2,r.path("unknown").size());
    }
    @Test void cleanReportIsPersistedBoundToManifestAndRequiredForApproval() throws Exception {
        String id=project();chapter(id,1,true);var e=create(id);var r=validate(id,e);
        assertEquals(e.path("manifestHash"),r.path("manifestHash"));assertEquals("PASS",r.path("status").asText());
        mvc.perform(get(path(id,e)+"/validation")).andExpect(status().isOk())
                .andExpect(jsonPath("$.reportId").value(r.path("reportId").asText()));
        approve(id,e).andExpect(status().isOk());
    }
    @Test void changedWorkingCopyCannotTransferReportToNewEditionButOldRecordRemainsReviewable() throws Exception {
        String id=project();Chapter c=chapter(id,1,true);var old=create(id);validate(id,old);
        c.setContent("作者新稿。".repeat(100));repo.saveChapter(c);approveChapter(id,1);
        var next=create(id);assertNotEquals(old.path("manifestHash"),next.path("manifestHash"));
        mvc.perform(get(path(id,old)+"/validation")).andExpect(status().isOk())
                .andExpect(jsonPath("$.workingCopyChanged").value(true));
        approve(id,next).andExpect(status().isConflict());
        assertEquals("BLOCKED",validate(id,next).path("status").asText());
        approve(id,old).andExpect(status().isOk());
    }
    @Test void newProblemInvalidatesPreviouslyPassingReport() throws Exception {
        String id=project();chapter(id,1,true);var e=create(id);validate(id,e);issue(id,1);
        mvc.perform(get(path(id,e)+"/validation")).andExpect(status().isOk()).andExpect(jsonPath("$.stale").value(true));
        approve(id,e).andExpect(status().isConflict());
    }
    @Test void explicitVersionBoundExceptionIsShownWithoutDeletingProblem() throws Exception {
        String id=project();chapter(id,1,true);var i=issue(id,1);var identity=repo.readReviewContext(id,1).identity();
        repo.saveDisposition(new ReviewDisposition(UUID.randomUUID().toString(),id,i.getId(),"evidence",identity,
                ReviewDisposition.Action.EXCEPTION,"有意保留不可靠叙述",null,Instant.now()));
        var e=create(id);var r=validate(id,e);
        assertEquals("PASS",r.path("status").asText());assertEquals(1,r.path("exceptions").size());
        assertEquals(1,repo.findUnresolvedIssues(id).size());approve(id,e).andExpect(status().isOk());
    }
    @Test void singleUnresolvedPromiseBlocksWithoutQuantityTolerance() throws Exception {
        String id=project();chapter(id,1,true);
        var f=new Foreshadowing(id,"密信去向","第一章",1);f.setMustResolve(true);repo.saveForeshadowing(f);
        var e=create(id);var r=validate(id,e);assertEquals("BLOCKED",r.path("status").asText());
        assertTrue(r.path("promises").toString().contains(f.getId()));approve(id,e).andExpect(status().isConflict());
    }
}
