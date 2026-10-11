package com.writing.novel.service;

import com.writing.novel.domain.AssertionSupport;
import com.writing.novel.persistence.sqlite.AssertionSupportRepository;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-053: 来源撤回的依赖传播——AND 缺一失效/OR 余一保留；
 * 无外根循环不自证；传播至全部依赖者。
 */
class SupportPropagationTest {

    @TempDir
    Path tempDir;
    private AssertionSupportRepository repo;
    private SupportPropagationService service;

    @BeforeEach
    void setUp() {
        var cp = new ConnectionProvider(tempDir.resolve("sp.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new AssertionSupportRepository(cp);
        // 外根存在性显式登记（生产由事实表背书；测试登记各用例的根节点）
        service = new SupportPropagationService(repo,
                java.util.Set.of("root1", "root2", "root3", "independent"));
    }

    @Test
    void AND缺一失效_断言级联失效() {
        // fact-A 需要 AND fact-P1 AND fact-P2；fact-P1 需要 OR root1/root2
        repo.save(new AssertionSupport("p1", "fact-A", AssertionSupport.Link.AND_PREMISE, "fact-P1", "s"));
        repo.save(new AssertionSupport("p1", "fact-A", AssertionSupport.Link.AND_PREMISE, "fact-P2", "s"));
        var p1root1 = repo.save(new AssertionSupport("p1", "fact-P1", AssertionSupport.Link.OR_SOURCE, "root1", "s"));
        repo.save(new AssertionSupport("p1", "fact-P1", AssertionSupport.Link.OR_SOURCE, "root2", "s"));
        repo.save(new AssertionSupport("p1", "fact-P2", AssertionSupport.Link.OR_SOURCE, "root3", "s"));

        assertTrue(service.isAlive("p1", "fact-A"));
        // 撤 root1：P1 余一保留 → A 存活
        var failed = service.withdrawAndPropagate("p1", p1root1.getId());
        assertFalse(failed.contains("fact-A"), "OR 余一保留: " + failed);
        assertTrue(service.isAlive("p1", "fact-P1"), "P1 余一存活");
        // 再撤 root2 支持行：P1 全 OR 失效 → A 的 AND 缺一 → A 失效（级联）
        var row2 = repo.findByFact("p1", "fact-P1").stream()
                .filter(s -> s.getSupportFactId().equals("root2")).findFirst().orElseThrow();
        failed = service.withdrawAndPropagate("p1", row2.getId());
        assertFalse(service.isAlive("p1", "fact-P1"), "P1 全 OR 失效");
        assertFalse(service.isAlive("p1", "fact-A"), "AND 级联失效");
        assertTrue(failed.contains("fact-A"), "级联失效入传播结果");
    }

    @Test
    void OR余一保留_撤单源不断言失效() {
        repo.save(new AssertionSupport("p1", "fact-B", AssertionSupport.Link.OR_SOURCE, "root1", "s"));
        repo.save(new AssertionSupport("p1", "fact-B", AssertionSupport.Link.OR_SOURCE, "root2", "s"));
        var s1 = repo.findByFact("p1", "fact-B").stream()
                .filter(s -> s.getSupportFactId().equals("root1")).findFirst().orElseThrow();
        var failed = service.withdrawAndPropagate("p1", s1.getId());
        assertFalse(failed.contains("fact-B"), "余一保留");
        assertTrue(service.isAlive("p1", "fact-B"));
    }

    @Test
    void 无外根循环不自证() {
        // 环：fact-X ←AND← fact-Y ←OR← fact-X（X 支持 Y，Y 又是 X 的 AND 前提）
        repo.save(new AssertionSupport("p1", "fact-Y", AssertionSupport.Link.OR_SOURCE, "fact-X", "s"));
        repo.save(new AssertionSupport("p1", "fact-X", AssertionSupport.Link.AND_PREMISE, "fact-Y", "s"));
        // 撤 Y 的唯一来源（fact-X 支持行）——X 与 Y 都不得靠环自证存活
        var xSupportsY = repo.findByFact("p1", "fact-Y").stream()
                .filter(s -> s.getSupportFactId().equals("fact-X")).findFirst().orElseThrow();
        var failed = service.withdrawAndPropagate("p1", xSupportsY.getId());
        assertFalse(service.isAlive("p1", "fact-X"), "环上节点不自证");
        assertFalse(service.isAlive("p1", "fact-Y"), "环上依赖者失效");
        assertTrue(failed.contains("fact-Y") || failed.contains("fact-X"), "传播覆盖环: " + failed);
    }

    @Test
    void 传播至全部依赖者() {
        // root → B（OR）→ A（AND root2 独立存活）；root → C（AND）
        repo.save(new AssertionSupport("p1", "fact-B", AssertionSupport.Link.OR_SOURCE, "root", "s"));
        repo.save(new AssertionSupport("p1", "fact-B", AssertionSupport.Link.AND_PREMISE, "independent", "s"));
        repo.save(new AssertionSupport("p1", "fact-C", AssertionSupport.Link.AND_PREMISE, "root", "s"));
        // 撤 C→root 支持行：C 的 AND 缺一失效；B 的 root 来源行未撤，且 independent 存活 → B 存活
        var cRow = repo.findByFact("p1", "fact-C").stream()
                .filter(s -> s.getSupportFactId().equals("root")).findFirst().orElseThrow();
        var failed = service.withdrawAndPropagate("p1", cRow.getId());
        assertTrue(service.isAlive("p1", "fact-B"), "B 的支持行未撤，AND 存活者保留");
        assertFalse(service.isAlive("p1", "fact-C"), "C 的 AND 缺一失效");
        assertTrue(failed.contains("fact-C"), "全部依赖者传播");
        assertFalse(failed.contains("fact-B"), "无关依赖者不受影响");
    }
}
