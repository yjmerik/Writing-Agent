package com.writing.novel.web;

import com.writing.novel.domain.*;
import com.writing.novel.persistence.sqlite.*;
import com.writing.novel.service.PlanEditionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PlanConfirmationAtomicityTest {
    @TempDir Path dir;
    ConnectionProvider cp;
    SqliteNovelRepository repo;
    PlanEditionService service;
    String pid, previewId, sourceHash, concurrentUpdate;
    List<PlanChange.PromiseDecision> decisions;
    @BeforeEach void seed() {
        cp=new ConnectionProvider(dir.resolve("plan.db").toString()); new SqliteSchemaInitializer(cp).initialize();
        repo=new SqliteNovelRepository(cp) {
            @Override public PlanChange commitPlanEdition(String projectId, String previewId, String hash,
                    WritingPreferences prefs, Outline outline, List<PlanChange.PromiseDecision> decisions, String key) {
                if (concurrentUpdate!=null) { sql(concurrentUpdate); concurrentUpdate=null; }
                return super.commitPlanEdition(projectId,previewId,hash,prefs,outline,decisions,key);
            }
        };
        var p=new Project("原子计划","概念","t"); repo.saveProject(p); pid=p.getId();
        repo.saveWritingPreferences(new WritingPreferences(pid));
        var outline=new Outline(pid,"总纲"); var volume=new Volume(pid,1,"第一卷","完整摘要");
        for(int i=1;i<=8;i++) volume.addSynopsis(new ChapterSynopsis(pid,i,"章"+i,"完整梗概"+i));
        outline.addVolume(volume); repo.saveOutline(outline);
        var promise=new Foreshadowing(pid,"旧承诺","埋设",1); promise.setExpectedResolveChapter(8); repo.saveForeshadowing(promise);
        service=new PlanEditionService(repo); var preview=service.previewTarget(pid,4);
        previewId=(String)preview.get("previewId"); sourceHash=(String)preview.get("sourcePlanHash");
        decisions=List.of(new PlanChange.PromiseDecision("decision",previewId,promise.getId(),"MIGRATE",4,null));
    }
    void sql(String statement) {
        try(var c=cp.getConnection();var s=c.createStatement()){s.execute(statement);}
        catch(Exception e){throw new RuntimeException(e);}
    }
    List<String> snapshot() throws Exception {
        var rows=new ArrayList<String>();
        try(var c=cp.getConnection();var s=c.createStatement()) {
            for(String table:List.of("writing_preferences","outlines","volumes","chapter_synopses","promise_decisions","plan_changes")) {
                try(var rs=s.executeQuery("SELECT * FROM "+table+" ORDER BY 1")) {
                    while(rs.next()) { var row=new ArrayList<String>(); row.add(table);
                        for(int i=1;i<=rs.getMetaData().getColumnCount();i++) row.add(rs.getString(i));
                        rows.add(row.toString());
                    }
                }
            }
        }
        return rows;
    }
    Map<String,Object> confirm(){return service.confirmTarget(pid,previewId,sourceHash,4,decisions,"same-request");}
    @ParameterizedTest @ValueSource(strings={"INSERT ON promise_decisions","UPDATE ON writing_preferences","DELETE ON chapter_synopses","UPDATE ON plan_changes"})
    void everyWriteFailureRollsBackEntirePlanAndSameRequestCanRetry(String point) throws Exception {
        var before=snapshot();
        sql("CREATE TRIGGER fail_plan BEFORE "+point+" BEGIN SELECT RAISE(ABORT,'persistent injected failure'); END");
        assertThrows(RuntimeException.class,this::confirm);
        assertEquals(before,snapshot(),"All objects, including original outline and PENDING preview, must be unchanged");
        sql("DROP TRIGGER fail_plan");
        assertEquals(true,confirm().get("applied"));
        assertEquals(4,repo.findWritingPreferences(pid).orElseThrow().getTargetChapters());
        assertEquals(4,repo.findOutlineByProject(pid).orElseThrow().getVolumes().get(0).getSynopses().size());
        assertEquals(PlanChange.Status.CONFIRMED,repo.findPlanChange(pid,previewId).orElseThrow().getStatus());
        var applied=snapshot(); assertThrows(IllegalStateException.class,this::confirm);
        assertEquals(applied,snapshot(),"A repeated successful request must not mutate any object");
    }
    @ParameterizedTest @ValueSource(strings={"synopsis","preferences","boundary","promise"})
    void sourceChangeImmediatelyBeforeAtomicCommitRejectsStalePreview(String change) {
        concurrentUpdate=switch(change) {
            case "synopsis" -> "UPDATE chapter_synopses SET synopsis='concurrent edit' WHERE chapter_index=1";
            case "preferences" -> "UPDATE writing_preferences SET target_chapters=30";
            case "boundary" -> "UPDATE projects SET current_chapter_index=5";
            default -> "UPDATE foreshadowings SET expected_resolve_chapter=7";
        };
        assertThrows(IllegalStateException.class,this::confirm);
        assertEquals(PlanChange.Status.PENDING,repo.findPlanChange(pid,previewId).orElseThrow().getStatus());
        assertNotEquals(4,repo.findWritingPreferences(pid).orElseThrow().getTargetChapters());
        assertEquals(8,repo.findOutlineByProject(pid).orElseThrow().getVolumes().get(0).getSynopses().size());
    }
}
