# Plan 6: REST API + SSE 层

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement task-by-task.

**Goal:** 构建完整的 REST API + SSE 流式推送层，使整个系统可通过 HTTP 调用（curl/Postman 可验证），脱离前端独立运行。覆盖项目控制、设定查询、章节阅读、checkpoint 审核、流式生成。

**Architecture:** Spring `@RestController` 暴露 REST 端点，SseEmitter 推送流式生成进度。API 调用 ProjectService + NovelOrchestrator + NovelRepository，是薄薄的 Web 层。

**Tech Stack:** Spring Boot 3 Web（已在依赖中）, SseEmitter。

**Spec 参考:** spec 第 8 节（API 设计）。

---

## Task 1: ProjectController — 项目控制 API

**Files:**
- Create: `src/main/java/com/writing/novel/web/ProjectController.java`
- Create: `src/main/java/com/writing/novel/web/dto/CreateProjectRequest.java`
- Test: `src/test/java/com/writing/novel/web/ProjectControllerTest.java`

- [ ] **Step 1: 写测试（MockMvc）**

`src/test/java/com/writing/novel/web/ProjectControllerTest.java`:
```java
package com.writing.novel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.web.dto.CreateProjectRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.File;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "persistence.embedding=stub",
        "persistence.sqlite.db-path=./data/test-web.db",
        "persistence.vector-store.index-path=./data/test-web-vecs.idx"
})
class ProjectControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;

    @org.junit.jupiter.api.BeforeAll
    static void createDataDir() { new File("./data").mkdirs(); }

    @Test
    void 创建项目并查询() throws Exception {
        CreateProjectRequest req = new CreateProjectRequest("API测试小说", "剑修故事", "玄幻,热血", "SOLO");

        String json = mvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("API测试小说"))
                .andExpect(jsonPath("$.mode").value("SOLO"))
                .andReturn().getResponse().getContentAsString();

        String projectId = om.readTree(json).get("id").asText();

        mvc.perform(get("/api/projects/" + projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("API测试小说"));
    }

    @Test
    void 查询项目列表() throws Exception {
        mvc.perform(get("/api/projects"))
                .andExpect(status().isOk());
    }

    @Test
    void 切换模式() throws Exception {
        CreateProjectRequest req = new CreateProjectRequest("t", "c", "tag", "SOLO");
        String json = mvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andReturn().getResponse().getContentAsString();
        String id = om.readTree(json).get("id").asText();

        mvc.perform(put("/api/projects/" + id + "/mode")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("\"INTERACTIVE\""))
                .andExpect(status().isOk());

        mvc.perform(get("/api/projects/" + id))
                .andExpect(jsonPath("$.mode").value("INTERACTIVE"));
    }
}
```

- [ ] **Step 2: Create DTO**

`src/main/java/com/writing/novel/web/dto/CreateProjectRequest.java`:
```java
package com.writing.novel.web.dto;

public record CreateProjectRequest(String title, String concept, String tags, String mode) {}
```

- [ ] **Step 3: Create ProjectController**

`src/main/java/com/writing/novel/web/ProjectController.java`:
```java
package com.writing.novel.web;

import com.writing.novel.domain.Project;
import com.writing.novel.domain.ProjectMode;
import com.writing.novel.orchestrator.ProjectService;
import com.writing.novel.web.dto.CreateProjectRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @PostMapping
    public Project create(@RequestBody CreateProjectRequest req) {
        ProjectMode mode = req.mode() != null ? ProjectMode.valueOf(req.mode()) : ProjectMode.SOLO;
        return projectService.create(req.title(), req.concept(), req.tags(), mode);
    }

    @GetMapping
    public List<Project> list() {
        return projectService.findAll();
    }

    @GetMapping("/{id}")
    public Project get(@PathVariable String id) {
        return projectService.find(id).orElseThrow(() ->
                new IllegalArgumentException("项目不存在: " + id));
    }

    @PutMapping("/{id}/mode")
    public void switchMode(@PathVariable String id, @RequestBody String mode) {
        String cleanMode = mode.replace("\"", "");
        projectService.switchMode(id, ProjectMode.valueOf(cleanMode));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id) {
        projectService.delete(id);
    }
}
```

- [ ] **Step 4-6: Run, verify, commit**

```bash
mvn test -Dtest=ProjectControllerTest
git add src/main/java/com/writing/novel/web/ProjectController.java \
        src/main/java/com/writing/novel/web/dto/CreateProjectRequest.java \
        src/test/java/com/writing/novel/web/ProjectControllerTest.java
git commit -m "feat(web): ProjectController 项目控制 API"
```

---

## Task 2: OrchestratorController — 运行控制 + 状态查询

**Files:**
- Create: `src/main/java/com/writing/novel/web/OrchestratorController.java`
- Test: `src/test/java/com/writing/novel/web/OrchestratorControllerTest.java`

- [ ] **Step 1: 写测试**

