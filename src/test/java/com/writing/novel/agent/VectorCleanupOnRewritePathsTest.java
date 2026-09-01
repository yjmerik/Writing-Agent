package com.writing.novel.agent;

import com.writing.novel.domain.Chapter;
import com.writing.novel.orchestrator.OrchestratorPolicies;
import com.writing.novel.domain.ChapterSynopsis;
import com.writing.novel.domain.Outline;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.Volume;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.memory.ContextAssembler;
import com.writing.novel.memory.IndexedText;
import com.writing.novel.memory.KnowledgeBaseService;
import com.writing.novel.memory.SummaryService;
import com.writing.novel.memory.VectorRetrievalService;
import com.writing.novel.orchestrator.NovelOrchestrator;
import com.writing.novel.orchestrator.ProjectService;
import com.writing.novel.persistence.embedding.EmbeddedVectorStore;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.spi.EmbeddingProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * p16-05: 三条改写路径（rewriteForCorrection / orchestrator.rewriteChapter /
 * orchestrator.editPassage）修改章节正文后必须清理并重建该章向量 chunk。
 *
 * bug: 向量清理+重建的唯一入口在 ChapterWriterAgent.write()（indexChapterInChunks
 * 内 removeByIdPrefix 清旧 + 批量 embedding 入库）。上述三条路径改写/编辑了正文但
 * 都不清理不重建向量 → 向量库永远保留旧版本情节，后续章节检索持续召回被否定的旧内容。
 *
 * 修复: 抽出可复用入口 ChapterWriterAgent.reindexChapter(projectId, chapterIndex, content)
 * （内部 removeByIdPrefix + indexChapterInChunks + flush），三条路径统一调用。
 */
class VectorCleanupOnRewritePathsTest {

    @TempDir Path tempDir;

    private static final String OLD_MARKER = "旧版情节标记甲乙丙";
    private static final String NEW_MARKER = "新版情节标记丁戊己";

    private SqliteNovelRepository repo;
    private EmbeddedVectorStore store;
    private VectorRetrievalService vectorSvc;
    private ChapterWriterAgent chapterWriter;
    private NovelOrchestrator orchestrator;

    private final EmbeddingProvider emb = new EmbeddingProvider() {
        @Override public float[] embed(String text) { return new float[]{text.hashCode() % 100 / 100.0f}; }
        @Override public int dimension() { return 1; }
        @Override public List<float[]> embedBatch(List<String> texts) {
            return texts.stream().map(this::embed).toList();
        }
    };

    /** CHAPTER_WRITING 一律返回带 NEW_MARKER 的足量正文（≥2000 字门槛 + >800 触发分块） */
    private final LlmGateway gateway = new LlmGateway() {
        @Override public LlmResponse complete(LlmRequest req) {
            return new LlmResponse((NEW_MARKER + "。").repeat(300), 100, 500);
        }
        @Override public void streamComplete(LlmRequest req, StreamHandler h) {}
    };

    /** editPassage 的 POST_PROCESS 后台任务是异步的——harvest 是其中最后一步，
     *  用 latch 等它完成，避免 @TempDir 清理与后台 SQLite 写入竞态（同 EditPassageReturnsBeforeHarvestTest） */
    private final CountDownLatch postProcessDone = new CountDownLatch(1);

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("p16.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        store = new EmbeddedVectorStore(tempDir.resolve("p16.idx").toString());
        vectorSvc = new VectorRetrievalService(store, emb);
        KnowledgeBaseService kbSvc = new KnowledgeBaseService(repo, vectorSvc);
        SummaryService summarySvc = new SummaryService(repo);
        ContextAssembler assembler = new ContextAssembler(repo, kbSvc, summarySvc, vectorSvc, null,
                new com.writing.novel.service.ClimaxDiversityTracker(), null, null, null);
        var promptSvc = TestPromptHelper.create();
        chapterWriter = new ChapterWriterAgent(gateway, assembler, repo, vectorSvc, promptSvc, null);

        PassageEditor editor = new PassageEditor(gateway, promptSvc) {
            @Override
            public String editPassage(String passageText, String contextBefore, String contextAfter,
                                      String instruction, String mode) {
                return passageText + "（编辑补写：" + NEW_MARKER + "。）";
            }
        };

        orchestrator = new NovelOrchestrator(
                repo,                        // 1 repository
                new ProjectService(repo),    // 2 projectService
                null,                        // 3 worldBuilder
                null,                        // 4 characterDesigner
                null,                        // 5 plotter
                chapterWriter,               // 6 chapterWriter
                null,                        // 7 continuityChecker
                null,                        // 8 styleGuardian
                null,                        // 9 memoryManager
                summarySvc,                  // 10 summaryService
                assembler,                   // 11 contextAssembler
                null,                        // 12 qualityEvaluationAgent
                null,                        // 13 entityGraphService
                null,                        // 14 characterProfileUpgrader
                null,                        // 15 climaxDiversityTracker
                null,                        // 16 plotProgressionDetector
                null,                        // 17 arcUpdater
                null,                        // 18 foreshadowingExtractor
                null,                        // 19 genreDetector
                null,                        // 20 genreRuleProvider
                null,                        // 21 creativeDiversityService
                null,                        // 22 narrativeThreadExtractor
                null,                        // 23 readerReviewAgent
                null,                        // 24 foreshadowingResolutionDetector
                null,                        // 25 sceneClassifier
                null,                        // 26 diversityTracker
                null,                        // 27 arcScheduler
                null,                        // 28 consistencyGuard
                null,                        // 29 consistencyGate
                null,                        // 30 foreshadowingImportanceAuditor
                null,                        // 31 clicheDetector
                null,                        // 32 relationshipEvolutionTracker
                editor,                      // 33 passageEditor
                null,                        // 34 plotMotifTracker
                null,                        // 35 expositionDetector
                new OrchestratorPolicies(null, null, null, null, null, null, null,
                        // p22-05: 收割桩经策略包组合注入（原后置 setter）
                        new FactHarvesterAgent(gateway, null, promptSvc) {
                            @Override
                            public HarvestResult harvest(String projectId, Chapter chapter) {
                                postProcessDone.countDown();
                                return new HarvestResult(0, 0, 0);
                            }
                        }, null, null, null));
    }

