package com.writing.novel.orchestrator;
import com.writing.novel.domain.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AuthorChapterPreservationTest extends NovelOrchestratorWritingTest {
    private Project prepared() {
        Project p=projectService.create("作者定稿","故事","悬疑");
        orchestrator.runConceiving(p.getId());orchestrator.step(p.getId());return p;
    }
    @Test void completedHumanChapterBelowModelLengthIsNeverRegenerated() {
        Project p=prepared();Chapter ch=new Chapter(p.getId(),1,"作者标题");
        String body="作者逐字审核后保留的正文。".repeat(50);ch.setContent(body);ch.setStatus(Chapter.Status.DONE);repo.saveChapter(ch);
        Chapter result=orchestrator.runWritingOneChapter(p.getId());
        assertEquals("作者标题",result.getTitle());assertEquals(body,result.getContent());
        assertEquals(ch.getVersion(),result.getVersion());
        assertEquals(1,repo.findProject(p.getId()).orElseThrow().getCurrentChapterIndex());
    }
    @Test void unfinishedHumanDraftStopsBeforeGeneration() {
        Project p=prepared();Chapter ch=new Chapter(p.getId(),1,"未完成草稿");ch.setContent("作者正在写的细节");ch.setStatus(Chapter.Status.DRAFTING);repo.saveChapter(ch);
        assertThrows(IllegalStateException.class,()->orchestrator.runWritingOneChapter(p.getId()));
        assertEquals("作者正在写的细节",repo.findChapterByIndex(p.getId(),1).orElseThrow().getContent());
        assertEquals(0,repo.findProject(p.getId()).orElseThrow().getCurrentChapterIndex());
    }
}
