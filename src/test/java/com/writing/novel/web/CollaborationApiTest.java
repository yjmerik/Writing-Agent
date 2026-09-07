package com.writing.novel.web;
import com.writing.novel.domain.*;
import com.writing.novel.orchestrator.NovelOrchestrator;
import com.writing.novel.persistence.sqlite.*;
import com.writing.novel.service.WritingTaskExecutor;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.Path;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class CollaborationApiTest {
 @TempDir Path dir;
 SqliteNovelRepository repo;WritingTaskExecutor executor;MockMvc mvc;Project project;String url;
 @BeforeEach void setup(){
  var cp=new ConnectionProvider(dir.resolve("api.db").toString());new SqliteSchemaInitializer(cp).initialize();repo=new SqliteNovelRepository(cp);
  executor=new WritingTaskExecutor(mock(NovelOrchestrator.class),repo);project=new Project("书","构思","标签");repo.saveProject(project);url="/api/projects/"+project.getId()+"/collaboration";
  mvc=MockMvcBuilders.standaloneSetup(new CollaborationController(repo,executor)).setControllerAdvice(new GlobalExceptionHandler()).build();
 }
 @AfterEach void close(){executor.shutdown();}
 @Test void statusAndIdempotentStopPreserveNovelStage() throws Exception {
  mvc.perform(get(url)).andExpect(status().isOk()).andExpect(jsonPath("$.canContinue").value(true)).andExpect(jsonPath("$.busy").value(false));
  mvc.perform(post(url+"/stop")).andExpect(status().isOk());
  assertEquals(NovelState.CONCEIVING,repo.findProject(project.getId()).orElseThrow().getState());
 }
 @Test void reviewPolicyDoesNotApproveExistingReview() throws Exception {
  var review=new Checkpoint(project.getId(),Checkpoint.Type.AFTER_INITIAL_SETUP,"审核");repo.saveCheckpoint(review);
  mvc.perform(put(url+"/review-policy").contentType("application/json").content("{\"policy\":\"MANUAL\"}")).andExpect(status().isOk());
  mvc.perform(post(url+"/start").contentType("application/json").content("{}")).andExpect(status().isConflict());
  mvc.perform(get(url)).andExpect(jsonPath("$.pendingReviews").value(1)).andExpect(jsonPath("$.canContinue").value(false));
  assertEquals(Checkpoint.Status.PENDING,repo.findCheckpoint(review.getId()).orElseThrow().getStatus());
 }
 @Test void policyRequiresIdleAndRejectsUnknownValue() throws Exception {
  mvc.perform(put(url+"/review-policy").contentType("application/json").content("{\"policy\":\"UNKNOWN\"}")).andExpect(status().isBadRequest());
  executor.beginWork(project.getId(),"editor");
  mvc.perform(put(url+"/review-policy").contentType("application/json").content("{\"policy\":\"VOLUME\"}")).andExpect(status().isConflict());
  mvc.perform(get(url)).andExpect(jsonPath("$.busy").value(true)).andExpect(jsonPath("$.canContinue").value(false));
  executor.endWork(project.getId(),"editor");
  mvc.perform(put(url+"/review-policy").contentType("application/json").content("{\"policy\":\"VOLUME\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.reviewPolicy").value("VOLUME"));
  assertEquals(-1,repo.findWritingPreferences(project.getId()).orElseThrow().getCheckpointSpan());
 }
 @Test void missingProjectIsRejected() throws Exception {mvc.perform(get("/api/projects/missing/collaboration")).andExpect(status().isBadRequest());}
 @Test void currentManualSaveDoesNotInheritPreviousStopRequest() throws Exception {
  var task=new WritingTask(project.getId(),24,WritingTask.Type.AUTO_RUN);
  task.setStatus(WritingTask.Status.PAUSED);task.setStopRequested(true);repo.saveWritingTask(task);
  executor.beginWork(project.getId(),"manual-save");
  try {
   mvc.perform(get(url)).andExpect(status().isOk())
     .andExpect(jsonPath("$.busy").value(true))
     .andExpect(jsonPath("$.stopRequested").value(false))
     .andExpect(jsonPath("$.task").doesNotExist());
  } finally { executor.endWork(project.getId(),"manual-save"); }
 }

}