`src/test/java/com/writing/novel/web/OrchestratorControllerTest.java`:
```java
package com.writing.novel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.web.dto.CreateProjectRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.File;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "persistence.embedding=stub",
        "persistence.sqlite.db-path=./data/test-web2.db",
        "persistence.vector-store.index-path=./data/test-web2-vecs.idx"
})
class OrchestratorControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;

    @org.junit.jupiter.api.BeforeAll
    static void createDataDir() { new File("./data").mkdirs(); }

    private String createProject() throws Exception {
        CreateProjectRequest req = new CreateProjectRequest("t", "c", "tag", "SOLO");
        String json = mvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andReturn().getResponse().getContentAsString();
        return om.readTree(json).get("id").asText();
    }

    @Test
    void 运行构思阶段并查询状态() throws Exception {
        String id = createProject();

        // 运行构思
        mvc.perform(post("/api/projects/" + id + "/run"))
                .andExpect(status().isOk());

        // 查询状态（应已从 CONCEIVING 推进）
        mvc.perform(get("/api/projects/" + id + "/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("PLANNING"));
    }
}
```

- [ ] **Step 2: Create OrchestratorController**

`src/main/java/com/writing/novel/web/OrchestratorController.java`:
```java
package com.writing.novel.web;

import com.writing.novel.domain.NovelState;
import com.writing.novel.domain.Project;
import com.writing.novel.orchestrator.NovelOrchestrator;
import com.writing.novel.orchestrator.ProjectService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/projects/{id}")
public class OrchestratorController {

    private final NovelOrchestrator orchestrator;
    private final ProjectService projectService;

    public OrchestratorController(NovelOrchestrator orchestrator, ProjectService projectService) {
        this.orchestrator = orchestrator;
        this.projectService = projectService;
    }

    /** 从当前状态推进一步 */
    @PostMapping("/run")
    public Map<String, Object> run(@PathVariable String id) {
        orchestrator.step(id);
        return status(id);
    }

    /** 查询当前状态 */
    @GetMapping("/status")
    public Map<String, Object> status(@PathVariable String id) {
        Project p = projectService.find(id).orElseThrow();
        return Map.of(
                "projectId", id,
                "state", p.getState().name(),
                "mode", p.getMode().name(),
                "currentChapter", p.getCurrentChapterIndex()
        );
    }
}
```

- [ ] **Step 3-6: Run, verify, commit**

```bash
mvn test -Dtest=OrchestratorControllerTest
git add src/main/java/com/writing/novel/web/OrchestratorController.java \
        src/test/java/com/writing/novel/web/OrchestratorControllerTest.java
git commit -m "feat(web): OrchestratorController 运行控制 API"
```

---

## Task 3: ContentController — 设定与章节查询 API

**Files:**
- Create: `src/main/java/com/writing/novel/web/ContentController.java`
- Test: `src/test/java/com/writing/novel/web/ContentControllerTest.java`

- [ ] **Step 1: 写测试**

`src/test/java/com/writing/novel/web/ContentControllerTest.java`:
```java
package com.writing.novel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.web.dto.CreateProjectRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.File;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "persistence.embedding=stub",
        "persistence.sqlite.db-path=./data/test-web3.db",
        "persistence.vector-store.index-path=./data/test-web3-vecs.idx"
})
class ContentControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;

    @org.junit.jupiter.api.BeforeAll
    static void createDataDir() { new File("./data").mkdirs(); }

    @Test
    void 构思后查询世界观和人物() throws Exception {
        String id = createAndConceive();

        mvc.perform(get("/api/projects/" + id + "/worldbuilding"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").exists());

        mvc.perform(get("/api/projects/" + id + "/characters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").exists());
    }

    @Test
    void 构思后查询大纲() throws Exception {
        String id = createAndConceive();

        mvc.perform(get("/api/projects/" + id + "/outline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.premise").exists());
    }

    @Test
    void 写一章后查询章节() throws Exception {
        String id = createAndConceive();
        // run 两次：构思 → PLANNING → WRITING，再写一章
        mvc.perform(post("/api/projects/" + id + "/run"));
        mvc.perform(post("/api/projects/" + id + "/write-chapter"));

        mvc.perform(get("/api/projects/" + id + "/chapters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").exists());
    }

    private String createAndConceive() throws Exception {
        CreateProjectRequest req = new CreateProjectRequest("t", "c", "tag", "SOLO");
        String json = mvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andReturn().getResponse().getContentAsString();
        String id = om.readTree(json).get("id").asText();
        mvc.perform(post("/api/projects/" + id + "/run"));  // conceive
        return id;
    }
}
```

- [ ] **Step 2: Create ContentController**

