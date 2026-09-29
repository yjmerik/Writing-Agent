package com.writing.novel.agent;

import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.domain.Foreshadowing;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.StoryFact;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.persistence.sqlite.SqliteStoryCanon;
import com.writing.novel.persistence.sqlite.StoryFactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-120: 伏笔台账 id 引用链——回收对账以 id 为主键（同义改写不错位、
 * 幻觉 id 拒绝），回收同步真典 PLOT_COMMITMENT。
 */
class ForeshadowIdChainTest {

    @TempDir
    Path tempDir;
    private SqliteNovelRepository repository;
    private SqliteStoryCanon canon;
    private String pid;
    private Foreshadowing sword;
    private Foreshadowing bell;
    private final AtomicReference<String> lastSystemPrompt = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("idchain.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repository = new SqliteNovelRepository(cp);
        canon = new SqliteStoryCanon(new StoryFactRepository(cp));
        var project = new Project("id链", "构思", "wuxia");
        repository.saveProject(project);
        pid = project.getId();

        sword = new Foreshadowing(pid, "断剑的来历之谜", "开篇", 1);
        sword.setPriority(Foreshadowing.Priority.HIGH);
        repository.saveForeshadowing(sword);
        bell = new Foreshadowing(pid, "沉钟为何而鸣", "中段", 2);
        repository.saveForeshadowing(bell);
    }

    private ForeshadowingResolutionDetector detectorReturning(String json) {
        LlmGateway gateway = new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest req) {
                lastSystemPrompt.set(req.getSystemPrompt());
                return new LlmResponse(json, 10, 50);
            }
            @Override public void streamComplete(LlmRequest req, StreamHandler h) {}
        };
        var detector = new ForeshadowingResolutionDetector(gateway, repository, TestPromptHelper.create());
        detector.setStoryCanon(canon);
        return detector;
    }

    @Test
    void 列表携带id_模板契约含id主键() {
        detectorReturning("{\"resolved\":[]}").detectAndResolve(pid, 5, "正文内容足够长。".repeat(30));
        String prompt = lastSystemPrompt.get();
        assertTrue(prompt.contains("[F1]") || prompt.contains("[F2]"),
                "台账列表必须携带稳定 F 标号（UUID 嵌入会破坏回放确定性）");
        assertTrue(prompt.contains("id 必须逐字复制"), "输出契约必须说明 id 主键");
        assertTrue(prompt.contains("id 不在列表中的条目将被拒绝"));
    }

    @Test
    void id对账_同义改写描述也能命中正确伏笔() {
        // 描述与台账完全不同（同义改写），F 标号正确 → 命中 sword 而非 bell（旧语义匹配会错位/失败）
        // deterministicOrder：sword(埋第1章) → F1，bell(埋第2章) → F2
        String json = "{\"resolved\":[{\"id\":\"F1\",\"description\":\"那把残缺兵器的身世终于揭晓\","
                + "\"reason\":\"铁匠认出断剑乃先王遗物\"}]}";
        int resolved = detectorReturning(json).detectAndResolve(pid, 5, "正文。".repeat(200));
        assertEquals(1, resolved);
        assertTrue(repository.findForeshadowingsByProject(pid).stream()
                .filter(f -> f.getId().equals(sword.getId())).findFirst().orElseThrow().isResolved());
        assertTrue(repository.findUnresolvedForeshadowings(pid).stream()
                .anyMatch(f -> f.getId().equals(bell.getId())), "无关伏笔不受影响");
    }

    @Test
    void 幻觉id拒绝_无证据拒绝() {
        String hallucinated = "{\"resolved\":[{\"id\":\"no-such-id\",\"description\":\"断剑\",\"reason\":\"揭晓\"}]}";
        assertEquals(0, detectorReturning(hallucinated).detectAndResolve(pid, 5, "正文。".repeat(200)),
                "台账中不存在的 id 条目必须拒绝");
        String noEvidence = "{\"resolved\":[{\"id\":\"" + sword.getId() + "\",\"reason\":\"\"}]}";
        assertEquals(0, detectorReturning(noEvidence).detectAndResolve(pid, 5, "正文。".repeat(200)),
                "缺证据不得判回收（p32-08 保持）");
    }

    @Test
    void 回收同步真典PLOT_COMMITMENT() {
        StoryFact commitment = new StoryFact(pid, StoryFact.FactType.PLOT_COMMITMENT,
                "主线", "断剑的来历必须揭晓", 1);
        new StoryFactRepository(new ConnectionProvider(tempDir.resolve("idchain.db").toString())).save(commitment);

        String json = "{\"resolved\":[{\"id\":\"F1\",\"reason\":\"铁匠认出断剑乃先王遗物\"}]}";
        int r = detectorReturning(json).detectAndResolve(pid, 7, "正文。".repeat(200));
        assertEquals(1, r);
        var synced = canon.findById(commitment.getId()).orElseThrow();
        assertEquals(7, synced.getLastValidatedChapter(), "承诺事实确认章应刷新到回收章（双承诺系统同步）");
    }
}