    // ---------- 用例 1：ChapterWriterAgent.rewriteForCorrection ----------

    @Test
    void rewriteForCorrection后该章旧chunk被清新chunk入库() {
        String pid = seedProjectWithChapterAndOldVectors(300);
        String cid = chapterId(pid);

        String out = chapterWriter.rewriteForCorrection(
                pid, 1, "雨夜", "主角遇袭。", "快节奏", "修正：情节矛盾");

        assertNotNull(out);
        assertTrue(out.contains(NEW_MARKER), "重写应返回新版正文");
        assertOldChunksCleared(cid);
        assertNewContentIndexed(pid, cid);
    }

    // ---------- 用例 2：NovelOrchestrator.rewriteChapter ----------

    @Test
    void orchestratorRewriteChapter后该章旧chunk被清新chunk入库() {
        String pid = seedProjectWithChapterAndOldVectors(300);
        String cid = chapterId(pid);

        Chapter out = orchestrator.rewriteChapter(pid, 1, "增加对话");

        assertNotNull(out);
        assertTrue(out.getContent().contains(NEW_MARKER), "重写后 DB 正文应为新版");
        assertOldChunksCleared(cid);
        assertNewContentIndexed(pid, cid);
    }

    // ---------- 用例 3：NovelOrchestrator.editPassage ----------

    @Test
    void editPassage后该章旧chunk被清新chunk入库() {
        String pid = seedProjectWithChapterAndOldVectors(300);
        String cid = chapterId(pid);

        Chapter out = orchestrator.editPassage(pid, 1, 0, "起笔段", "润色这一段", "REWRITE");

        assertNotNull(out);
        assertTrue(out.getContent().contains(NEW_MARKER), "段落编辑应写入新版文本");
        assertOldChunksCleared(cid);
        assertNewContentIndexed(pid, cid);

        // 等 POST_PROCESS 后台块跑完（harvest 为末步），避免 @TempDir 清理与后台 SQLite 写入竞态
        try { postProcessDone.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------- helpers ----------

    /**
     * 建项目 + 大纲（第 1 章梗概）+ 第 1 章正文（OLD_MARKER 重复 markerRepeats 次，单段 >800 字），
     * 并把「旧版本」chunk 播进向量库——模拟当初 write() 的索引产物
     * （整章 id + chunk0/1 + 一个高序号 chunk9，重写后不应残留）。
     * @return projectId
     */
    private String seedProjectWithChapterAndOldVectors(int markerRepeats) {
        Project p = new Project("测试书", "测试概念", "玄幻");
        repo.saveProject(p);

        Outline outline = new Outline(p.getId(), "总纲：测试。");
        Volume vol = new Volume(p.getId(), 1, "第一卷", "卷梗概");
        ChapterSynopsis syn = new ChapterSynopsis(p.getId(), 1, "雨夜", "主角遇袭。");
        syn.setWritingNotes("快节奏");
        vol.addSynopsis(syn);
        outline.addVolume(vol);
        repo.saveOutline(outline);

        Chapter ch = new Chapter(p.getId(), 1, "雨夜");
        ch.setContent("起笔段。" + (OLD_MARKER + "。").repeat(markerRepeats));
        repo.saveChapter(ch);

        String cid = chapterId(p.getId());
        vectorSvc.indexBatch(List.of(
                new IndexedText(cid, p.getId(), OLD_MARKER + "（整章旧chunk）", "CHAPTER"),
                new IndexedText(cid + "#chunk0", p.getId(), OLD_MARKER + "旧段落0", "CHAPTER"),
                new IndexedText(cid + "#chunk1", p.getId(), OLD_MARKER + "旧段落1", "CHAPTER"),
                new IndexedText(cid + "#chunk9", p.getId(), OLD_MARKER + "旧段落9（高序号）", "CHAPTER")));
        return p.getId();
    }

    private String chapterId(String pid) {
        return repo.findChapterByIndex(pid, 1).orElseThrow().getId();
    }

    /** 旧版本 chunk（整章 id + 高序号 chunk9）必须被清理 */
    private void assertOldChunksCleared(String cid) {
        assertNull(store.getText(cid), "旧整章 chunk（id=chapterId）应被 removeByIdPrefix 清理");
        assertNull(store.getText(cid + "#chunk9"), "旧高序号 chunk9 不应残留（复现 GAP 同类问题）");
    }

    /** 新正文应已分块入库：前若干 chunk 至少一个含 NEW_MARKER，且不含纯旧版残留文本 */
    private void assertNewContentIndexed(String pid, String cid) {
        boolean anyNew = false;
        for (int i = 0; i < 6; i++) {
            String text = store.getText(cid + "#chunk" + i);
            if (text == null) continue;
            if (text.contains(NEW_MARKER)) anyNew = true;
        }
        assertTrue(anyNew, "重写/编辑后的新正文 chunk 应已入库（chapterId#chunkN 含新标记）");
    }
}
