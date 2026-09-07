package com.writing.novel.web;
import com.writing.novel.domain.*;
import com.writing.novel.orchestrator.NovelOrchestrator;
import com.writing.novel.service.*;
import com.writing.novel.spi.NovelRepository;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class CollaborationEditOwnershipTest {
 NovelRepository repo;WritingTaskExecutor executor;NovelOrchestrator orchestrator;CanonPatchService canon;
 @BeforeEach void setup(){repo=mock(NovelRepository.class);orchestrator=mock(NovelOrchestrator.class);canon=mock(CanonPatchService.class);executor=new WritingTaskExecutor(orchestrator,repo);}
 @AfterEach void close(){executor.shutdown();}
 @Test void manualWorldSaveOwnsProjectUntilPatchFinishes(){
  var controller=new ContentController(repo,orchestrator,canon,executor);
  when(canon.createWorld(eq("p"),any(),anyInt())).thenAnswer(call->{
   assertTrue(executor.hasActiveTask("p"),"人工保存必须持续持有工作权");
   assertThrows(IllegalStateException.class,()->executor.startWriteChapter("p"));
   return new CanonPatchService.PatchResult(call.getArgument(1),List.of(),true);
  });
  controller.createWorld("p",Map.of("name","作者定稿","content","不可覆盖"));
  assertFalse(executor.hasActiveTask("p"));
 }
 @Test void checkpointApprovalOwnsProjectThroughStateChange(){
  var cp=new Checkpoint("p",Checkpoint.Type.AFTER_CHAPTER,"审阅");
  var targets=mock(CheckpointTargetService.class);when(targets.requirePending("p",cp.getId())).thenReturn(cp);
  doAnswer(call->{assertTrue(executor.hasActiveTask("p"));assertThrows(IllegalStateException.class,()->executor.startWriteChapter("p"));return null;}).when(orchestrator).approveCheckpoint("p",cp.getId());
  new CheckpointController(repo,orchestrator,targets,executor).approve("p",cp.getId());
  assertFalse(executor.hasActiveTask("p"));
 }
 @Test void failedSaveReleasesOnlyItsOwnWork(){
  when(canon.createWorld(eq("p"),any(),anyInt())).thenThrow(new IllegalArgumentException("invalid"));
  assertThrows(IllegalArgumentException.class,()->new ContentController(repo,orchestrator,canon,executor).createWorld("p",Map.of()));
  assertFalse(executor.hasActiveTask("p"));
 }
}
