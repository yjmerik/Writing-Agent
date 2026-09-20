package com.writing.novel.service;

import com.writing.novel.agent.ChapterWriterAgent;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.Chapter;
import com.writing.novel.llm.prompt.PromptTemplateService;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.memory.ContextAssembler;
import com.writing.novel.spi.NovelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties={"llm.scenario.enabled=true", "persistence.embedding=stub"})
@AutoConfigureMockMvc
@DirtiesContext
class AuthorIntentWritingTest {
    private static final Path DIR;
    static { try { DIR=Files.createTempDirectory("intent-writing-"); } catch(Exception e){ throw new ExceptionInInitializerError(e); } }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("persistence.sqlite.db-path", () -> DIR.resolve("novel.db").toString());
        r.add("persistence.vector-store.index-path", () -> DIR.resolve("vectors.idx").toString());
    }
    @Autowired MockMvc mvc;
    @Autowired NovelRepository repository;
    @Autowired ContextAssembler assembler;
    @Autowired ChapterWriterAgent writer;
    @Autowired PromptTemplateService prompts;
    @MockBean LlmGateway gateway;
    private final AtomicReference<LlmRequest> sent=new AtomicReference<>();
    private String id;
    @BeforeEach void setup() {
        var project=new Project("约定写作", "账房寻找失踪的友人", "悬疑");
        repository.saveProject(project); id=project.getId();
        when(gateway.complete(any())).thenAnswer(call -> {
            sent.set(call.getArgument(0));
            return new LlmResponse("雨停了，账房合上册子，走进巷子寻找失踪的友人。".repeat(130),100,2000);
        });
    }
    private void save(String json) throws Exception {
        mvc.perform(put("/api/projects/"+id+"/intent").contentType("application/json").content(json))
                .andExpect(status().isOk());
    }
    @Test void savedContractAndNextRevisionReachActualWriterRequest() throws Exception {
        save("{\"pov\":\"第一人称旧约定\",\"languageSamples\":\"我推开门，没有回头。\"}");
        writer.write(id,1,"入巷","账房进入小巷",null);
        assertNotNull(sent.get());
        assertTrue(sent.get().getSystemPrompt().contains("第一人称旧约定"));
        assertTrue(sent.get().getSystemPrompt().contains("我推开门，没有回头。"));
        assertTrue(sent.get().getSystemPrompt().contains("第1版"));
        save("{\"pov\":\"第三人称新约定\",\"languageSamples\":\"门闩滑开，他站在阴影里。\"}");
        writer.write(id,2,"门后","账房寻找证人",null);
        String prompt=sent.get().getSystemPrompt();
        assertTrue(prompt.contains("第三人称新约定"));
        assertTrue(prompt.contains("门闩滑开，他站在阴影里。"));
        assertTrue(prompt.contains("第2版"));
        assertFalse(prompt.contains("第一人称旧约定"));
        assertEquals(2, repository.findChaptersByProject(id).size());
    }
    @Test void blankContractKeepsPromptByteIdenticalAndOtherBooksDoNotInheritIt() throws Exception {
        String before=assembler.assemble(id,1,"入巷","寻找证人",null).toSystemPrompt();
        save("{}");
        assertEquals(before,assembler.assemble(id,1,"入巷","寻找证人",null).toSystemPrompt());
        var other=new Project("另一本", "故事", "武侠"); repository.saveProject(other);
        save("{\"pov\":\"绝不可串书的私有视角\"}");
        assertFalse(assembler.assemble(other.getId(),1,"开篇","开始",null).toSystemPrompt().contains("绝不可串书的私有视角"));
    }
    @Test void insufficientBudgetRejectsContractWithoutSendingOrSavingText() throws Exception {
        save("{\"languageSamples\":\""+"不可截断的样本".repeat(1000)+"\"}");
        assertThrows(IllegalStateException.class,()->assembler.assemble(id,1,"入巷","寻找证人",null,200));
        assertNull(sent.get());
        assertTrue(repository.findChaptersByProject(id).isEmpty());
    }
    @Test void correctionReceivesCompleteCurrentDraftWithLiteralSamplesAndReviewEvidence() throws Exception {
        save("{\"pov\":\"第三人称限制视角\",\"languageSamples\":\"雨沿檐角滴进缺口的瓦盆。\"}");
        var chapter = new Chapter(id, 1, "入巷");
        chapter.setContent("旧稿不可用于本次纠偏。".repeat(220));
        repository.saveChapter(chapter);
        String current = "原稿起点{{literal}}：纸在门外，尚未拾取。\n"
                + "账房只会算学，不会改写命债。".repeat(220) + "\n原稿终点：巡夜人还没有交付凭牌。";
        chapter.setContent(current);
        repository.saveChapter(chapter);
        var other = new Project("另一本", "不同作品", "武侠"); repository.saveProject(other);
        var otherChapter = new Chapter(other.getId(), 1, "入巷");
        otherChapter.setContent("其他作品不得进入纠偏".repeat(200)); repository.saveChapter(otherChapter);
        String findings = "纸张转移缺失；主角越界改写命债；凭牌来源不明。";
        String candidate = writer.rewriteForCorrection(id, 1, "入巷", "保留查证债务的目的", null, findings);
        assertNotNull(candidate);
        String request = sent.get().getSystemPrompt() + "\n" + sent.get().getUserMessage();
        assertTrue(request.contains(current), "纠偏必须看到完整的当前原稿，不能仅按计划另写一章");
        assertTrue(request.contains("原稿版本：3"));
        assertTrue(request.contains(findings));
        assertTrue(request.contains("第三人称限制视角"));
        assertFalse(request.contains("旧稿不可用于本次纠偏"));
        assertFalse(request.contains("其他作品不得进入纠偏"));
        assertEquals(current, repository.findChapterByIndex(id, 1).orElseThrow().getContent(),
                "返回候选不能自行覆盖原稿");
    }
    @Test void correctionWithoutSourceDraftDoesNotSendPaidRequest() {
        assertThrows(IllegalStateException.class, () -> writer.rewriteForCorrection(
                id, 1, "入巷", "寻找证人", null, "补足凭牌来源"));
        assertNull(sent.get());
        assertTrue(repository.findChaptersByProject(id).isEmpty());
    }
    @Test void correctionRejectsOversizedDraftInsteadOfTruncatingIt() {
        var chapter = new Chapter(id, 1, "入巷");
        String original = "不能丢失的证据交接过程".repeat(4000);
        chapter.setContent(original); repository.saveChapter(chapter);
        var boundedWriter = new ChapterWriterAgent(gateway, assembler, repository, prompts);
        assertThrows(IllegalStateException.class, () -> boundedWriter.rewriteForCorrection(
                id, 1, "入巷", "寻找证人", null, "补足凭牌来源"));
        assertNull(sent.get());
        assertEquals(original, repository.findChapterByIndex(id, 1).orElseThrow().getContent());
    }
}