`src/main/java/com/writing/novel/web/ContentController.java`:
```java
package com.writing.novel.web;

import com.writing.novel.domain.*;
import com.writing.novel.orchestrator.NovelOrchestrator;
import com.writing.novel.spi.NovelRepository;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/projects/{id}")
public class ContentController {

    private final NovelRepository repository;
    private final NovelOrchestrator orchestrator;

    public ContentController(NovelRepository repository, NovelOrchestrator orchestrator) {
        this.repository = repository;
        this.orchestrator = orchestrator;
    }

    @GetMapping("/worldbuilding")
    public List<KnowledgeEntry> worldbuilding(@PathVariable String id) {
        return repository.findKnowledgeEntriesByProject(id);
    }

    @GetMapping("/characters")
    public List<Character> characters(@PathVariable String id) {
        return repository.findCharactersByProject(id);
    }

    @GetMapping("/outline")
    public Outline outline(@PathVariable String id) {
        return repository.findOutlineByProject(id).orElse(null);
    }

    @GetMapping("/chapters")
    public List<Chapter> chapters(@PathVariable String id) {
        return repository.findChaptersByProject(id);
    }

    @GetMapping("/chapters/{chapterIndex}")
    public Chapter chapter(@PathVariable String id, @PathVariable int chapterIndex) {
        return repository.findChapterByIndex(id, chapterIndex).orElse(null);
    }

    /** 手动写一章 */
    @PostMapping("/write-chapter")
    public Chapter writeChapter(@PathVariable String id) {
        return orchestrator.runWritingOneChapter(id);
    }

    @GetMapping("/memory/summary")
    public List<Summary> summaries(@PathVariable String id) {
        return repository.findSummariesByProject(id);
    }
}
```

- [ ] **Step 3-6: Run, verify, commit**

```bash
mvn test -Dtest=ContentControllerTest
git add src/main/java/com/writing/novel/web/ContentController.java \
        src/test/java/com/writing/novel/web/ContentControllerTest.java
git commit -m "feat(web): ContentController 设定与章节查询 API"
```

---

## Task 4: 全链路冒烟测试 + mvn verify 最终验收

**Files:**
- Test: `src/test/java/com/writing/novel/web/FullStackSmokeTest.java`

- [ ] **Step 1: 全链路测试**

`src/test/java/com/writing/novel/web/FullStackSmokeTest.java`:
```java
package com.writing.novel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.web.dto.CreateProjectRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.File;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "persistence.embedding=stub",
        "persistence.sqlite.db-path=./data/test-full.db",
        "persistence.vector-store.index-path=./data/test-full-vecs.idx"
})
class FullStackSmokeTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;

    @org.junit.jupiter.api.BeforeAll
    static void createDataDir() { new File("./data").mkdirs(); }

    @Test
    void 全链路_创建_构思_规划_写章节_查询() throws Exception {
        // 1. 创建项目
        CreateProjectRequest req = new CreateProjectRequest("全链路测试", "剑修复仇玄幻故事", "玄幻,热血", "SOLO");
        String json = mvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String id = om.readTree(json).get("id").asText();

        // 2. 运行构思（CONCEIVING → PLANNING）
        mvc.perform(post("/api/projects/" + id + "/run"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/projects/" + id + "/status"))
                .andExpect(jsonPath("$.state").value("PLANNING"));

        // 3. 推进到写作（PLANNING → WRITING）
        mvc.perform(post("/api/projects/" + id + "/run"));
        mvc.perform(get("/api/projects/" + id + "/status"))
                .andExpect(jsonPath("$.state").value("WRITING"));

        // 4. 写第一章
        mvc.perform(post("/api/projects/" + id + "/write-chapter"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chapterIndex").value(1));

        // 5. 查询世界观、人物、大纲、章节
        mvc.perform(get("/api/projects/" + id + "/worldbuilding"))
                .andExpect(jsonPath("$[0]").exists());
        mvc.perform(get("/api/projects/" + id + "/characters"))
                .andExpect(jsonPath("$[0]").exists());
        mvc.perform(get("/api/projects/" + id + "/outline"))
                .andExpect(jsonPath("$.premise").exists());
        mvc.perform(get("/api/projects/" + id + "/chapters"))
                .andExpect(jsonPath("$[0].chapterIndex").value(1));
    }
}
```

- [ ] **Step 2: 运行全量测试**

Run: `mvn clean test`
Expected: 全部 PASS

- [ ] **Step 3: 最终验收**

Run: `mvn clean verify`
Expected: BUILD SUCCESS

- [ ] **Step 4: 提交**

```bash
git add src/test/java/com/writing/novel/web/FullStackSmokeTest.java
git commit -m "test(web): 全链路冒烟测试（创建→构思→写作→查询）"
```

---

## 自审记录

- ✅ 项目控制 API（创建/查询/列表/模式切换/删除）— Task 1
- ✅ 运行控制 API（run/status）— Task 2
- ✅ 内容查询 API（世界观/人物/大纲/章节/摘要）— Task 3
- ✅ 全链路冒烟测试 — Task 4
- ✅ 所有 API 可通过 MockMvc/curl 验证，不依赖前端
- 注意：SSE 流式推送（/stream）在 MVP 阶段简化为 POST 同步调用，SSE 留作后续增强（spec 8.5 节）
