package com.writing.novel.service;

import com.writing.novel.domain.*;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.persistence.sqlite.SqliteStoryCanon;
import com.writing.novel.persistence.sqlite.StoryFactRepository;
import com.writing.novel.spi.StoryCanon;
import com.writing.novel.telemetry.EventLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-009（方案 §09/R03/F04）：来源撤回与支持传播。
 * 唯一证据来源失效（来源稿被修订）→ 事实转 UNSUPPORTED + 事件 + 停止注入；
 * 存在其他有效证据则保留；作者修订路径自动触发传播。
 */
class SupportWithdrawalTest {

    @TempDir
    Path tempDir;

    private SqliteNovelRepository repo;
    private StoryFactRepository factRepo;
    private SqliteStoryCanon canon;
    private SupportWithdrawalService withdrawal;
    private String projectId;
    private EventLog.Listener listener;
    private final List<String> events = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        var cp = new ConnectionProvider(tempDir.resolve("withdraw.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        factRepo = new StoryFactRepository(cp);
        canon = new SqliteStoryCanon(factRepo, repo);
        withdrawal = new SupportWithdrawalService(factRepo);
        Project project = new Project("撤回项目", "构思", "武侠");
        repo.saveProject(project);
        projectId = project.getId();
        listener = e -> events.add(String.valueOf(e.get("event")));
        EventLog.subscribe(listener);
    }

    @AfterEach
    void tearDown() {
        EventLog.unsubscribe(listener);
    }

    private StoryFact factWithEvidence(String subject, String assertion, Object[][] evidence) {
        StoryFact fact = new StoryFact(projectId, StoryFact.FactType.WORLD_RULE, subject, assertion, 8);
        canon.harvest(fact);
        StoryFact saved = canon.recallBySubject(projectId, subject).get(0);
        for (Object[] ev : evidence) {
            factRepo.saveFactEvidence(java.util.UUID.randomUUID().toString(), saved.getId(), projectId,
                    (int) ev[0], (int) ev[1], "hash-" + ev[0] + "-" + ev[1], (String) ev[2], 1,
                    "anchor-" + ev[0], (int) ev[0], null);
        }
        return saved;
    }

    @Test
    void 唯一证据失效_事实转UNSUPPORTED_停止注入() {
        StoryFact f = factWithEvidence("密信", "密信由江砚交给沈昭",
                new Object[][]{{8, 1, "江砚把密信交到沈昭手中"}});
        assertTrue(canon.recallBySubject(projectId, "密信").size() == 1, "前置：事实在册");

        withdrawal.onChapterRevised(projectId, 8, 2);

        assertTrue(canon.recallBySubject(projectId, "密信").isEmpty(),
                "唯一证据失效后事实失去支持，不得继续注入（recall 只返回 ACTIVE）");
        assertEquals(StoryFact.Status.UNSUPPORTED,
                canon.findAll(projectId).stream().filter(x -> x.getId().equals(f.getId())).findFirst().orElseThrow().getStatus());
        assertTrue(events.contains("FACT_UNSUPPORTED"), "必须留 FACT_UNSUPPORTED 事件");
        var evidence = factRepo.findByFactId(projectId, f.getId());
        assertTrue(evidence.isEmpty() || evidence.stream().allMatch(this::isStale),
                "旧稿版本的证据标记失效");
    }

    @Test
    void 双证据删一_事实保留支持() {
        factWithEvidence("密信下落", "密信由江砚交给沈昭",
                new Object[][]{{8, 1, "江砚把密信交到沈昭手中"}, {12, 1, "沈昭收下了那封信"}});
        withdrawal.onChapterRevised(projectId, 8, 2);

        assertEquals(1, canon.recallBySubject(projectId, "密信下落").size(),
                "仍有第 12 章有效证据——事实保留 ACTIVE");
        assertFalse(events.contains("FACT_UNSUPPORTED"));
    }

    @Test
    void 证据绑定的稿版本等于当前版本时不失效() {
        factWithEvidence("焚信", "江砚焚毁了密信",
                new Object[][]{{8, 2, "江砚把信凑近烛火"}});
        withdrawal.onChapterRevised(projectId, 8, 2);
        assertEquals(1, canon.recallBySubject(projectId, "焚信").size(),
                "证据版本==当前版本（新稿证据）不受影响");
    }

    @Test
    void 后续重新收割可恢复为ACTIVE() {
        StoryFact f = factWithEvidence("密信", "密信由江砚交给沈昭",
                new Object[][]{{8, 1, "江砚把密信交到沈昭手中"}});
        withdrawal.onChapterRevised(projectId, 8, 2);
        assertTrue(canon.recallBySubject(projectId, "密信").isEmpty());

        // 新稿重新确立事实 + 新证据 → 恢复可用
        StoryFact revived = new StoryFact(projectId, StoryFact.FactType.WORLD_RULE,
                "密信", "密信残片由沈昭保存", 9);
        canon.harvest(revived);
        factRepo.saveFactEvidence(java.util.UUID.randomUUID().toString(),
                canon.recallBySubject(projectId, "密信").get(0).getId(), projectId,
                9, 1, "hash-9", "沈昭把残片收进袖中", 1, "anchor-9", 9, null);
        assertEquals(1, canon.recallBySubject(projectId, "密信").size(), "重新收割后恢复注入");
    }

    @Test
    void 作者修订章节自动触发传播() {
        // 章节 8 v1 + 事实证据
        Chapter ch = new Chapter(projectId, 8, "第八章");
        ch.setContent("第八章正文，江砚把密信交到沈昭手中。".repeat(60));
        ch.setStatus(Chapter.Status.DONE);
        repo.saveChapter(ch);
        factWithEvidence("交接", "江砚交出密信",
                new Object[][]{{8, 1, "江砚把密信交到沈昭手中"}});

        // 作者直接编辑推进版本（真实边界）
        ChapterRevisionService service = new ChapterRevisionService(repo, null, null, withdrawal);
        int version = repo.findChapterByIndex(projectId, 8).orElseThrow().getVersion();
        Chapter edited = service.edit(projectId, 8, version, "第八章",
                "作者改写后的正文，江砚当场焚毁了密信。".repeat(80), "DIRECT_EDIT");

        assertTrue(edited.getVersion() > version, "版本已推进");
        assertTrue(canon.recallBySubject(projectId, "交接").isEmpty(),
                "作者修订后唯一证据失效 → 事实失去支持停止注入");
        assertTrue(events.contains("FACT_UNSUPPORTED"));
    }

    private boolean isStale(java.util.Map<String, Object> m) {
        Object stale = m.get("stale");
        return stale != null && ((int) stale) == 1;
    }
}
